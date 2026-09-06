import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 独立、单写线程、单交易对的内存撮合实验。它不是账本，也没有生产 API。
 * 金额契约：price 为 1/10000 报价币的整数 tick；quantity 为整数最小交易单位。
 * 所有历史订单和请求保留到本进程结束，便于证明终态不复活和重试不重复执行。
 * 生产迁移必须另外实现持久化、分区 fencing、恢复、清理策略和事务边界。
 *
 * @author FinCore Reliability Lab
 * @since 2026-09-06
 */
public final class MatchingLab {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_]{1,40}");
    private static final Pattern INTEGER = Pattern.compile("-?[0-9]+");

    /** 文本解析在计时外；保留规范化文本作为请求指纹，字段不同即冲突。 */
    record Command(boolean valid, String fingerprint, String kind, String request,
                   String id, String owner, String side, long price, long quantity, boolean badNumber) {
        static Command parse(String line) {
            String[] t = line.strip().split("\\s+");
            boolean valid = (t.length == 7 && t[0].equals("N")) || (t.length == 4 && t[0].equals("C"));
            if (valid) {
                for (int i = 1; i <= 3; i++) {
                    valid &= ID.matcher(t[i]).matches();
                }
            }
            if (!valid) {
                return new Command(false, "", "", "?", "", "", "", 0, 0, false);
            }
            long price = 0, quantity = 0; boolean badNumber = false;
            if (t[0].equals("N")) {
                try {
                    if (!INTEGER.matcher(t[5]).matches() || !INTEGER.matcher(t[6]).matches()) {
                        throw new NumberFormatException();
                    }
                    price = Long.parseLong(t[5]); quantity = Long.parseLong(t[6]);
                } catch (NumberFormatException expectedInvalidInput) {
                    badNumber = true;
                }
            }
            return new Command(true, String.join(" ", t), t[0], t[1], t[2], t[3], t.length == 7 ? t[4] : "", price, quantity, badNumber);
        }
    }

    /** 对象字段记录守恒关系；FIFO 由价位内 LinkedHashMap 插入顺序决定。 */
    static final class Order {
        final String id, owner, side;
        final long price, original, sequence;
        long remaining, filled, cancelled, executed;
        Order(Command c, long sequence) {
            id = c.id; owner = c.owner; side = c.side; price = c.price;
            original = c.quantity; remaining = c.quantity; this.sequence = sequence;
        }
    }
    record Trade(String maker, long quantity, long price) {}
    record Result(String kind, String request, String id, long remaining, long cancelled, List<Trade> trades, String error) {
        static Result reject(String request, String code) { return new Result("R", request, "", 0, 0, List.of(), code); }
        String encode() {
            if (kind.equals("R")) {
                return "R|" + request + "|" + error;
            }
            if (kind.equals("C")) {
                return "C|" + request + "|" + id + "|" + cancelled;
            }
            StringBuilder b = new StringBuilder("A|").append(request).append('|').append(id).append('|').append(remaining).append('|');
            for (Trade t : trades) {
                if (b.charAt(b.length() - 1) != '|') {
                    b.append(',');
                }
                b.append(t.maker).append(':').append(t.quantity).append(':').append(t.price);
            }
            if (trades.isEmpty()) {
                b.append('-');
            }
            return b.append('|').append(cancelled).toString();
        }
    }
    record Cached(String fingerprint, Result result) {}

    /** 串行状态机；业务拒绝发生在修改前，进程失败没有恢复或事务承诺。 */
    static final class Engine {
        // 红黑树定位最优价格 O(log P)，价位内插入/撤单平均 O(1)。对象分配与指针追踪均在测量范围。
        final NavigableMap<Long, LinkedHashMap<String, Order>> bids = new TreeMap<>(Comparator.reverseOrder());
        final NavigableMap<Long, LinkedHashMap<String, Order>> asks = new TreeMap<>();
        final Map<String, Order> orders = new HashMap<>();
        final Map<String, Cached> requests = new HashMap<>();
        long sequence;

        Result apply(Command c) {
            if (!c.valid) {
                return Result.reject("?", "BAD_PROTOCOL");
            }
            Cached previous = requests.get(c.request);
            if (previous != null) {
                return previous.fingerprint.equals(c.fingerprint) ? previous.result : Result.reject(c.request, "DUPLICATE_KEY");
            }
            Result result = c.kind.equals("C") ? cancel(c) : place(c);
            requests.put(c.request, new Cached(c.fingerprint, result));
            return result;
        }

        Result cancel(Command c) {
            Order o = orders.get(c.id);
            if (o == null) {
                return Result.reject(c.request, "NOT_FOUND");
            }
            if (!o.owner.equals(c.owner)) {
                return Result.reject(c.request, "NOT_OWNER");
            }
            if (o.remaining == 0) {
                return Result.reject(c.request, "TERMINAL");
            }
            long cancelled = o.remaining;
            remove(o); o.cancelled += cancelled; o.remaining = 0;
            return new Result("C", c.request, c.id, 0, cancelled, List.of(), "");
        }

        Result place(Command c) {
            if (!c.side.equals("B") && !c.side.equals("S")) {
                return Result.reject(c.request, "BAD_SIDE");
            }
            if (c.badNumber) {
                return Result.reject(c.request, "BAD_NUMBER");
            }
            if (c.price <= 0 || c.quantity <= 0) {
                return Result.reject(c.request, "NON_POSITIVE");
            }
            // 除法式上界检查先于任何订单/价位修改，避免溢出后部分成交。
            if (c.price > Long.MAX_VALUE / c.quantity) {
                return Result.reject(c.request, "OVERFLOW");
            }
            if (orders.containsKey(c.id)) {
                return Result.reject(c.request, "ORDER_EXISTS");
            }
            if (executionWouldOverflow(c)) {
                return Result.reject(c.request, "OVERFLOW");
            }
            Order taker = new Order(c, sequence++);
            NavigableMap<Long, LinkedHashMap<String, Order>> opposite = c.side.equals("B") ? asks : bids;
            List<Trade> trades = new ArrayList<>();
            while (taker.remaining > 0 && !opposite.isEmpty()) {
                long price = opposite.firstKey();
                if (c.side.equals("B") ? price > c.price : price < c.price) {
                    break;
                }
                Order maker = opposite.firstEntry().getValue().values().iterator().next();
                // 与现有 MatchingService 的 CANCEL_TAKER 顺序一致：先前有效成交保留，剩余量撤销。
                if (maker.owner.equals(c.owner)) {
                    taker.cancelled = taker.remaining;
                    taker.remaining = 0;
                    break;
                }
                long quantity = Math.min(taker.remaining, maker.remaining);
                // 每笔成交量不大于 maker 原始量，maker 的 price*original 已验证，不会货币溢出。
                maker.remaining -= quantity; maker.filled += quantity;
                taker.remaining -= quantity; taker.filled += quantity;
                long notional = quantity * maker.price;
                maker.executed += notional; taker.executed += notional;
                trades.add(new Trade(maker.id, quantity, maker.price));
                if (maker.remaining == 0) {
                    remove(maker);
                }
            }
            orders.put(taker.id, taker);
            if (taker.remaining > 0) {
                book(taker).computeIfAbsent(taker.price, ignored -> new LinkedHashMap<>()).put(taker.id, taker);
            }
            return new Result("A", c.request, c.id, taker.remaining, taker.cancelled, trades, "");
        }

        /** 卖单可能以高于限价成交；累积成交额与剩余挂单预留之和也必须可表示。 */
        boolean executionWouldOverflow(Command c) {
            var opposite = c.side.equals("B") ? asks : bids;
            long left = c.quantity, total = 0;
            outer: for (var level : opposite.entrySet()) {
                if (c.side.equals("B") ? level.getKey() > c.price : level.getKey() < c.price) {
                    break;
                }
                for (Order maker : level.getValue().values()) {
                    if (maker.owner.equals(c.owner)) {
                        left = 0;
                        break outer;
                    }
                    long quantity = Math.min(left, maker.remaining), notional = quantity * maker.price;
                    if (total > Long.MAX_VALUE - notional) {
                        return true;
                    }
                    total += notional; left -= quantity;
                    if (left == 0) {
                        break outer;
                    }
                }
            }
            return total > Long.MAX_VALUE - left * c.price;
        }

        NavigableMap<Long, LinkedHashMap<String, Order>> book(Order o) { return o.side.equals("B") ? bids : asks; }
        void remove(Order o) {
            var book = book(o); var level = book.get(o.price);
            level.remove(o.id);
            if (level.isEmpty()) {
                book.remove(o.price);
            }
        }

        /** 验证路径只用于重放；计时路径在整批结束后调用，避免把 O(N) 检查算入引擎延迟。 */
        void verify() {
            HashSet<Order> seen = new HashSet<>();
            checkBook(bids, "B", seen); checkBook(asks, "S", seen);
            for (Order o : orders.values()) {
                require(o.remaining >= 0 && o.filled >= 0 && o.cancelled >= 0, "negative quantity");
                require(o.original - o.remaining - o.filled == o.cancelled, "quantity conservation");
                require(o.executed >= 0 && o.executed <= Long.MAX_VALUE - o.remaining * o.price, "execution notional overflow");
                require((o.remaining > 0) == seen.contains(o), "order/book index disagreement");
            }
            require(bids.isEmpty() || asks.isEmpty() || bids.firstKey() < asks.firstKey(), "crossed book");
        }
        void checkBook(NavigableMap<Long, LinkedHashMap<String, Order>> book, String side, HashSet<Order> seen) {
            for (var entry : book.entrySet()) {
                require(!entry.getValue().isEmpty(), "empty level"); long previous = -1;
                for (Order o : entry.getValue().values()) {
                    require(o.price == entry.getKey() && o.side.equals(side) && o.remaining > 0, "bad level");
                    require(o.sequence > previous && seen.add(o) && orders.get(o.id) == o, "FIFO/index"); previous = o.sequence;
                }
            }
        }
        String snapshot() {
            StringBuilder b = new StringBuilder();
            for (Order o : new TreeMap<>(orders).values()) {
                if (!b.isEmpty()) {
                    b.append(';');
                }
                b.append(o.id).append(':').append(o.owner).append(':').append(o.side).append(':').append(o.price)
                 .append(':').append(o.original).append(':').append(o.remaining).append(':').append(o.filled).append(':').append(o.cancelled).append(':').append(o.executed);
            }
            return b.toString();
        }
    }

    static void require(boolean ok, String reason) {
        if (!ok) {
            throw new IllegalStateException(reason);
        }
    }
    static String hash(String state) {
        long h = 0xcbf29ce484222325L;
        for (byte b : state.getBytes(StandardCharsets.US_ASCII)) {
            h = (h ^ (b & 255)) * 0x100000001b3L;
        }
        // 这是状态散列的有意模 2^64 运算，不参与任何金额计算。
        return Long.toUnsignedString(h);
    }
    static long percentile(long[] sorted, double p) { return sorted[Math.max(0, (int) Math.ceil(sorted.length * p) - 1)]; }
    static volatile long observable;

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && (args[0].equals("--replay") || args[0].equals("--replay-batch"))) {
            Engine engine = new Engine();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                for (String line; (line = in.readLine()) != null;) {
                    System.out.println(engine.apply(Command.parse(line)).encode());
                    if (args[0].equals("--replay")) {
                        engine.verify();
                    }
                }
            }
            engine.verify(); System.out.println("STATE|" + engine.snapshot()); return;
        }
        if (args.length != 3 || !args[0].equals("--bench")) {
            throw new IllegalArgumentException("--replay | --bench FILE WARMUP");
        }
        Path input = Path.of(args[1]);
        List<Command> commands = Files.readAllLines(input).stream().map(Command::parse).toList();
        int warmup = Integer.parseInt(args[2]);
        require(!commands.isEmpty() && warmup > 0, "empty workload or missing warmup");
        for (int pass = 0; pass < warmup; pass++) {
            Engine warm = new Engine();
            for (Command c : commands) {
                warm.apply(c);
            }
            warm.verify(); observable ^= warm.snapshot().length();
        }
        Engine engine = new Engine(); long[] samples = new long[commands.size()];
        var bean = ManagementFactory.getThreadMXBean();
        com.sun.management.ThreadMXBean allocation = bean instanceof com.sun.management.ThreadMXBean a && a.isThreadAllocatedMemorySupported() ? a : null;
        if (allocation != null && !allocation.isThreadAllocatedMemoryEnabled()) {
            allocation.setThreadAllocatedMemoryEnabled(true);
        }
        long thread = Thread.currentThread().threadId();
        long allocatedBefore = allocation == null ? -1 : allocation.getThreadAllocatedBytes(thread);
        long started = System.nanoTime();
        for (int i = 0; i < commands.size(); i++) {
            Command c = commands.get(i); long before = System.nanoTime();
            engine.apply(c); samples[i] = System.nanoTime() - before;
        }
        long elapsed = System.nanoTime() - started;
        long allocated = allocation == null ? -1 : allocation.getThreadAllocatedBytes(thread) - allocatedBefore;
        engine.verify(); String stateHash = hash(engine.snapshot()); observable ^= stateHash.length();
        // 原始样本在计时完成后落盘，保留每个独立进程的分布供复核。
        Path sampleFile = input.resolveSibling(input.getFileName() + ".java.samples." + ProcessHandle.current().pid() + ".csv");
        try (var writer = Files.newBufferedWriter(sampleFile)) {
            writer.write("latency_ns\n");
            for (long sample : samples) {
                writer.write(sample + "\n");
            }
        }
        Arrays.sort(samples);
        System.out.println("{\"samples\":" + samples.length + ",\"elapsedNs\":" + elapsed + ",\"throughputOpsSec\":" + (samples.length * 1e9 / elapsed)
            + ",\"p50Ns\":" + percentile(samples, .5) + ",\"p99Ns\":" + percentile(samples, .99) + ",\"p999Ns\":" + percentile(samples, .999)
            + ",\"maxNs\":" + samples[samples.length - 1] + ",\"allocatedBytesMeasuredThread\":" + (allocated < 0 ? "null" : allocated)
            + ",\"stateHash\":\"" + stateHash + "\",\"sampleFile\":\"" + sampleFile.getFileName() + "\"}");
    }
}

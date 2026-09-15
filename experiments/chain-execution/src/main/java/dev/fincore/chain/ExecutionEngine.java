package dev.fincore.chain;

import static dev.fincore.chain.Models.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.h2.api.Trigger;

/**
 * 文件持久化的 PAPER 账本。数据库行锁串行化不同引擎实例的资金变更，
 * 任何进程内集合都不是最终账本。此 PAPER 内核没有钱包、签名器、RPC 客户端、网络端点或实盘选项。
 *
 * 预占、分录、订单状态和收据去重在同一事务提交。发送意图先提交，再调用合成网关；
 * 发送结果不明确时禁止盲目重试。总开关阻止新执行，但始终允许对账恢复。
 */
public final class ExecutionEngine implements AutoCloseable {
    private static final BigInteger ZERO = BigInteger.ZERO;
    private static final String WALLET_SOL = "00000000-0000-0000-0000-000000000001";
    private static final String RESERVED_SOL = "00000000-0000-0000-0000-000000000002";
    private static final String VENUE_SOL = "00000000-0000-0000-0000-000000000003";
    private static final String FEE_SOL = "00000000-0000-0000-0000-000000000004";
    private static final String WALLET_TOKENS = "00000000-0000-0000-0000-000000000005";
    private static final String RESERVED_TOKENS = "00000000-0000-0000-0000-000000000006";
    private static final String VENUE_TOKENS = "00000000-0000-0000-0000-000000000007";
    private static final Gate[] GATES = createGates();
    private final String jdbcUrl;
    private final Gate gate;
    private final Clock clock;
    private final Connection keeper;
    private volatile boolean closed;

    /** 在指定目录打开独立合成账本；期初资金仅初始化一次，重启不会重复充值。 */
    public ExecutionEngine(Path directory, Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        Path location = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        // H2 将分号后的内容解析为连接设置；文件目录参数不能注入任何连接设置。
        String path = location.toString();
        if (path.indexOf(';') >= 0 || path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("PAPER database directory contains a URL delimiter");
        }
        Connection opened = null;
        try {
            Files.createDirectories(location);
            String databasePath = location.toRealPath().resolve("paper-ledger").toString();
            require(databasePath.indexOf(';') < 0 && databasePath.indexOf('\n') < 0 && databasePath.indexOf('\r') < 0,
                "Canonical PAPER database directory contains a URL delimiter");
            this.jdbcUrl = "jdbc:h2:file:" + databasePath + ";LOCK_TIMEOUT=10000";
            this.gate = GATES[Math.floorMod(databasePath.hashCode(), GATES.length)];
            try (GateLease ignored = gate.enter()) {
                // 全局持久化设置只在打开引擎时应用，业务连接不能反复争用 H2 元数据锁。
                opened = DriverManager.getConnection(jdbcUrl + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0", "sa", "");
                initialize(opened);
            }
            keeper = opened;
        } catch (Exception failure) {
            if (opened != null) {
                try { opened.close(); } catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
            }
            throw new IllegalStateException("Cannot open isolated PAPER ledger", failure);
        }
    }

    /**
     * 按请求号幂等预占资金及费用，同号改动方向或金额将被拒绝。
     * 所有未决预占共同消耗资金预算，卖出只能预占已确认且尚未被占用的持仓。
     */
    public OrderView reserve(Request request, Quote quote) {
        validateRequest(request);
        return transaction((connection, state) -> {
            StoredOrder existing = findOrder(connection, request.requestId());
            if (existing != null) {
                require(existing.view.side() == request.side() && existing.view.input().equals(request.amount()),
                    "Idempotency key already binds a different request");
                return existing.view;
            }
            require(!state.killSwitch, "PAPER kill switch is enabled");
            validateQuote(request, quote);
            require(count(connection, "SELECT COUNT(*) FROM ce_orders WHERE status NOT IN ('FINALIZED','FAILED')")
                < MAX_OPEN_ORDERS, "Too many unresolved orders");
            BigInteger reservedSol = quote.maxNetworkFee().add(request.side() == Side.BUY ? request.amount() : ZERO);
            BigInteger reservedTokens = request.side() == Side.SELL ? request.amount() : ZERO;
            require(state.sol.subtract(state.reservedSol).subtract(reservedSol).compareTo(SOL_FLOOR) >= 0,
                "SOL floor or available SOL would be breached");
            require(state.tokens.subtract(state.reservedTokens).compareTo(reservedTokens) >= 0,
                "Insufficient available FCLAB");
            require(state.grossSolDebits.add(state.reservedSol).add(reservedSol).compareTo(MAX_GROSS_SOL_DEBITS) <= 0,
                "Gross SOL debit budget includes all unresolved reservations");

            String digest = digest(request, quote);
            String attempt = UUID.randomUUID().toString();
            execute(connection, """
                INSERT INTO ce_orders (request_id,side,input_amount,quote_id,expected_output,min_output,max_fee,
                  slippage_bps,price_impact_bps,liquidity_sol,expires_at,status,signature,digest,attempt_id,
                  reserved_sol,reserved_tokens) VALUES (?,?,?,?,?,?,?,?,?,?,?,'RESERVED',NULL,?,?,?,?)
                """, request.requestId(), request.side(), request.amount(), quote.quoteId(), quote.expectedOutput(),
                quote.minOutput(), quote.maxNetworkFee(), quote.slippageBps(), quote.priceImpactBps(),
                quote.liquiditySol(), quote.expiresAt(), digest, attempt, reservedSol, reservedTokens);
            appendJournal(connection, request.requestId(), List.of(
                posting(WALLET_SOL, Asset.SOL, reservedSol.negate()), posting(RESERVED_SOL, Asset.SOL, reservedSol),
                posting(WALLET_TOKENS, Asset.FCLAB, reservedTokens.negate()),
                posting(RESERVED_TOKENS, Asset.FCLAB, reservedTokens)));
            state.reservedSol = state.reservedSol.add(reservedSol);
            state.reservedTokens = state.reservedTokens.add(reservedTokens);
            saveState(connection, state);
            return getOrder(connection, request.requestId()).view;
        });
    }

    /** 接管使所有旧工作者令牌失效，也包括仍未返回的发送回调。 */
    public long takeover() {
        return transaction((connection, state) -> {
            state.epoch = Math.incrementExact(state.epoch);
            saveState(connection, state);
            return state.epoch;
        });
    }

    /**
     * 仅持有当前纪元的工作者可发送一次；先持久化报价摘要和尝试号，再调用合成网关。
     * 超时保留预占并进入 UNKNOWN；回调失去纪元时不得更新状态，后继工作者只能对账。
     */
    public OrderView dispatch(String requestId, long epoch, PaperGateway gateway) {
        Objects.requireNonNull(gateway, "paper gateway");
        DispatchDecision decision = transaction((connection, state) -> {
            fence(state, epoch);
            StoredOrder order = getOrder(connection, requestId);
            if (order.view.status() != Status.RESERVED) return new DispatchDecision(order.view, null);
            require(!state.killSwitch, "PAPER kill switch is enabled");
            Request request = new Request(requestId, order.view.side(), order.view.input());
            validateQuote(request, order.quote);
            require(digest(request, order.quote).equals(order.view.digest()), "Persisted PAPER quote digest mismatch");
            Plan plan = new Plan(requestId, order.view.side(), order.view.input(), order.quote.minOutput(),
                order.quote.maxNetworkFee(), order.view.digest(), order.view.attemptId());
            execute(connection, """
                INSERT INTO ce_dispatch_intents (attempt_id,request_id,digest,worker_epoch,created_at,dispatch_state)
                VALUES (?,?,?,?,?,'DISPATCHING')
                """, plan.attemptId(), requestId, plan.digest(), epoch, clock.instant());
            transition(connection, requestId, Status.DISPATCHING, null);
            return new DispatchDecision(getOrder(connection, requestId).view, plan);
        });
        if (decision.plan == null) return decision.view;

        // 调用外部传入的 PAPER 函数时不持有数据库事务，已落库的意图阻止重复发送。
        String receivedSignature = null;
        try {
            receivedSignature = gateway.submit(decision.plan);
            require(validSignature(receivedSignature), "Invalid PAPER receipt identifier");
        } catch (Exception uncertainDelivery) {
            // 异常只表示结果未知，既不代表成功，也不证明没有送达。
            receivedSignature = null;
        }
        String signature = receivedSignature;
        return transaction((connection, state) -> {
            fence(state, epoch);
            OrderView current = getOrder(connection, requestId).view;
            if (terminal(current.status()) || current.status() == Status.REVIEW) return current;
            if (signature != null && current.signature() != null && !signature.equals(current.signature())) {
                transition(connection, requestId, Status.REVIEW, current.signature());
            } else if (current.status() == Status.DISPATCHING || current.status() == Status.UNKNOWN) {
                // 较早观察已绑定的交易标识不能被迟到的异常回调清空。
                transition(connection, requestId, signature == null ? Status.UNKNOWN : Status.SUBMITTED,
                    signature == null ? current.signature() : signature);
            }
            execute(connection, "UPDATE ce_dispatch_intents SET dispatch_state=? WHERE attempt_id=?",
                signature == null ? "UNKNOWN" : "SUBMITTED", decision.plan.attemptId());
            return getOrder(connection, requestId).view;
        });
    }

    /**
     * 两个指定合成观察源必须对同一尝试的完整收据一致，达到 finalized 后才入账。
     * 查无记录和 confirmed 不释放资金；差异冻结为 REVIEW，禁止本接口自动修复。
     * 最终失败仅扣实际网络费，终态重放不重复记账；总开关不影响此恢复路径。
     */
    public OrderView reconcile(String requestId, long epoch, List<Observation> observations) {
        return transaction((connection, state) -> {
            fence(state, epoch);
            StoredOrder stored = getOrder(connection, requestId);
            OrderView order = stored.view;
            require(order.status() != Status.RESERVED, "An unsent reservation cannot have a receipt");
            if (observations != null) {
                for (Observation observation : observations) {
                    execute(connection, "INSERT INTO ce_observations (request_id,received_at,payload) VALUES (?,?,?)",
                        requestId, clock.instant(), String.valueOf(observation));
                }
            }
            if (terminal(order.status()) || order.status() == Status.REVIEW) return order;
            if (!digest(new Request(requestId, order.side(), order.input()), stored.quote).equals(order.digest())) {
                return review(connection, order);
            }
            if (!agreeingPair(observations, order)) return review(connection, order);
            Observation receipt = observations.getFirst();
            if (receipt.confirmation() == Confirmation.NOT_FOUND) {
                transition(connection, requestId, Status.UNKNOWN, order.signature());
                return getOrder(connection, requestId).view;
            }
            if (!nonnegative(receipt.input()) || !nonnegative(receipt.output()) || !nonnegative(receipt.networkFee())
                || receipt.networkFee().compareTo(stored.quote.maxNetworkFee()) > 0) return review(connection, order);
            if (receipt.confirmation() == Confirmation.CONFIRMED) {
                transition(connection, requestId, Status.CONFIRMING, receipt.signature());
                return getOrder(connection, requestId).view;
            }
            boolean successful = receipt.confirmation() == Confirmation.FINALIZED_SUCCESS;
            if (successful) {
                if (!receipt.input().equals(order.input()) || receipt.output().compareTo(stored.quote.minOutput()) < 0)
                    return review(connection, order);
            } else if (!receipt.input().equals(ZERO) || !receipt.output().equals(ZERO)) {
                return review(connection, order);
            }
            try (PreparedStatement statement = prepare(connection, "SELECT request_id FROM ce_receipts WHERE signature=?", receipt.signature());
                 ResultSet rows = statement.executeQuery()) {
                if (rows.next()) return review(connection, order);
            }
            execute(connection, """
                INSERT INTO ce_receipts (signature,request_id,attempt_id,digest,confirmation,receipt_slot)
                VALUES (?,?,?,?,?,?)
                """, receipt.signature(), requestId, receipt.attemptId(), receipt.digest(), receipt.confirmation(), receipt.slot());
            settle(connection, state, order, receipt, successful);
            return getOrder(connection, requestId).view;
        });
    }

    /** 持久化总开关，阻止新的预占和发送，不阻止查询、接管或对账。 */
    public void setKillSwitch(boolean enabled) {
        transaction((connection, state) -> {
            state.killSwitch = enabled;
            saveState(connection, state);
            return null;
        });
    }

    /** 在同一数据库事务中读取总额、预占、预算和各资产分录校验和。 */
    public Snapshot snapshot() {
        return transaction((connection, state) -> {
            Map<Asset, BigInteger> sums = new EnumMap<>(Asset.class);
            for (Asset asset : Asset.values()) sums.put(asset, ZERO);
            try (PreparedStatement statement = connection.prepareStatement("SELECT asset,SUM(delta) AS total FROM ce_journal GROUP BY asset");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) sums.put(Asset.valueOf(rows.getString("asset")), integer(rows, "total"));
            }
            return new Snapshot(state.sol, state.reservedSol, state.tokens, state.reservedTokens,
                state.grossSolDebits, state.killSwitch, state.epoch,
                count(connection, "SELECT COUNT(*) FROM ce_orders"), count(connection, "SELECT COUNT(*) FROM ce_journal"),
                Map.copyOf(sums));
        });
    }

    /** 返回按持久化序号排列的不可变分录副本；数据库拒绝修改和删除历史分录。 */
    public List<JournalEntry> journal() {
        return transaction((connection, state) -> {
            List<JournalEntry> entries = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM ce_journal ORDER BY sequence_id");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) entries.add(new JournalEntry(rows.getLong("sequence_id"), rows.getString("order_id"),
                    rows.getString("account_id"), Asset.valueOf(rows.getString("asset")), integer(rows, "delta")));
            }
            return List.copyOf(entries);
        });
    }

    /** 读取指定持久化订单；不存在的请求号不会被解释为发送失败。 */
    public OrderView order(String requestId) {
        return transaction((connection, state) -> getOrder(connection, requestId).view);
    }

    /** 关闭本实例持有的连接；已提交资金与未知订单保留在文件账本中。 */
    @Override public void close() {
        if (!closed) {
            closed = true;
            try { keeper.close(); } catch (SQLException failure) { throw new IllegalStateException("PAPER database close failed", failure); }
        }
    }

    private void settle(Connection connection, State state, OrderView order, Observation receipt, boolean successful) throws SQLException {
        BigInteger buyInput = successful && order.side() == Side.BUY ? order.input() : ZERO;
        BigInteger sellInput = successful && order.side() == Side.SELL ? order.input() : ZERO;
        BigInteger solCredit = successful && order.side() == Side.SELL ? receipt.output() : ZERO;
        BigInteger tokenCredit = successful && order.side() == Side.BUY ? receipt.output() : ZERO;
        BigInteger solDebit = buyInput.add(receipt.networkFee());
        appendJournal(connection, order.requestId(), List.of(
            posting(RESERVED_SOL, Asset.SOL, order.reservedSol().negate()),
            posting(WALLET_SOL, Asset.SOL, order.reservedSol().subtract(solDebit).add(solCredit)),
            posting(VENUE_SOL, Asset.SOL, buyInput.subtract(solCredit)),
            posting(FEE_SOL, Asset.SOL, receipt.networkFee()),
            posting(RESERVED_TOKENS, Asset.FCLAB, order.reservedTokens().negate()),
            posting(WALLET_TOKENS, Asset.FCLAB, order.reservedTokens().subtract(sellInput).add(tokenCredit)),
            posting(VENUE_TOKENS, Asset.FCLAB, sellInput.subtract(tokenCredit))));
        state.sol = state.sol.subtract(solDebit).add(solCredit);
        state.tokens = state.tokens.subtract(sellInput).add(tokenCredit);
        state.reservedSol = state.reservedSol.subtract(order.reservedSol());
        state.reservedTokens = state.reservedTokens.subtract(order.reservedTokens());
        state.grossSolDebits = state.grossSolDebits.add(solDebit);
        saveState(connection, state);
        execute(connection, "UPDATE ce_orders SET status=?,signature=?,reserved_sol=0,reserved_tokens=0 WHERE request_id=?",
            successful ? Status.FINALIZED : Status.FAILED, receipt.signature(), order.requestId());
    }

    private static boolean agreeingPair(List<Observation> observations, OrderView order) {
        if (observations == null || observations.size() != 2) return false;
        Observation a = observations.getFirst(), b = observations.getLast();
        if (a == null || b == null || a.confirmation() == null) return false;
        if (!("paper-rpc-a".equals(a.provider()) && "paper-rpc-b".equals(b.provider())
            || "paper-rpc-b".equals(a.provider()) && "paper-rpc-a".equals(b.provider()))) return false;
        return Objects.equals(a.attemptId(), order.attemptId()) && Objects.equals(a.digest(), order.digest())
            && validSignature(a.signature()) && (order.signature() == null || order.signature().equals(a.signature()))
            && a.slot() >= 0 && Objects.equals(a.attemptId(), b.attemptId()) && Objects.equals(a.digest(), b.digest())
            && Objects.equals(a.signature(), b.signature()) && a.confirmation() == b.confirmation()
            && Objects.equals(a.input(), b.input()) && Objects.equals(a.output(), b.output())
            && Objects.equals(a.networkFee(), b.networkFee()) && a.slot() == b.slot();
    }

    private static OrderView review(Connection connection, OrderView order) throws SQLException {
        transition(connection, order.requestId(), Status.REVIEW, order.signature());
        return getOrder(connection, order.requestId()).view;
    }

    private static void transition(Connection connection, String id, Status status, String signature) throws SQLException {
        execute(connection, "UPDATE ce_orders SET status=?,signature=? WHERE request_id=?", status, signature, id);
    }

    private void validateQuote(Request request, Quote quote) {
        require(quote != null && quote.quoteId() != null && !quote.quoteId().isBlank() && quote.quoteId().length() <= 256,
            "A bounded quote identifier is required");
        require(quote.side() == request.side() && request.amount().equals(quote.input()), "Quote does not match request");
        require(positive(quote.expectedOutput()) && positive(quote.minOutput()), "Quote output must be positive integer atoms");
        require(nonnegative(quote.maxNetworkFee()) && quote.maxNetworkFee().compareTo(MAX_FEE) <= 0, "Network fee cap exceeded");
        require(quote.slippageBps() >= 0 && quote.slippageBps() <= 50, "Slippage limit exceeded");
        require(quote.priceImpactBps() >= 0 && quote.priceImpactBps() <= 200, "Price impact limit exceeded");
        require(nonnegative(quote.liquiditySol()) && quote.liquiditySol().compareTo(MIN_POOL_SOL) >= 0, "Pool liquidity too low");
        BigInteger expectedMinimum = quote.expectedOutput().multiply(BigInteger.valueOf(10000L - quote.slippageBps()))
            .divide(BigInteger.valueOf(10000));
        require(expectedMinimum.equals(quote.minOutput()), "Minimum output does not bind the quoted slippage");
        Instant now = clock.instant();
        require(quote.expiresAt() != null && quote.expiresAt().isAfter(now) && !quote.expiresAt().isAfter(now.plusSeconds(60)),
            "Quote must be unexpired and valid for at most 60 seconds");
        require(request.side() != Side.BUY || request.amount().compareTo(MAX_BUY) <= 0, "PAPER buy size exceeded");
    }

    private static void validateRequest(Request request) {
        require(request != null && request.requestId() != null && request.requestId().matches("[0-9A-Za-z_-]{1,64}"),
            "Invalid request identifier");
        require(request.side() != null && positive(request.amount()), "Positive integer amount and side required");
    }

    // 输入保留算术余量，避免超过数据库整数列精度；所有数值仍为最小单位整数。
    private static boolean nonnegative(BigInteger value) { return value != null && value.signum() >= 0 && value.toString().length() <= 70; }
    private static boolean positive(BigInteger value) { return nonnegative(value) && value.signum() > 0; }
    private static boolean validSignature(String value) { return value != null && !value.isBlank() && value.length() <= 256; }
    private static boolean terminal(Status status) { return status == Status.FINALIZED || status == Status.FAILED; }
    private static void fence(State state, long epoch) { require(epoch > 0 && state.epoch == epoch, "Stale PAPER worker epoch"); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }

    private static String digest(Request request, Quote quote) {
        try {
            StringBuilder canonical = new StringBuilder();
            for (Object value : List.of("PAPER-v1", request.requestId(), request.side(), request.amount(), quote.quoteId(),
                quote.side(), quote.input(), quote.expectedOutput(), quote.minOutput(), quote.maxNetworkFee(),
                quote.slippageBps(), quote.priceImpactBps(), quote.liquiditySol(), quote.expiresAt())) {
                String text = value.toString();
                canonical.append(text.length()).append(':').append(text);
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException("Required SHA-256 unavailable", failure); }
    }

    private static StoredOrder getOrder(Connection connection, String id) throws SQLException {
        StoredOrder stored = findOrder(connection, id);
        require(stored != null, "Unknown PAPER request");
        return stored;
    }

    private static StoredOrder findOrder(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = prepare(connection, "SELECT * FROM ce_orders WHERE request_id=?", id);
             ResultSet row = statement.executeQuery()) {
            if (!row.next()) return null;
            Side side = Side.valueOf(row.getString("side"));
            BigInteger input = integer(row, "input_amount");
            OrderView view = new OrderView(row.getString("request_id"), side, input, Status.valueOf(row.getString("status")),
                row.getString("signature"), row.getString("digest"), row.getString("attempt_id"),
                integer(row, "reserved_sol"), integer(row, "reserved_tokens"));
            Quote quote = new Quote(row.getString("quote_id"), side, input, integer(row, "expected_output"),
                integer(row, "min_output"), integer(row, "max_fee"), row.getInt("slippage_bps"), row.getInt("price_impact_bps"),
                integer(row, "liquidity_sol"), Instant.parse(row.getString("expires_at")));
            return new StoredOrder(view, quote);
        }
    }

    private static Posting posting(String account, Asset asset, BigInteger delta) { return new Posting(account, asset, delta); }

    private static void appendJournal(Connection connection, String orderId, List<Posting> proposed) throws SQLException {
        Map<Asset, BigInteger> sums = new EnumMap<>(Asset.class);
        // 账户使用固定 UUID；始终先取得唯一数据库互斥行的锁，再按确定的 UUID 顺序追加分录。
        Map<String, Posting> ordered = new TreeMap<>(Comparator.comparing(UUID::fromString));
        for (Posting posting : proposed) {
            sums.merge(posting.asset, posting.delta, BigInteger::add);
            Posting old = ordered.get(posting.account);
            require(old == null || old.asset == posting.asset, "Account asset mismatch");
            ordered.put(posting.account, old == null ? posting : posting(posting.account, posting.asset, old.delta.add(posting.delta)));
        }
        require(sums.values().stream().allMatch(ZERO::equals), "Unbalanced PAPER journal rejected");
        for (Posting posting : ordered.values()) {
            if (posting.delta.signum() != 0) execute(connection,
                "INSERT INTO ce_journal (order_id,account_id,asset,delta) VALUES (?,?,?,?)",
                orderId, posting.account, posting.asset, posting.delta);
        }
    }

    private <T> T transaction(Work<T> work) {
        require(!closed, "PAPER engine is closed");
        try (GateLease ignored = gate.enter();
             Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "")) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                // 锁行自身永不更新，资金状态另表读取，避免锁对象随余额版本变更。
                lockMutex(connection);
                State state;
                try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM ce_state WHERE id=1");
                     ResultSet row = statement.executeQuery()) {
                    require(row.next(), "PAPER state is missing");
                    state = new State(row);
                }
                T result = work.run(connection, state);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException | Error failure) {
                try { connection.rollback(); } catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            }
        } catch (SQLException failure) { throw new IllegalStateException("PAPER ledger transaction failed", failure); }
    }

    private static void saveState(Connection connection, State state) throws SQLException {
        require(state.sol.signum() >= 0 && state.tokens.signum() >= 0 && state.reservedSol.signum() >= 0
            && state.reservedTokens.signum() >= 0 && state.sol.compareTo(state.reservedSol) >= 0
            && state.tokens.compareTo(state.reservedTokens) >= 0, "Invalid PAPER balance state");
        require(state.grossSolDebits.signum() >= 0 && state.grossSolDebits.add(state.reservedSol).compareTo(MAX_GROSS_SOL_DEBITS) <= 0,
            "Invalid PAPER debit exposure");
        execute(connection, """
            UPDATE ce_state SET sol=?,reserved_sol=?,tokens=?,reserved_tokens=?,gross_sol_debits=?,kill_switch=?,epoch=? WHERE id=1
            """, state.sol, state.reservedSol, state.tokens, state.reservedTokens, state.grossSolDebits, state.killSwitch, state.epoch);
    }

    private static int count(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet rows = statement.executeQuery()) {
            rows.next(); return rows.getInt(1);
        }
    }

    private static BigInteger integer(ResultSet row, String column) throws SQLException { return row.getBigDecimal(column).toBigIntegerExact(); }

    private static PreparedStatement prepare(Connection connection, String sql, Object... parameters) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            for (int i = 0; i < parameters.length; i++) {
                Object parameter = parameters[i];
                if (parameter instanceof BigInteger amount) parameter = new BigDecimal(amount);
                else if (parameter instanceof Enum<?> || parameter instanceof Instant) parameter = parameter.toString();
                statement.setObject(i + 1, parameter);
            }
            return statement;
        } catch (SQLException | RuntimeException failure) {
            try { statement.close(); } catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    private static void execute(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, parameters)) { statement.executeUpdate(); }
    }

    private static void initialize(Connection connection) throws SQLException {
        for (String sql : SCHEMA) execute(connection, sql);
        connection.setAutoCommit(false);
        try {
            if (count(connection, "SELECT COUNT(*) FROM ce_mutex WHERE id=1") == 0) {
                execute(connection, "INSERT INTO ce_mutex (id) VALUES (1)");
            }
            connection.commit();
        } catch (SQLException failure) {
            connection.rollback();
            // 并发打开者由固定主键决定谁插入互斥行，不覆盖已存在的数据。
            if (!"23505".equals(failure.getSQLState())
                || count(connection, "SELECT COUNT(*) FROM ce_mutex WHERE id=1") != 1) throw failure;
            connection.commit();
        }
        try {
            lockMutex(connection);
            if (count(connection, "SELECT COUNT(*) FROM ce_state WHERE id=1") == 0) {
                execute(connection, "INSERT INTO ce_state (id,sol,reserved_sol,tokens,reserved_tokens,gross_sol_debits,kill_switch,epoch) VALUES (1,?,0,0,0,0,FALSE,0)", INITIAL_SOL);
                appendJournal(connection, "__seed__", List.of(posting(WALLET_SOL, Asset.SOL, INITIAL_SOL), posting(VENUE_SOL, Asset.SOL, INITIAL_SOL.negate())));
            }
            connection.commit();
        } catch (SQLException failure) {
            connection.rollback();
            throw failure;
        } finally { connection.setAutoCommit(true); }
    }

    private static void lockMutex(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM ce_mutex WHERE id=1 FOR UPDATE");
             ResultSet row = statement.executeQuery()) {
            require(row.next(), "PAPER database mutex is missing");
        }
    }

    /** 数据库层禁止更新或删除历史分录；更正应使用独立的冲正分录。 */
    public static final class RejectJournalMutation implements Trigger {
        @Override public void fire(Connection connection, Object[] oldRow, Object[] newRow) throws SQLException {
            throw new SQLException("PAPER journal is append-only; use a separate reverse journal", "45000");
        }
    }

    private record StoredOrder(OrderView view, Quote quote) { }
    private record DispatchDecision(OrderView view, Plan plan) { }
    private record Posting(String account, Asset asset, BigInteger delta) { }
    @FunctionalInterface private interface Work<T> { T run(Connection connection, State state) throws SQLException; }

    private static Gate[] createGates() {
        Gate[] gates = new Gate[64];
        for (int i = 0; i < gates.length; i++) gates[i] = new Gate();
        return gates;
    }

    /**
     * 固定大小的本机准入设施，只保存并发名额，不保存余额、订单或幂等事实。
     * 数据库互斥行、事务、唯一约束和纪元仍是权威保护；不同数据库哈希冲突只降低吞吐。
     * 每个条带最多容纳 32 个等待或执行中的请求，等待单写锁最多 5 秒。
     */
    private static final class Gate {
        private final Semaphore slots = new Semaphore(32, true);
        private final ReentrantLock writer = new ReentrantLock(true);

        GateLease enter() {
            require(slots.tryAcquire(), "PAPER local admission capacity exhausted");
            boolean acquired = false;
            try {
                acquired = writer.tryLock(5, TimeUnit.SECONDS);
                require(acquired, "PAPER local writer admission timed out");
                return new GateLease(this);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("PAPER local writer admission interrupted", interrupted);
            } finally {
                if (!acquired) slots.release();
            }
        }
    }

    private record GateLease(Gate gate) implements AutoCloseable {
        @Override public void close() {
            gate.writer.unlock();
            gate.slots.release();
        }
    }

    private static final class State {
        BigInteger sol, reservedSol, tokens, reservedTokens, grossSolDebits;
        boolean killSwitch;
        long epoch;
        State(ResultSet row) throws SQLException {
            sol = integer(row, "sol"); reservedSol = integer(row, "reserved_sol"); tokens = integer(row, "tokens");
            reservedTokens = integer(row, "reserved_tokens"); grossSolDebits = integer(row, "gross_sol_debits");
            killSwitch = row.getBoolean("kill_switch"); epoch = row.getLong("epoch");
        }
    }

    private static final List<String> SCHEMA = List.of("CREATE TABLE IF NOT EXISTS ce_mutex (id INTEGER PRIMARY KEY CHECK(id=1))", """
        CREATE TABLE IF NOT EXISTS ce_state (
          id INTEGER PRIMARY KEY CHECK(id=1), sol NUMERIC(78,0) NOT NULL CHECK(sol>=0),
          reserved_sol NUMERIC(78,0) NOT NULL CHECK(reserved_sol>=0 AND reserved_sol<=sol),
          tokens NUMERIC(78,0) NOT NULL CHECK(tokens>=0),
          reserved_tokens NUMERIC(78,0) NOT NULL CHECK(reserved_tokens>=0 AND reserved_tokens<=tokens),
          gross_sol_debits NUMERIC(78,0) NOT NULL CHECK(gross_sol_debits>=0),
          kill_switch BOOLEAN NOT NULL, epoch BIGINT NOT NULL CHECK(epoch>=0))
        """, """
        CREATE TABLE IF NOT EXISTS ce_orders (
          request_id VARCHAR(64) PRIMARY KEY, side VARCHAR(8) NOT NULL CHECK(side IN ('BUY','SELL')),
          input_amount NUMERIC(78,0) NOT NULL CHECK(input_amount>0), quote_id VARCHAR(256) NOT NULL,
          expected_output NUMERIC(78,0) NOT NULL, min_output NUMERIC(78,0) NOT NULL, max_fee NUMERIC(78,0) NOT NULL,
          slippage_bps INTEGER NOT NULL, price_impact_bps INTEGER NOT NULL, liquidity_sol NUMERIC(78,0) NOT NULL,
          expires_at VARCHAR(64) NOT NULL, status VARCHAR(24) NOT NULL
            CHECK(status IN ('RESERVED','DISPATCHING','SUBMITTED','UNKNOWN','CONFIRMING','FINALIZED','FAILED','REVIEW')),
          signature VARCHAR(256), digest VARCHAR(64) NOT NULL, attempt_id VARCHAR(36) NOT NULL UNIQUE,
          reserved_sol NUMERIC(78,0) NOT NULL CHECK(reserved_sol>=0),
          reserved_tokens NUMERIC(78,0) NOT NULL CHECK(reserved_tokens>=0))
        """, """
        CREATE TABLE IF NOT EXISTS ce_journal (
          sequence_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, order_id VARCHAR(64) NOT NULL,
          account_id VARCHAR(36) NOT NULL, asset VARCHAR(8) NOT NULL CHECK(asset IN ('SOL','FCLAB')),
          delta NUMERIC(78,0) NOT NULL CHECK(delta<>0))
        """, """
        CREATE TRIGGER IF NOT EXISTS ce_journal_immutable BEFORE UPDATE, DELETE ON ce_journal
          FOR EACH ROW CALL 'dev.fincore.chain.ExecutionEngine$RejectJournalMutation'
        """, """
        CREATE TABLE IF NOT EXISTS ce_dispatch_intents (
          attempt_id VARCHAR(36) PRIMARY KEY, request_id VARCHAR(64) NOT NULL UNIQUE REFERENCES ce_orders(request_id),
          digest VARCHAR(64) NOT NULL, worker_epoch BIGINT NOT NULL, created_at VARCHAR(64) NOT NULL,
          dispatch_state VARCHAR(24) NOT NULL)
        """, """
        CREATE TABLE IF NOT EXISTS ce_receipts (
          signature VARCHAR(256) PRIMARY KEY, request_id VARCHAR(64) NOT NULL UNIQUE REFERENCES ce_orders(request_id),
          attempt_id VARCHAR(36) NOT NULL UNIQUE, digest VARCHAR(64) NOT NULL,
          confirmation VARCHAR(32) NOT NULL, receipt_slot BIGINT NOT NULL)
        """, """
        CREATE TABLE IF NOT EXISTS ce_observations (
          observation_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
          request_id VARCHAR(64) NOT NULL REFERENCES ce_orders(request_id), received_at VARCHAR(64) NOT NULL,
          payload CHARACTER LARGE OBJECT NOT NULL)
        """);
}

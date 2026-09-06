// 单线程、单交易对的内存撮合实验；不提供 HTTP、账本、持久化或生产替换入口。
// price 单位为 1/10000 报价币，quantity 为整数最小交易单位。禁止浮点金额。
// 标准容器版本刻意保留真实分配成本：树节点、链表节点、订单、请求与成交结果均会分配。
#include <algorithm>
#include <charconv>
#include <chrono>
#include <cstdint>
#include <fstream>
#include <iostream>
#include <iterator>
#include <limits>
#include <list>
#include <map>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>
#include <unistd.h>

using Amount = std::int64_t;
static constexpr Amount MAX_AMOUNT = std::numeric_limits<Amount>::max();
static void require(bool condition, const char* message) { if (!condition) throw std::runtime_error(message); }
static bool identifier(const std::string& s) {
    if (s.empty() || s.size() > 40) return false;
    return std::all_of(s.begin(), s.end(), [](char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_'; });
}
static bool parseInteger(const std::string& s, Amount& value) {
    if (s.empty() || s[0] == '+') return false;
    const char* first = s.data(); const char* last = first + s.size();
    auto result = std::from_chars(first, last, value);
    return result.ec == std::errc{} && result.ptr == last;
}

struct Command {
    bool valid = false, badNumber = false;
    std::string fingerprint, kind, request = "?", id, owner, side;
    Amount price = 0, quantity = 0;
    // 解析和输入分配均在测量区间外，指纹规范化空白但不改变数值文本。
    static Command parse(const std::string& line) {
        std::istringstream in(line); std::vector<std::string> t;
        for (std::string word; in >> word;) t.push_back(std::move(word));
        Command c;
        if (!((t.size() == 7 && t[0] == "N") || (t.size() == 4 && t[0] == "C"))) return c;
        for (std::size_t i = 1; i <= 3; ++i) if (!identifier(t[i])) return c;
        c.valid = true; c.kind = t[0]; c.request = t[1]; c.id = t[2]; c.owner = t[3];
        for (const auto& field : t) { if (!c.fingerprint.empty()) c.fingerprint += ' '; c.fingerprint += field; }
        if (c.kind == "N") { c.side = t[4]; c.badNumber = !parseInteger(t[5], c.price) || !parseInteger(t[6], c.quantity); }
        return c;
    }
};

struct Order {
    std::string id, owner, side;
    Amount price, original, remaining, filled = 0, cancelled = 0, executed = 0;
    std::uint64_t sequence;
    std::list<Order*>::iterator position;
    Order(const Command& c, std::uint64_t seq) : id(c.id), owner(c.owner), side(c.side), price(c.price), original(c.quantity), remaining(c.quantity), sequence(seq) {}
};
struct Trade { std::string maker; Amount quantity, price; };
struct Result {
    std::string kind, request, id;
    Amount remaining = 0, cancelled = 0;
    std::vector<Trade> trades;
    std::string error;
    static Result reject(const std::string& request, const std::string& error) { return {"R", request, "", 0, 0, {}, error}; }
    std::string encode() const {
        if (kind == "R") return "R|" + request + "|" + error;
        if (kind == "C") return "C|" + request + "|" + id + "|" + std::to_string(cancelled);
        std::ostringstream out; out << "A|" << request << '|' << id << '|' << remaining << '|';
        for (std::size_t i = 0; i < trades.size(); ++i) { if (i) out << ','; const auto& t = trades[i]; out << t.maker << ':' << t.quantity << ':' << t.price; }
        if (trades.empty()) out << '-';
        out << '|' << cancelled; return out.str();
    }
};
struct Cached { std::string fingerprint; Result result; };

class Engine {
    // 与 Java 对应：有序价位 + FIFO 队列 + 订单哈希索引。撤单使用稳定链表迭代器。
    using Book = std::map<Amount, std::list<Order*>>;
    Book bids, asks;
    std::unordered_map<std::string, std::unique_ptr<Order>> orders;
    std::unordered_map<std::string, Cached> requests;
    std::uint64_t sequence = 0;
    Result transient;
    Book& book(const Order& o) { return o.side == "B" ? bids : asks; }
    void remove(Order& o) {
        Book& b = book(o); auto level = b.find(o.price);
        level->second.erase(o.position); if (level->second.empty()) b.erase(level);
    }
    Result cancel(const Command& c) {
        auto found = orders.find(c.id);
        if (found == orders.end()) return Result::reject(c.request, "NOT_FOUND");
        Order& o = *found->second;
        if (o.owner != c.owner) return Result::reject(c.request, "NOT_OWNER");
        if (o.remaining == 0) return Result::reject(c.request, "TERMINAL");
        Amount cancelled = o.remaining;
        remove(o); o.cancelled += cancelled; o.remaining = 0;
        return {"C", c.request, c.id, 0, cancelled, {}, ""};
    }
    Result place(const Command& c) {
        if (c.side != "B" && c.side != "S") return Result::reject(c.request, "BAD_SIDE");
        if (c.badNumber) return Result::reject(c.request, "BAD_NUMBER");
        if (c.price <= 0 || c.quantity <= 0) return Result::reject(c.request, "NON_POSITIVE");
        // 不能先乘再检测：C++ 有符号整数溢出是未定义行为。先除法比较，再进行任何状态修改。
        if (c.price > MAX_AMOUNT / c.quantity) return Result::reject(c.request, "OVERFLOW");
        if (orders.contains(c.id)) return Result::reject(c.request, "ORDER_EXISTS");
        if (executionWouldOverflow(c)) return Result::reject(c.request, "OVERFLOW");
        auto holder = std::make_unique<Order>(c, sequence++); Order& taker = *holder;
        Book& opposite = c.side == "B" ? asks : bids;
        std::vector<Trade> trades;
        while (taker.remaining > 0 && !opposite.empty()) {
            auto level = c.side == "B" ? opposite.begin() : std::prev(opposite.end());
            Amount price = level->first;
            if (c.side == "B" ? price > c.price : price < c.price) break;
            Order& maker = *level->second.front();
            // CANCEL_TAKER：保留本命令此前有效成交，取消剩余量，自有 maker 保持不变。
            if (maker.owner == c.owner) { taker.cancelled = taker.remaining; taker.remaining = 0; break; }
            Amount quantity = std::min(taker.remaining, maker.remaining);
            maker.remaining -= quantity; maker.filled += quantity;
            taker.remaining -= quantity; taker.filled += quantity;
            Amount notional = quantity * maker.price;
            maker.executed += notional; taker.executed += notional;
            trades.push_back({maker.id, quantity, maker.price});
            if (maker.remaining == 0) remove(maker);
        }
        orders.emplace(taker.id, std::move(holder));
        if (taker.remaining > 0) { auto& queue = book(taker)[taker.price]; queue.push_back(&taker); taker.position = std::prev(queue.end()); }
        return {"A", c.request, c.id, taker.remaining, taker.cancelled, std::move(trades), ""};
    }
    // 卖出价改善可能让累计成交额超过限价*数量。预检实际路径并为残量预留限价成交额。
    // 这次只读遍历先于任何状态修改；价位/订单扫描两次的 CPU 成本被双方计时包含。
    bool executionWouldOverflow(const Command& c) const {
        Amount left = c.quantity, total = 0; bool overflow = false;
        auto visit = [&](const auto& level) {
            if (c.side == "B" ? level.first > c.price : level.first < c.price) return false;
            for (const Order* maker : level.second) {
                if (maker->owner == c.owner) { left = 0; return false; }
                Amount quantity = std::min(left, maker->remaining), notional = quantity * maker->price;
                if (total > MAX_AMOUNT - notional) { overflow = true; return false; }
                total += notional; left -= quantity; if (left == 0) return false;
            }
            return true;
        };
        if (c.side == "B") { for (const auto& level : asks) if (!visit(level)) break; }
        else { for (auto it = bids.rbegin(); it != bids.rend(); ++it) if (!visit(*it)) break; }
        return overflow || total > MAX_AMOUNT - left * c.price;
    }
    void checkBook(const Book& b, const std::string& side, std::unordered_set<const Order*>& seen) const {
        for (const auto& [price, queue] : b) {
            require(!queue.empty(), "empty level"); std::uint64_t previous = 0; bool first = true;
            for (auto iterator = queue.begin(); iterator != queue.end(); ++iterator) {
                const Order& o = **iterator;
                require(o.price == price && o.side == side && o.remaining > 0, "bad level");
                require((first || o.sequence > previous) && seen.insert(&o).second, "FIFO/duplicate index");
                require(orders.at(o.id).get() == &o && o.position == iterator, "order index/iterator");
                previous = o.sequence; first = false;
            }
        }
    }
public:
    const Result& apply(const Command& c) {
        if (!c.valid) { transient = Result::reject("?", "BAD_PROTOCOL"); return transient; }
        auto previous = requests.find(c.request);
        if (previous != requests.end()) {
            if (previous->second.fingerprint == c.fingerprint) return previous->second.result;
            transient = Result::reject(c.request, "DUPLICATE_KEY"); return transient;
        }
        Result result = c.kind == "C" ? cancel(c) : place(c);
        return requests.emplace(c.request, Cached{c.fingerprint, std::move(result)}).first->second.result;
    }
    void verify() const {
        std::unordered_set<const Order*> seen; checkBook(bids, "B", seen); checkBook(asks, "S", seen);
        for (const auto& [id, holder] : orders) {
            const Order& o = *holder;
            require(o.remaining >= 0 && o.filled >= 0 && o.cancelled >= 0, "negative quantity");
            require(o.original - o.remaining - o.filled == o.cancelled, "quantity conservation");
            require(o.executed >= 0 && o.executed <= MAX_AMOUNT - o.remaining * o.price, "execution notional overflow");
            require((o.remaining > 0) == seen.contains(&o), "order/book disagreement");
        }
        require(bids.empty() || asks.empty() || bids.rbegin()->first < asks.begin()->first, "crossed book");
    }
    std::string snapshot() const {
        std::vector<const Order*> sorted; for (const auto& [id, holder] : orders) sorted.push_back(holder.get());
        std::sort(sorted.begin(), sorted.end(), [](const Order* a, const Order* b) { return a->id < b->id; });
        std::ostringstream out; bool first = true;
        for (const Order* o : sorted) { if (!first) out << ';'; first = false; out << o->id << ':' << o->owner << ':' << o->side << ':' << o->price << ':' << o->original << ':' << o->remaining << ':' << o->filled << ':' << o->cancelled << ':' << o->executed; }
        return out.str();
    }
};

static std::string hash(const std::string& state) {
    // 状态散列使用有意定义的无符号模运算，不参与金额运算。
    std::uint64_t value = 14695981039346656037ULL;
    for (unsigned char c : state) value = (value ^ c) * 1099511628211ULL;
    return std::to_string(value);
}
static std::int64_t percentile(const std::vector<std::int64_t>& sorted, std::size_t numerator, std::size_t denominator) {
    return sorted.at((sorted.size() * numerator + denominator - 1) / denominator - 1);
}
static volatile std::size_t observable = 0;

static int execute(int argc, char** argv) {
    if (argc == 2 && (std::string(argv[1]) == "--replay" || std::string(argv[1]) == "--replay-batch")) {
        Engine engine;
        for (std::string line; std::getline(std::cin, line);) { std::cout << engine.apply(Command::parse(line)).encode() << '\n'; if (std::string(argv[1]) == "--replay") engine.verify(); }
        engine.verify(); std::cout << "STATE|" << engine.snapshot() << '\n'; return 0;
    }
    require(argc == 4 && std::string(argv[1]) == "--bench", "--replay | --bench FILE WARMUP");
    std::ifstream input(argv[2]); require(input.good(), "cannot open workload");
    std::vector<Command> commands;
    for (std::string line; std::getline(input, line);) commands.push_back(Command::parse(line));
    int warmup = std::stoi(argv[3]); require(!commands.empty() && warmup > 0, "empty workload or missing warmup");
    for (int pass = 0; pass < warmup; ++pass) { Engine warm; for (const auto& c : commands) warm.apply(c); warm.verify(); observable = observable ^ warm.snapshot().size(); }
    Engine engine; std::vector<std::int64_t> samples(commands.size());
    using Clock = std::chrono::steady_clock;
    auto started = Clock::now();
    for (std::size_t i = 0; i < commands.size(); ++i) {
        auto before = Clock::now(); engine.apply(commands[i]);
        samples[i] = std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now() - before).count();
    }
    auto elapsed = std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now() - started).count();
    engine.verify(); std::string stateHash = hash(engine.snapshot()); observable = observable ^ stateHash.size();
    std::string samplePath = std::string(argv[2]) + ".cpp.samples." + std::to_string(getpid()) + ".csv";
    { std::ofstream output(samplePath); require(output.good(), "cannot write samples"); output << "latency_ns\n"; for (auto sample : samples) output << sample << '\n'; require(output.good(), "sample write failure"); }
    std::sort(samples.begin(), samples.end());
    auto filename = samplePath.substr(samplePath.find_last_of("/\\") + 1);
    // 唯一浮点表达式用于吞吐率（时间统计），不用于任何价格、数量或金额。
    std::cout << "{\"samples\":" << samples.size() << ",\"elapsedNs\":" << elapsed << ",\"throughputOpsSec\":" << (samples.size() * 1e9 / static_cast<double>(elapsed))
              << ",\"p50Ns\":" << percentile(samples, 1, 2) << ",\"p99Ns\":" << percentile(samples, 99, 100) << ",\"p999Ns\":" << percentile(samples, 999, 1000)
              << ",\"maxNs\":" << samples.back() << ",\"allocatedBytesMeasuredThread\":null,\"stateHash\":\"" << stateHash << "\",\"sampleFile\":\"" << filename << "\"}\n";
    return 0;
}
int main(int argc, char** argv) {
    // 内存分配失败等异常终止进程；不能把部分执行伪装成成功，也没有持久化恢复保证。
    try { return execute(argc, argv); }
    catch (const std::exception& error) { std::cerr << "matching-lab failure: " << error.what() << '\n'; return 2; }
}

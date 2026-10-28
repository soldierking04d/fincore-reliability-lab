#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>

// 纯本地所有权示例：unsafe 分支用于让 ASan 检测失效引用，不接收请求或执行外部代码。
struct RequestState {
    int tenant;
    explicit RequestState(bool fail = false) : tenant(42) {
        if (fail) throw std::runtime_error("受控构造失败");
    }
};

int main(int argc, char** argv) {
    if (argc == 2 && std::string(argv[1]) == "unsafe") {
        auto owner = std::make_unique<RequestState>();
        RequestState* worker_cache = owner.get();
        owner.reset();
        // 原所有者已释放，但 Worker 缓存仍保存裸指针；ASan 应在这里报告释放后使用。
        volatile int observed = worker_cache->tenant;
        return observed == 42 ? 0 : 1;
    }
    std::weak_ptr<RequestState> worker_cache;
    try {
        // 先完成构造才发布引用；构造抛错时不会公开半成品。
        auto staged = std::make_shared<RequestState>(true);
        worker_cache = staged;
        throw std::logic_error("失败注入没有触发");
    } catch (const std::runtime_error&) {
        if (!worker_cache.expired()) return 2;
    }
    {
        auto request = std::make_shared<RequestState>();
        worker_cache = request;
        const auto live = worker_cache.lock();
        if (!live || live->tenant != 42) return 3;
    }
    // 另一个请求必须先提升弱引用；旧请求结束后不能再次读取其内存。
    if (worker_cache.lock()) return 4;
    std::cout << "{\"safeChecks\":3,\"passed\":true,\"expiredReferenceRejected\":true}\n";
    return 0;
}

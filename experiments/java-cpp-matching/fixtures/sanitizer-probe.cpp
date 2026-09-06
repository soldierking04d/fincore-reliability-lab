// 仅用于证明验证工具会阻止已知错误；此程序绝不链接进撮合或性能二进制。
// 两个分支预期均失败退出，harness 同时核验退出码与对应诊断文本。
#include <cstdint>
#include <limits>
#include <memory>
#include <string>

int main(int argc, char** argv) {
    if (argc > 1 && std::string(argv[1]) == "undefined") {
        volatile std::int64_t maximum = std::numeric_limits<std::int64_t>::max();
        return static_cast<int>(maximum + argc);
    }
    auto allocation = std::make_unique<int[]>(1);
    allocation[0] = 7;
    return allocation[argc];
}

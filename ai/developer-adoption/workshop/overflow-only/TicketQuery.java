/** 可复现实作样例：非线上缺陷报告，不访问真实工单或生产系统。 */
public final class TicketQuery {
    private TicketQuery() {}

    /** 方法签名和返回字段在修复前后保持一致；页号从零开始。 */
    public record PageRequest(int page, int size, long offset, String query) {}

    public static PageRequest prepare(int page, int size, String query) {
        long offset = (long) page * size;
        String normalized = query == null ? "" : query.trim();
        return new PageRequest(page, size, offset, normalized);
    }
}

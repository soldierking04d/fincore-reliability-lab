/** 可复现实作样例：非线上缺陷报告，不访问真实工单或生产系统。 */
public final class TicketQuery {
    private TicketQuery() {}

    /** 方法签名和返回字段在修复前后保持一致；页号从零开始。 */
    public record PageRequest(int page, int size, long offset, String query) {}

    public static PageRequest prepare(int page, int size, String query) {
        if (page < 0) {
            throw new IllegalArgumentException("page 必须 >= 0");
        }
        if (size < 1 || size > 200) {
            throw new IllegalArgumentException("size 必须在 1..200 之间");
        }
        long offset = (long) page * size;
        return new PageRequest(page, size, offset, normalizeQuery(query));
    }

    /** 仅去除首尾空白；内部字符原样保留，零宽空格不属于本合同的空白。 */
    private static String normalizeQuery(String query) {
        if (query == null) {
            return "";
        }
        int start = 0;
        int end = query.length();
        while (start < end && isBlank(query.codePointAt(start))) {
            start += Character.charCount(query.codePointAt(start));
        }
        while (end > start && isBlank(query.codePointBefore(end))) {
            end -= Character.charCount(query.codePointBefore(end));
        }
        return query.substring(start, end);
    }

    private static boolean isBlank(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
}

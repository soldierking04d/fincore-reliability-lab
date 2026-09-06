import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** 只调用被测实现并输出实际值；预期数据由独立的用例文件提供。 */
public final class QueryProbe {
    private QueryProbe() {}

    public static void main(String[] args) {
        if (args.length != 3) {
            throw new IllegalArgumentException("探针需要页号、页大小和查询三个参数");
        }
        int page = Integer.parseInt(args[0]);
        int size = Integer.parseInt(args[1]);
        String query = args[2].equals("@null") ? null
            : new String(Base64.getDecoder().decode(args[2]), StandardCharsets.UTF_8);
        try {
            TicketQuery.PageRequest result = TicketQuery.prepare(page, size, query);
            System.out.println("{\"kind\":\"ok\",\"page\":" + result.page()
                + ",\"size\":" + result.size() + ",\"offset\":\"" + result.offset()
                + "\",\"queryBase64\":\"" + encode(result.query()) + "\"}");
        } catch (IllegalArgumentException error) {
            System.out.println("{\"kind\":\"error\",\"type\":\"IllegalArgumentException\","
                + "\"messageBase64\":\"" + encode(error.getMessage()) + "\"}");
        }
    }

    private static String encode(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}

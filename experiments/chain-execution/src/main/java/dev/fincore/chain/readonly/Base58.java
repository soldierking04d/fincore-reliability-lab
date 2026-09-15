package dev.fincore.chain.readonly;

import java.math.BigInteger;
import java.util.Arrays;

/** 仅用于公开交易字节的 Base58 编解码，不生成或加载密钥材料。 */
public final class Base58 {
    private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final BigInteger RADIX = BigInteger.valueOf(58);

    private Base58() { }

    public static byte[] decode(String text) {
        require(text != null, "Base58 text is required");
        if (text.isEmpty()) return new byte[0];
        int leadingZeroes = 0;
        while (leadingZeroes < text.length() && text.charAt(leadingZeroes) == '1') leadingZeroes++;
        BigInteger value = BigInteger.ZERO;
        for (int i = 0; i < text.length(); i++) {
            int digit = ALPHABET.indexOf(text.charAt(i));
            require(digit >= 0, "Invalid Base58 character");
            value = value.multiply(RADIX).add(BigInteger.valueOf(digit));
        }
        byte[] magnitude = value.signum() == 0 ? new byte[0] : value.toByteArray();
        if (magnitude.length > 0 && magnitude[0] == 0) magnitude = Arrays.copyOfRange(magnitude, 1, magnitude.length);
        byte[] result = new byte[Math.addExact(leadingZeroes, magnitude.length)];
        System.arraycopy(magnitude, 0, result, leadingZeroes, magnitude.length);
        return result;
    }

    public static String encode(byte[] bytes) {
        require(bytes != null, "Public bytes are required");
        if (bytes.length == 0) return "";
        int leadingZeroes = 0;
        while (leadingZeroes < bytes.length && bytes[leadingZeroes] == 0) leadingZeroes++;
        BigInteger value = new BigInteger(1, bytes);
        StringBuilder reversed = new StringBuilder();
        while (value.signum() != 0) {
            BigInteger[] quotientAndRemainder = value.divideAndRemainder(RADIX);
            reversed.append(ALPHABET.charAt(quotientAndRemainder[1].intValueExact()));
            value = quotientAndRemainder[0];
        }
        reversed.append("1".repeat(leadingZeroes));
        return reversed.reverse().toString();
    }

    /** 校验恰好 32 个公开字节的规范编码；不证明地址控制权，也不检查其是否位于椭圆曲线上。 */
    public static String address(String text) {
        require(text != null && text.length() >= 32 && text.length() <= 44,
            "A canonical 32-byte Base58 address or blockhash is required");
        byte[] decoded = decode(text);
        require(decoded.length == 32 && encode(decoded).equals(text), "Base58 value must contain exactly 32 bytes");
        return text;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}

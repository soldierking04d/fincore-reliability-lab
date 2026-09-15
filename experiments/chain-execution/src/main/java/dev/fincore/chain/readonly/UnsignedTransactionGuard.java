package dev.fincore.chain.readonly;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 严格检查未签名的 legacy 交易字节，再与独立批准合同逐项精确比较。
 * 本类不签名、不广播、不加载秘密、不查询账户状态，也不解释兑换指令的业务语义。
 * 仅凭指令的程序地址，不能批准执行其尚未解释的数据。
 *
 * 交易字节格式已于 2026-09-14 核验，依据为
 * https://solana.com/docs/core/transactions/transaction-structure、
 * https://solana.com/docs/core/transactions/versioned-transactions，以及官方 SDK
 * anza-xyz/solana-sdk 中的 message/src/legacy.rs 和 short-vec/src/lib.rs。
 */
public final class UnsignedTransactionGuard {
    private static final int MAX_WIRE_BYTES = 1_232;
    private static final String SYSTEM_PROGRAM = "11111111111111111111111111111111";
    private static final String MEMO_PROGRAM = "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr";

    private UnsignedTransactionGuard() { }

    /** 消息头请求的账户权限；运行时仍可能进一步降低保留账户或程序账户的权限。 */
    public record Account(String address, boolean signer, boolean writable) {
        public Account { address = Base58.address(address); }
    }

    public record Instruction(String program, List<String> accounts, String dataBase64) {
        public Instruction {
            program = Base58.address(program);
            require(accounts != null, "Instruction accounts are required");
            accounts = accounts.stream().map(Base58::address).toList();
            require(dataBase64 != null, "Instruction data is required");
            byte[] decoded = Base64.getDecoder().decode(dataBase64);
            require(Base64.getEncoder().encodeToString(decoded).equals(dataBase64),
                "Instruction data must use canonical Base64");
        }
    }

    public record Inspection(String feePayer, String blockhash, List<Account> accounts,
                             List<Instruction> instructions, String messageSha256, String wireSha256) {
        public Inspection {
            accounts = List.copyOf(accounts);
            instructions = List.copyOf(instructions);
        }
    }

    /**
     * 解析结构，并完整保留账户顺序、权限和指令字节。
     * 本层明确排除持久 nonce，但不会据此批准其他尚未解释的程序数据。
     * 调用方仍须另行校验数据新鲜度，以及链上程序和账户状态。
     */
    public static Inspection inspect(byte[] wire) {
        require(wire != null && wire.length > 0 && wire.length <= MAX_WIRE_BYTES,
            "Unsigned legacy transaction must contain 1 to 1232 bytes");
        // 检查、比较和摘要均使用同一份快照，不受调用方后续修改原数组的影响。
        byte[] snapshot = wire.clone();
        Cursor cursor = new Cursor(snapshot);
        int signatureCount = cursor.compactLength();
        require(signatureCount == 1, "Exactly one unsigned fee-payer signature slot is supported");
        for (byte value : cursor.bytes(64)) require(value == 0, "Nonzero signatures are not accepted");
        int messageOffset = cursor.position;
        int requiredSignatures = cursor.unsignedByte();
        require((requiredSignatures & 128) == 0, "Versioned transactions and address lookup tables are unsupported");
        require(requiredSignatures == signatureCount, "Signature count does not match the single fee payer");
        int readonlySigned = cursor.unsignedByte();
        int readonlyUnsigned = cursor.unsignedByte();
        int accountCount = cursor.compactLength();
        require(accountCount > 0 && accountCount <= 256, "Invalid legacy account count");
        require(readonlySigned == 0, "The sole fee payer must be writable");
        require(readonlyUnsigned <= accountCount - requiredSignatures, "Account header partitions overlap");
        require(accountCount <= cursor.remaining() / 32, "Truncated account keys");

        List<Account> accounts = new ArrayList<>(accountCount);
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < accountCount; index++) {
            String address = Base58.encode(cursor.bytes(32));
            require(seen.add(address), "Duplicate account keys are not accepted");
            boolean signer = index < requiredSignatures;
            boolean writable = signer ? index < requiredSignatures - readonlySigned
                : index < accountCount - readonlyUnsigned;
            accounts.add(new Account(address, signer, writable));
        }
        String blockhash = Base58.encode(cursor.bytes(32));
        int instructionCount = cursor.compactLength();
        // 即使编译后指令不含账户和数据，也至少需要一个程序索引和两个紧凑长度字段。
        require(instructionCount <= cursor.remaining() / 3, "Truncated instruction array");
        List<Instruction> instructions = new ArrayList<>(instructionCount);
        for (int index = 0; index < instructionCount; index++) {
            int programIndex = cursor.unsignedByte();
            require(programIndex > 0 && programIndex < accountCount, "Invalid program account index");
            int referencedCount = cursor.compactLength();
            require(referencedCount <= cursor.remaining(), "Truncated instruction account array");
            List<String> references = new ArrayList<>(referencedCount);
            for (int account = 0; account < referencedCount; account++) {
                int accountIndex = cursor.unsignedByte();
                require(accountIndex < accountCount, "Instruction account index is out of bounds");
                references.add(accounts.get(accountIndex).address());
            }
            byte[] data = cursor.bytes(cursor.compactLength());
            String program = accounts.get(programIndex).address();
            // AdvanceNonceAccount 对应 SystemInstruction 判别值 4，以小端 u32 编码。
            // 持久 nonce 不属于近期 blockhash，不能通过本校验器的合同准入。
            require(!(SYSTEM_PROGRAM.equals(program) && data.length >= 4
                    && data[0] == 4 && data[1] == 0 && data[2] == 0 && data[3] == 0),
                "Durable nonce transactions are unsupported");
            instructions.add(new Instruction(program, references, Base64.getEncoder().encodeToString(data)));
        }
        require(cursor.remaining() == 0, "Trailing bytes or unsupported address lookup data");
        return new Inspection(accounts.getFirst().address(), blockhash, accounts, instructions,
            sha256(Arrays.copyOfRange(snapshot, messageOffset, snapshot.length)), sha256(snapshot));
    }

    /**
     * 按独立批准合同，逐项匹配账户顺序、权限、程序、指令账户和全部数据字节。
     * 预期值必须来自受信业务规则，禁止把 inspect(wire) 的结果回填成本方法的批准合同。
     * 匹配成功不等于已批准程序业务语义。
     * 返回的 SHA-256 绑定此次完整的未签名交易字节，不是交易签名。
     */
    public static String verify(byte[] wire, String expectedBlockhash, List<Account> expectedAccounts,
                                List<Instruction> expectedInstructions) {
        String blockhash = Base58.address(expectedBlockhash);
        require(expectedAccounts != null && expectedInstructions != null, "An explicit approval contract is required");
        require(expectedAccounts.stream().allMatch(account -> account != null)
            && expectedInstructions.stream().allMatch(instruction -> instruction != null), "Approval contract contains null entries");
        List<Account> approvedAccounts = List.copyOf(expectedAccounts);
        List<Instruction> approvedInstructions = List.copyOf(expectedInstructions);
        Inspection inspected = inspect(wire);
        require(inspected.blockhash().equals(blockhash), "Blockhash differs from the approved contract");
        require(inspected.accounts().equals(approvedAccounts), "Ordered accounts or privileges differ from the approved contract");
        require(inspected.instructions().equals(approvedInstructions), "Ordered instructions differ from the approved contract");
        return inspected.wireSha256();
    }

    /**
     * 构造一条未签名的 Memo 指令，不携带指令账户、转账或优先费用设置。
     * 仅用于结构检查或 RPC 模拟探针；签名槽始终为零，本方法不具备签名或提交能力。
     * Memo 语义依据：https://www.solana-program.com/docs/memo。
     */
    public static byte[] memoProbe(String feePayer, String blockhash, String memo) {
        String payer = Base58.address(feePayer);
        String recentBlockhash = Base58.address(blockhash);
        require(!payer.equals(MEMO_PROGRAM), "Fee payer and Memo program must be different accounts");
        require(memo != null && memo.length() <= 96, "Probe memo must contain at most 96 ASCII bytes");
        for (int index = 0; index < memo.length(); index++) {
            require(memo.charAt(index) <= 127, "Probe memo must be ASCII");
        }
        byte[] data = memo.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(1);
        out.writeBytes(new byte[64]);
        out.write(1); out.write(0); out.write(1); // 付款者可写且需签名，Memo 程序只读且无需签名。
        out.write(2);
        out.writeBytes(Base58.decode(payer));
        out.writeBytes(Base58.decode(MEMO_PROGRAM));
        out.writeBytes(Base58.decode(recentBlockhash));
        out.write(1); // 恰好一条指令。
        out.write(1); // Memo 程序的账户索引。
        out.write(0); // 不携带指令账户。
        out.write(data.length); // 长度不超过 96，因此规范 compact-u16 编码恰好占一个字节。
        out.writeBytes(data);
        byte[] wire = out.toByteArray();
        verify(wire, recentBlockhash, List.of(new Account(payer, true, true), new Account(MEMO_PROGRAM, false, false)),
            List.of(new Instruction(MEMO_PROGRAM, List.of(), Base64.getEncoder().encodeToString(data))));
        return wire;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("Required SHA-256 is unavailable", unavailable);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static final class Cursor {
        private final byte[] bytes;
        private int position;

        private Cursor(byte[] bytes) { this.bytes = bytes; }
        private int remaining() { return bytes.length - position; }
        private int unsignedByte() {
            require(remaining() >= 1, "Truncated legacy transaction");
            return bytes[position++] & 255;
        }
        private byte[] bytes(int length) {
            require(length >= 0 && length <= remaining(), "Truncated legacy transaction payload");
            byte[] result = Arrays.copyOfRange(bytes, position, position + length);
            position += length;
            return result;
        }
        private int compactLength() {
            int value = 0;
            for (int index = 0; index < 3; index++) {
                int next = unsignedByte();
                require(index == 0 || next != 0, "Noncanonical compact-u16 alias");
                require(index != 2 || (next & 252) == 0, "Compact-u16 overflow or continued third byte");
                value |= (next & 127) << (7 * index);
                if ((next & 128) == 0) return value;
            }
            throw new IllegalArgumentException("Compact-u16 exceeds three bytes");
        }
    }
}

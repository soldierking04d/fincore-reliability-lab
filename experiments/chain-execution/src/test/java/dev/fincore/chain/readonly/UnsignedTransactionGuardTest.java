package dev.fincore.chain.readonly;

import static dev.fincore.chain.readonly.UnsignedTransactionGuard.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 手工组装公开字节夹具，不使用钱包、不生成签名、不调用 RPC，也不广播。 */
class UnsignedTransactionGuardTest {
    private static final String SYSTEM = "11111111111111111111111111111111";
    private static final String MEMO = "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr";
    private static final byte[] PAYER_BYTES = repeated(17);
    private static final byte[] RECIPIENT_BYTES = repeated(34);
    private static final byte[] BLOCKHASH_BYTES = repeated(51);
    // legacy 夹具的固定偏移：1 字节签名数量、64 字节全零签名槽、3 字节消息头。
    private static final int MESSAGE = 65;
    private static final int KEY_COUNT = 68;
    private static final int KEYS = 69;
    private static final int BLOCKHASH = 165;
    private static final int INSTRUCTION_COUNT = 197;
    private static final int PROGRAM_INDEX = 198;
    private static final int INSTRUCTION_ACCOUNT_COUNT = 199;
    private static final int INSTRUCTION_ACCOUNTS = 200;
    private static final int DATA_LENGTH = 202;
    private static final int DATA = 203;

    @Test void inspectsUnsignedLegacyTransferAndVerifiesExactIndependentContract() throws Exception {
        byte[] wire = transfer(5_000);
        var inspected = inspect(wire);
        assertEquals(address(PAYER_BYTES), inspected.feePayer());
        assertEquals(address(BLOCKHASH_BYTES), inspected.blockhash());
        assertEquals(approvedAccounts(), inspected.accounts());
        assertEquals(approvedInstructions(), inspected.instructions());
        assertEquals(sha(Arrays.copyOfRange(wire, MESSAGE, wire.length)), inspected.messageSha256());
        assertEquals(sha(wire), inspected.wireSha256());
        assertEquals(sha(wire), verify(wire, address(BLOCKHASH_BYTES), approvedAccounts(), approvedInstructions()));
    }

    @Test void changingAmountInsideApprovedProgramIsRejected() {
        assertRejectedContract(transfer(5_001));
        byte[] changedDiscriminator = transfer(5_000);
        changedDiscriminator[DATA] = 9;
        assertRejectedContract(changedDiscriminator);
    }

    @Test void additionalInstructionAndChangedInstructionAccountOrderAreRejected() {
        byte[] original = transfer(5_000);
        byte[] extra = replace(original, original.length, 0,
            Arrays.copyOfRange(original, PROGRAM_INDEX, original.length));
        extra[INSTRUCTION_COUNT] = 2;
        assertEquals(2, inspect(extra).instructions().size());
        assertRejectedContract(extra);
        byte[] reordered = original.clone();
        reordered[INSTRUCTION_ACCOUNTS] = 1;
        reordered[INSTRUCTION_ACCOUNTS + 1] = 0;
        assertRejectedContract(reordered);
    }

    @Test void changingBlockhashRecipientFeePayerOrPermissionsIsRejected() {
        for (int position : new int[] {BLOCKHASH, KEYS + 32, KEYS}) {
            byte[] changed = transfer(5_000);
            changed[position] ^= 1;
            assertRejectedContract(changed);
        }
        byte[] permissionEscalation = transfer(5_000);
        permissionEscalation[MESSAGE + 2] = 0;
        assertTrue(inspect(permissionEscalation).accounts().getLast().writable());
        assertRejectedContract(permissionEscalation);
    }

    @Test void programWhitelistCannotApproveUnknownInstructionOrProgram() {
        byte[] unknown = transfer(5_000);
        System.arraycopy(repeated(68), 0, unknown, KEYS + 64, 32);
        assertEquals(address(repeated(68)), inspect(unknown).instructions().getFirst().program());
        assertRejectedContract(unknown);
        var onlyProgram = List.of(new Instruction(SYSTEM, List.of(), ""));
        assertThrows(IllegalArgumentException.class,
            () -> verify(transfer(5_000), address(BLOCKHASH_BYTES), approvedAccounts(), onlyProgram));
    }

    @Test void durableNonceCannotBeApprovedEvenByCopyingItsBytesIntoExpectedContract() {
        byte[] nonce = transfer(5_000);
        nonce[DATA] = 4; // SystemInstruction::AdvanceNonceAccount 的判别值，以小端 u32 编码。
        assertThrows(IllegalArgumentException.class, () -> inspect(nonce));
        var expected = List.of(new Instruction(SYSTEM, List.of(address(PAYER_BYTES), address(RECIPIENT_BYTES)),
            Base64.getEncoder().encodeToString(Arrays.copyOfRange(nonce, DATA, nonce.length))));
        assertThrows(IllegalArgumentException.class,
            () -> verify(nonce, address(BLOCKHASH_BYTES), approvedAccounts(), expected));

        String recentBlockhashes = "SysvarRecentB1ockHashes11111111111111111111";
        byte[] validNonce = fixture(List.of(PAYER_BYTES, RECIPIENT_BYTES, Base58.decode(recentBlockhashes), new byte[32]),
            3, new byte[] {1, 2, 0}, new byte[] {4, 0, 0, 0});
        validNonce[MESSAGE + 2] = 2;
        var nonceAccounts = List.of(new Account(address(PAYER_BYTES), true, true),
            new Account(address(RECIPIENT_BYTES), false, true), new Account(recentBlockhashes, false, false),
            new Account(SYSTEM, false, false));
        var nonceInstruction = List.of(new Instruction(SYSTEM,
            List.of(address(RECIPIENT_BYTES), recentBlockhashes, address(PAYER_BYTES)), "BAAAAA=="));
        assertThrows(IllegalArgumentException.class, () -> inspect(validNonce));
        assertThrows(IllegalArgumentException.class,
            () -> verify(validNonce, address(BLOCKHASH_BYTES), nonceAccounts, nonceInstruction));
    }

    @Test void signaturesMustBeExactlyOneAllZeroSlotAndMatchHeader() {
        byte[] signature = transfer(5_000);
        signature[64] = 1;
        assertThrows(IllegalArgumentException.class, () -> inspect(signature));
        for (int count : new int[] {0, 2, 127}) {
            byte[] badCount = transfer(5_000);
            badCount[0] = (byte) count;
            assertThrows(IllegalArgumentException.class, () -> inspect(badCount));
            byte[] mismatched = transfer(5_000);
            mismatched[MESSAGE] = (byte) count;
            assertThrows(IllegalArgumentException.class, () -> inspect(mismatched));
        }
        byte[] twoSigners = replace(transfer(5_000), MESSAGE, 0, new byte[64]);
        twoSigners[0] = 2;
        twoSigners[MESSAGE + 64] = 2;
        assertThrows(IllegalArgumentException.class, () -> inspect(twoSigners));
    }

    @Test void everyVersionPrefixIsRejectedIncludingV0AddressLookupTables() {
        for (int version : new int[] {128, 129, 255}) {
            byte[] versioned = replace(transfer(5_000), MESSAGE, 0, new byte[] {(byte) version});
            byte[] withLookupArray = Arrays.copyOf(versioned, versioned.length + 1);
            assertThrows(IllegalArgumentException.class, () -> inspect(withLookupArray));
        }
    }

    @Test void everyTruncatedPrefixTrailingBytesAndOversizedWireAreRejected() {
        byte[] wire = transfer(5_000);
        for (int length = 0; length < wire.length; length++) {
            byte[] truncated = Arrays.copyOf(wire, length);
            assertThrows(IllegalArgumentException.class, () -> inspect(truncated), "truncated at " + length);
        }
        assertThrows(IllegalArgumentException.class, () -> inspect(Arrays.copyOf(wire, wire.length + 1)));
        assertThrows(IllegalArgumentException.class, () -> inspect(new byte[1_233]));
        assertThrows(IllegalArgumentException.class, () -> inspect(null));
    }

    @Test void accountAndProgramIndexesAndHeaderPartitionsAreBoundsChecked() {
        for (int position : new int[] {PROGRAM_INDEX, INSTRUCTION_ACCOUNTS, INSTRUCTION_ACCOUNTS + 1}) {
            byte[] outOfBounds = transfer(5_000);
            outOfBounds[position] = 3;
            assertThrows(IllegalArgumentException.class, () -> inspect(outOfBounds));
        }
        byte[] payerAsProgram = transfer(5_000);
        payerAsProgram[PROGRAM_INDEX] = 0;
        assertThrows(IllegalArgumentException.class, () -> inspect(payerAsProgram));
        for (int position : new int[] {MESSAGE + 1, MESSAGE + 2, KEY_COUNT}) {
            byte[] badHeader = transfer(5_000);
            badHeader[position] = position == MESSAGE + 1 ? (byte) 1 : position == KEY_COUNT ? (byte) 0 : (byte) 3;
            assertThrows(IllegalArgumentException.class, () -> inspect(badHeader));
        }
        byte[] duplicates = transfer(5_000);
        System.arraycopy(PAYER_BYTES, 0, duplicates, KEYS + 32, 32);
        assertThrows(IllegalArgumentException.class, () -> inspect(duplicates));
    }

    @Test void compactLengthsRejectAliasesOverflowAndContinuationBeyondThirdByte() {
        byte[] wire = transfer(5_000);
        for (int position : new int[] {0, KEY_COUNT, INSTRUCTION_COUNT, INSTRUCTION_ACCOUNT_COUNT, DATA_LENGTH}) {
            byte[] alias = replace(wire, position, 1, new byte[] {(byte) (wire[position] | 128), 0});
            assertThrows(IllegalArgumentException.class, () -> inspect(alias), "compact length at " + position);
        }
        for (byte[] prefix : new byte[][] {{(byte) 255, (byte) 255, 4},
                {(byte) 128, (byte) 128, (byte) 128}, {(byte) 140, (byte) 128, 0}}) {
            byte[] invalid = replace(wire, DATA_LENGTH, 1, prefix);
            assertThrows(IllegalArgumentException.class, () -> inspect(invalid));
        }
    }

    @Test void canonicalTwoByteLengthAndExact1232ByteBoundaryAreAccepted() {
        byte[] twoByteLength = memoFixture(128);
        assertEquals(128, Base64.getDecoder().decode(inspect(twoByteLength).instructions().getFirst().dataBase64()).length);
        byte[] atLimit = memoFixture(1_062);
        assertEquals(1_232, atLimit.length);
        assertEquals(1_062, Base64.getDecoder().decode(inspect(atLimit).instructions().getFirst().dataBase64()).length);
        assertThrows(IllegalArgumentException.class, () -> inspect(memoFixture(1_063)));
    }

    @Test void memoProbeContainsOnlyFixedMemoProgramNoTransferAndNoSignature() {
        String memo = "READ_ONLY_PROBE_42";
        byte[] probe = memoProbe(address(PAYER_BYTES), address(BLOCKHASH_BYTES), memo);
        var inspected = inspect(probe);
        assertEquals(List.of(new Account(address(PAYER_BYTES), true, true), new Account(MEMO, false, false)), inspected.accounts());
        assertEquals(List.of(new Instruction(MEMO, List.of(),
            Base64.getEncoder().encodeToString(memo.getBytes(StandardCharsets.US_ASCII)))), inspected.instructions());
        assertEquals(1, probe[0]);
        assertArrayEquals(new byte[64], Arrays.copyOfRange(probe, 1, 65));
        assertEquals(address(BLOCKHASH_BYTES), inspected.blockhash());
        assertDoesNotThrow(() -> memoProbe(address(PAYER_BYTES), address(BLOCKHASH_BYTES), "x".repeat(96)));
        for (String invalid : new String[] {"x".repeat(97), "mémo", "🔒"}) {
            assertThrows(IllegalArgumentException.class,
                () -> memoProbe(address(PAYER_BYTES), address(BLOCKHASH_BYTES), invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> memoProbe(address(PAYER_BYTES), address(BLOCKHASH_BYTES), null));
        assertThrows(IllegalArgumentException.class, () -> memoProbe("bad-address", address(BLOCKHASH_BYTES), memo));
        assertThrows(IllegalArgumentException.class, () -> memoProbe(address(PAYER_BYTES), "bad-blockhash", memo));
    }

    @Test void inspectionAndContractListsCannotBeMutatedAfterConstruction() {
        var inspected = inspect(transfer(5_000));
        assertThrows(UnsupportedOperationException.class, () -> inspected.accounts().clear());
        assertThrows(UnsupportedOperationException.class, () -> inspected.instructions().clear());
        assertThrows(UnsupportedOperationException.class, () -> inspected.instructions().getFirst().accounts().clear());
    }

    private static void assertRejectedContract(byte[] wire) {
        assertThrows(IllegalArgumentException.class,
            () -> verify(wire, address(BLOCKHASH_BYTES), approvedAccounts(), approvedInstructions()));
    }

    private static List<Account> approvedAccounts() {
        return List.of(new Account(address(PAYER_BYTES), true, true),
            new Account(address(RECIPIENT_BYTES), false, true), new Account(SYSTEM, false, false));
    }

    private static List<Instruction> approvedInstructions() {
        // 独立字面预期：System 转账的小端 u32 判别值 2，加小端 u64 金额 5000。
        byte[] data = {2, 0, 0, 0, (byte) 136, 19, 0, 0, 0, 0, 0, 0};
        return List.of(new Instruction(SYSTEM, List.of(address(PAYER_BYTES), address(RECIPIENT_BYTES)),
            Base64.getEncoder().encodeToString(data)));
    }

    private static byte[] transfer(long atoms) {
        byte[] data = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(2).putLong(atoms).array();
        return fixture(List.of(PAYER_BYTES, RECIPIENT_BYTES, new byte[32]), 2, new byte[] {0, 1}, data);
    }

    private static byte[] memoFixture(int dataLength) {
        byte[] data = new byte[dataLength];
        Arrays.fill(data, (byte) 'x');
        return fixture(List.of(PAYER_BYTES, Base58.decode(MEMO)), 1, new byte[0], data);
    }

    private static byte[] fixture(List<byte[]> keys, int programIndex, byte[] accounts, byte[] data) {
        var out = new ByteArrayOutputStream();
        out.write(1); out.writeBytes(new byte[64]);
        out.write(1); out.write(0); out.write(1);
        compact(out, keys.size());
        keys.forEach(out::writeBytes);
        out.writeBytes(BLOCKHASH_BYTES);
        out.write(1); out.write(programIndex);
        compact(out, accounts.length); out.writeBytes(accounts);
        compact(out, data.length); out.writeBytes(data);
        return out.toByteArray();
    }

    private static void compact(ByteArrayOutputStream out, int length) {
        do {
            int next = length & 127;
            length >>>= 7;
            out.write(length == 0 ? next : next | 128);
        } while (length != 0);
    }

    private static byte[] replace(byte[] source, int offset, int removed, byte[] inserted) {
        byte[] result = new byte[source.length - removed + inserted.length];
        System.arraycopy(source, 0, result, 0, offset);
        System.arraycopy(inserted, 0, result, offset, inserted.length);
        System.arraycopy(source, offset + removed, result, offset + inserted.length, source.length - offset - removed);
        return result;
    }

    private static byte[] repeated(int value) {
        byte[] result = new byte[32]; Arrays.fill(result, (byte) value); return result;
    }

    private static String address(byte[] bytes) { return Base58.encode(bytes); }
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}

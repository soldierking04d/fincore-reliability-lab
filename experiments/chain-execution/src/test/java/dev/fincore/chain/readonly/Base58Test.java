package dev.fincore.chain.readonly;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class Base58Test {
    @Test void knownVectorsAndLeadingZeroBytesArePreserved() {
        assertEquals("", Base58.encode(new byte[0]));
        assertArrayEquals(new byte[0], Base58.decode(""));
        assertEquals("2g", Base58.encode(new byte[] {97}));
        assertArrayEquals("Hello World".getBytes(StandardCharsets.US_ASCII), Base58.decode("JxF12TrwUP45BMd"));
        assertEquals("112", Base58.encode(new byte[] {0, 0, 1}));
        assertArrayEquals(new byte[] {0, 0, 1}, Base58.decode("112"));
        assertEquals("3D", Base58.encode(new byte[] {(byte) 128}));
        assertArrayEquals(new byte[] {(byte) 128}, Base58.decode("3D"));
        assertEquals("5Q", Base58.encode(new byte[] {(byte) 255}));
        assertArrayEquals(new byte[] {(byte) 255}, Base58.decode("5Q"));
        assertEquals("1".repeat(32), Base58.address("1".repeat(32)));
    }

    @Test void deterministicBytePatternsRoundTripIncludingHighBits() {
        for (int length = 0; length <= 128; length++) {
            byte[] value = new byte[length];
            Arrays.fill(value, (byte) (length + 128));
            assertArrayEquals(value, Base58.decode(Base58.encode(value)), "high-bit length=" + length);
            if (length > 0) value[0] = 0;
            assertArrayEquals(value, Base58.decode(Base58.encode(value)), "length=" + length);
        }
    }

    @Test void invalidAlphabetWhitespaceAndNon32ByteAddressesAreRejected() {
        for (String value : new String[] {"0", "O", "I", "l", " 2g", "2g\n", "é", "２"}) {
            assertThrows(IllegalArgumentException.class, () -> Base58.decode(value));
        }
        for (String value : new String[] {"", "1".repeat(31), "1".repeat(33), "z".repeat(45)}) {
            assertThrows(IllegalArgumentException.class, () -> Base58.address(value));
        }
        assertThrows(IllegalArgumentException.class, () -> Base58.decode(null));
        assertThrows(IllegalArgumentException.class, () -> Base58.encode(null));
        assertThrows(IllegalArgumentException.class, () -> Base58.address(null));
    }
}

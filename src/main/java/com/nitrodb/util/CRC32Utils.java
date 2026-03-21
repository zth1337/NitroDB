package com.nitrodb.util;

import java.util.zip.CRC32;

/**
 * CRC32 checksum utility for data integrity verification.
 */
public final class CRC32Utils {

    private CRC32Utils() {
        throw new AssertionError("Utility class");
    }

    /**
     * Compute CRC32 checksum of byte array.
     */
    public static int checksum(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return (int) crc.getValue();
    }

    /**
     * Compute CRC32 checksum of byte array region.
     */
    public static int checksum(byte[] data, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(data, offset, length);
        return (int) crc.getValue();
    }

    /**
     * Compute CRC32 over multiple byte arrays (concatenated logically).
     */
    public static int checksum(byte[]... arrays) {
        CRC32 crc = new CRC32();
        for (byte[] arr : arrays) {
            crc.update(arr);
        }
        return (int) crc.getValue();
    }
}
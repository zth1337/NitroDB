package com.nitrodb.util;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Zero-dependency byte manipulation utilities.
 */
public final class ByteUtils {

    private ByteUtils() {
        throw new AssertionError("Utility class");
    }

    public static byte[] intToBytes(int value) {
        return ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
    }

    public static int bytesToInt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, Integer.BYTES).getInt();
    }

    public static byte[] longToBytes(long value) {
        return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }

    public static long bytesToLong(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, Long.BYTES).getLong();
    }

    public static byte[] toBytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static String fromBytes(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static String fromBytes(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, StandardCharsets.UTF_8);
    }

    /**
     * Write int into byte array at given offset (big-endian).
     */
    public static void putInt(byte[] dest, int offset, int value) {
        dest[offset]     = (byte) (value >>> 24);
        dest[offset + 1] = (byte) (value >>> 16);
        dest[offset + 2] = (byte) (value >>> 8);
        dest[offset + 3] = (byte) value;
    }

    /**
     * Read int from byte array at given offset (big-endian).
     */
    public static int getInt(byte[] src, int offset) {
        return ((src[offset] & 0xFF) << 24)
                | ((src[offset + 1] & 0xFF) << 16)
                | ((src[offset + 2] & 0xFF) << 8)
                | (src[offset + 3] & 0xFF);
    }

    /**
     * Write long into byte array at given offset (big-endian).
     */
    public static void putLong(byte[] dest, int offset, long value) {
        dest[offset]     = (byte) (value >>> 56);
        dest[offset + 1] = (byte) (value >>> 48);
        dest[offset + 2] = (byte) (value >>> 40);
        dest[offset + 3] = (byte) (value >>> 32);
        dest[offset + 4] = (byte) (value >>> 24);
        dest[offset + 5] = (byte) (value >>> 16);
        dest[offset + 6] = (byte) (value >>> 8);
        dest[offset + 7] = (byte) value;
    }

    /**
     * Read long from byte array at given offset (big-endian).
     */
    public static long getLong(byte[] src, int offset) {
        return ((long)(src[offset] & 0xFF) << 56)
                | ((long)(src[offset + 1] & 0xFF) << 48)
                | ((long)(src[offset + 2] & 0xFF) << 40)
                | ((long)(src[offset + 3] & 0xFF) << 32)
                | ((long)(src[offset + 4] & 0xFF) << 24)
                | ((long)(src[offset + 5] & 0xFF) << 16)
                | ((long)(src[offset + 6] & 0xFF) << 8)
                | (src[offset + 7] & 0xFF);
    }
}
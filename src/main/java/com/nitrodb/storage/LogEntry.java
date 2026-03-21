package com.nitrodb.storage;

import com.nitrodb.util.ByteUtils;
import com.nitrodb.util.CRC32Utils;

import java.nio.charset.StandardCharsets;

/**
 * Represents a single entry in the append-only log (data file).
 *
 * Binary format (all big-endian):
 * ┌──────────┬───────────┬──────────┬───────────┬──────────┬──────┬───────┐
 * │ CRC32    │ Timestamp │ KeySize  │ ValueSize │ Tombstone│ Key  │ Value │
 * │ 4 bytes  │ 8 bytes   │ 4 bytes  │ 4 bytes   │ 1 byte   │ var  │ var   │
 * └──────────┴───────────┴──────────┴───────────┴──────────┴──────┴───────┘
 *
 * HEADER_SIZE = 4 + 8 + 4 + 4 + 1 = 21 bytes
 */
public record LogEntry(
        int crc,
        long timestamp,
        int keySize,
        int valueSize,
        boolean tombstone,
        byte[] key,
        byte[] value
) {

    public static final int HEADER_SIZE = 4 + 8 + 4 + 4 + 1; // 21 bytes

    /**
     * Create a new LogEntry for a SET operation.
     */
    public static LogEntry create(String key, byte[] value) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        long timestamp = System.currentTimeMillis();
        int crc = computeCrc(timestamp, keyBytes, value, false);
        return new LogEntry(crc, timestamp, keyBytes.length, value.length, false, keyBytes, value);
    }

    /**
     * Create a tombstone LogEntry for a DEL operation.
     */
    public static LogEntry tombstone(String key) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] emptyValue = new byte[0];
        long timestamp = System.currentTimeMillis();
        int crc = computeCrc(timestamp, keyBytes, emptyValue, true);
        return new LogEntry(crc, timestamp, keyBytes.length, 0, true, keyBytes, emptyValue);
    }

    /**
     * Serialize this entry to bytes for writing to disk.
     */
    public byte[] toBytes() {
        int totalSize = HEADER_SIZE + keySize + valueSize;
        byte[] buf = new byte[totalSize];

        ByteUtils.putInt(buf, 0, crc);
        ByteUtils.putLong(buf, 4, timestamp);
        ByteUtils.putInt(buf, 12, keySize);
        ByteUtils.putInt(buf, 16, valueSize);
        buf[20] = (byte) (tombstone ? 1 : 0);

        System.arraycopy(key, 0, buf, HEADER_SIZE, keySize);
        if (valueSize > 0) {
            System.arraycopy(value, 0, buf, HEADER_SIZE + keySize, valueSize);
        }

        return buf;
    }

    /**
     * Deserialize a LogEntry from bytes.
     */
    public static LogEntry fromBytes(byte[] buf) {
        if (buf.length < HEADER_SIZE) {
            throw new IllegalArgumentException("Buffer too small for header: " + buf.length);
        }

        int crc = ByteUtils.getInt(buf, 0);
        long timestamp = ByteUtils.getLong(buf, 4);
        int keySize = ByteUtils.getInt(buf, 12);
        int valueSize = ByteUtils.getInt(buf, 16);
        boolean tombstone = buf[20] != 0;

        if (buf.length < HEADER_SIZE + keySize + valueSize) {
            throw new IllegalArgumentException("Buffer too small for entry data");
        }

        byte[] key = new byte[keySize];
        System.arraycopy(buf, HEADER_SIZE, key, 0, keySize);

        byte[] value = new byte[valueSize];
        if (valueSize > 0) {
            System.arraycopy(buf, HEADER_SIZE + keySize, value, 0, valueSize);
        }

        return new LogEntry(crc, timestamp, keySize, valueSize, tombstone, key, value);
    }

    /**
     * Get the total size of this entry on disk.
     */
    public int totalSize() {
        return HEADER_SIZE + keySize + valueSize;
    }

    /**
     * Get key as string.
     */
    public String keyString() {
        return new String(key, StandardCharsets.UTF_8);
    }

    /**
     * Validate CRC integrity.
     */
    public boolean isValid() {
        int expected = computeCrc(timestamp, key, value, tombstone);
        return expected == crc;
    }

    /**
     * Compute CRC32 over the data portion (excluding CRC field itself).
     */
    private static int computeCrc(long timestamp, byte[] key, byte[] value, boolean tombstone) {
        // CRC covers: timestamp + keySize + valueSize + tombstone + key + value
        byte[] header = new byte[8 + 4 + 4 + 1];
        ByteUtils.putLong(header, 0, timestamp);
        ByteUtils.putInt(header, 8, key.length);
        ByteUtils.putInt(header, 12, value.length);
        header[16] = (byte) (tombstone ? 1 : 0);
        return CRC32Utils.checksum(header, key, value);
    }
}
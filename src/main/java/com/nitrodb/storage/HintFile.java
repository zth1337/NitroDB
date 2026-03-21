package com.nitrodb.storage;

import com.nitrodb.util.ByteUtils;
import com.nitrodb.util.FileUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Hint file for fast startup recovery.
 * Contains only key + pointer information (no values),
 * allowing the KeyDir to be rebuilt without scanning full data files.
 *
 * Format per entry:
 * ┌───────────┬──────────┬──────────┬──────────┬──────────┐
 * │ Timestamp │ KeySize  │ EntrySize│ Offset   │ Key      │
 * │ 8 bytes   │ 4 bytes  │ 4 bytes  │ 8 bytes  │ var      │
 * └───────────┴──────────┴──────────┴──────────┴──────────┘
 */
public final class HintFile {

    public record HintEntry(long timestamp, int keySize, int entrySize, long offset, String key) {}

    private static final int HINT_HEADER_SIZE = 8 + 4 + 4 + 8; // 24 bytes

    /**
     * Write hint entries for a data file.
     */
    public static void write(Path directory, long fileId, List<HintEntry> entries) throws IOException {
        Path hintPath = FileUtils.hintFilePath(directory, fileId);
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(hintPath.toFile())))) {
            for (HintEntry entry : entries) {
                byte[] keyBytes = entry.key().getBytes(StandardCharsets.UTF_8);
                dos.writeLong(entry.timestamp());
                dos.writeInt(keyBytes.length);
                dos.writeInt(entry.entrySize());
                dos.writeLong(entry.offset());
                dos.write(keyBytes);
            }
            dos.flush();
        }
    }

    /**
     * Read hint entries from a hint file.
     */
    public static List<HintEntry> read(Path directory, long fileId) throws IOException {
        Path hintPath = FileUtils.hintFilePath(directory, fileId);
        List<HintEntry> entries = new ArrayList<>();

        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(new FileInputStream(hintPath.toFile())))) {
            while (dis.available() > 0) {
                long timestamp = dis.readLong();
                int keySize = dis.readInt();
                int entrySize = dis.readInt();
                long offset = dis.readLong();
                byte[] keyBytes = new byte[keySize];
                dis.readFully(keyBytes);
                String key = new String(keyBytes, StandardCharsets.UTF_8);
                entries.add(new HintEntry(timestamp, keySize, entrySize, offset, key));
            }
        }

        return entries;
    }
}
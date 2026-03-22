package com.nitrodb.storage;

import com.nitrodb.util.FileUtils;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

public final class HintFile {
    public record HintEntry(long timestamp, int keySize, int entrySize, long offset, String key) {}

    public static void write(Path directory, long fileId, List<HintEntry> entries) throws IOException {
        Path hintPath = FileUtils.hintFilePath(directory, fileId);
        Path tmpPath = hintPath.resolveSibling(hintPath.getFileName() + ".tmp");
        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmpPath.toFile())))) {
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
        Files.move(tmpPath, hintPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public static List<HintEntry> read(Path directory, long fileId) throws IOException {
        Path hintPath = FileUtils.hintFilePath(directory, fileId);
        List<HintEntry> entries = new java.util.ArrayList<>();
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(hintPath.toFile())))) {
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
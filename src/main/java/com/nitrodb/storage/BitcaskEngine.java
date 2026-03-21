package com.nitrodb.storage;

import com.nitrodb.config.NitroConfig;
import com.nitrodb.util.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Bitcask storage engine implementation.
 *
 * - Append-only writes for maximum write throughput
 * - In-memory KeyDir for O(1) key lookup
 * - Single disk seek per read
 * - Background compaction for space reclamation
 */
public final class BitcaskEngine implements StorageEngine {

    private final NitroConfig config;
    private final DataFileManager fileManager;
    private final KeyDir keyDir;
    private final CompactionEngine compactionEngine;
    private volatile boolean closed = false;

    public BitcaskEngine(NitroConfig config) throws IOException {
        this.config = config;
        this.keyDir = new KeyDir();
        this.fileManager = new DataFileManager(config);
        this.compactionEngine = new CompactionEngine(config, fileManager, keyDir);

        recover();

        compactionEngine.startScheduler();
    }

    /**
     * Recover KeyDir from existing data/hint files on startup.
     */
    private void recover() throws IOException {
        Path dir = config.dataDirectory();
        if (!Files.exists(dir)) return;

        List<Path> hintFiles = FileUtils.listHintFiles(dir);
        Set<Long> recoveredFromHints = new HashSet<>();

        for (Path hintPath : hintFiles) {
            long fileId = FileUtils.extractFileId(hintPath);
            try {
                List<HintFile.HintEntry> entries = HintFile.read(dir, fileId);
                for (HintFile.HintEntry hint : entries) {
                    RecordPointer ptr = new RecordPointer(
                            fileId, hint.offset(), hint.entrySize(), hint.timestamp()
                    );
                    keyDir.putIfNewer(hint.key(), ptr);
                }
                recoveredFromHints.add(fileId);
            } catch (IOException e) {
                System.err.println("[NitroDB] Failed to read hint file for " + fileId + ": " + e.getMessage());
            }
        }

        List<Path> dataFiles = FileUtils.listDataFiles(dir);
        for (Path dataPath : dataFiles) {
            long fileId = FileUtils.extractFileId(dataPath);
            if (recoveredFromHints.contains(fileId)) {
                continue;
            }
            scanDataFile(fileId);
        }

        System.out.println("[NitroDB] Recovery complete. " + keyDir.size() + " keys loaded.");
    }

    /**
     * Scan a data file entry by entry to rebuild the index.
     */
    private void scanDataFile(long fileId) throws IOException {
        DataFile df = fileManager.getFile(fileId);
        if (df == null) return;

        long offset = 0;
        long fileSize = df.size();

        while (offset < fileSize) {
            try {
                LogEntry entry = df.read(offset);

                if (!entry.isValid()) {
                    System.err.println("[NitroDB] Corrupt entry at file=" + fileId + " offset=" + offset);
                    break;
                }

                String key = entry.keyString();
                if (entry.tombstone()) {
                    keyDir.remove(key);
                } else {
                    RecordPointer ptr = new RecordPointer(
                            fileId, offset, entry.totalSize(), entry.timestamp()
                    );
                    keyDir.putIfNewer(key, ptr);
                }

                offset += entry.totalSize();
            } catch (IOException e) {
                break;
            }
        }
    }

    @Override
    public void put(String key, byte[] value) throws IOException {
        ensureOpen();
        validateKey(key);
        validateValue(value);

        LogEntry entry = LogEntry.create(key, value);

        synchronized (this) {
            DataFile activeFile = fileManager.getActiveFile();
            long offset = activeFile.append(entry, config.syncOnWrite());

            RecordPointer ptr = new RecordPointer(
                    activeFile.getFileId(), offset,
                    entry.totalSize(), entry.timestamp()
            );
            keyDir.put(key, ptr);
        }
    }

    @Override
    public Optional<byte[]> get(String key) throws IOException {
        ensureOpen();

        RecordPointer ptr = keyDir.get(key);
        if (ptr == null) {
            return Optional.empty();
        }

        DataFile df = fileManager.getFile(ptr.fileId());
        if (df == null) {
            keyDir.remove(key);
            return Optional.empty();
        }

        LogEntry entry = df.read(ptr.offset());

        if (!entry.isValid()) {
            System.err.println("[NitroDB] CRC mismatch for key: " + key);
            keyDir.remove(key);
            return Optional.empty();
        }

        if (entry.tombstone()) {
            keyDir.remove(key);
            return Optional.empty();
        }

        return Optional.of(entry.value());
    }

    @Override
    public boolean delete(String key) throws IOException {
        ensureOpen();

        if (!keyDir.containsKey(key)) {
            return false;
        }

        LogEntry tombstone = LogEntry.tombstone(key);

        synchronized (this) {
            DataFile activeFile = fileManager.getActiveFile();
            activeFile.append(tombstone, config.syncOnWrite());
            keyDir.remove(key);
        }

        return true;
    }

    @Override
    public boolean exists(String key) {
        return keyDir.containsKey(key);
    }

    @Override
    public Set<String> keys() {
        return Collections.unmodifiableSet(keyDir.keys());
    }

    @Override
    public long size() {
        return keyDir.size();
    }

    @Override
    public void compact() throws IOException {
        compactionEngine.compact();
    }

    @Override
    public void flush() throws IOException {
        // data is written directly via RandomAccessFile, which goes to OS buffer
        // if syncOnWrite is true, data is already fsync'd
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            compactionEngine.stopScheduler();
            fileManager.close();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Storage engine is closed");
        }
    }

    private void validateKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("Key cannot be null or empty");
        }
        if (key.getBytes().length > config.maxKeySize()) {
            throw new IllegalArgumentException("Key size exceeds maximum of " + config.maxKeySize() + " bytes");
        }
    }

    private void validateValue(byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("Value cannot be null");
        }
        if (value.length > config.maxValueSize()) {
            throw new IllegalArgumentException("Value size exceeds maximum of " + config.maxValueSize() + " bytes");
        }
    }

    /**
     * Get the KeyDir for diagnostics/testing.
     */
    public KeyDir getKeyDir() {
        return keyDir;
    }
}
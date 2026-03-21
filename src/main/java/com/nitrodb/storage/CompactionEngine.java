package com.nitrodb.storage;

import com.nitrodb.config.NitroConfig;
import com.nitrodb.util.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Background compaction engine.
 * Merges old data files, removing stale/deleted entries.
 * Produces new compacted data files + hint files.
 */
public final class CompactionEngine {

    private final NitroConfig config;
    private final DataFileManager fileManager;
    private final KeyDir keyDir;
    private final ReentrantLock compactionLock = new ReentrantLock();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread compactionThread;

    public CompactionEngine(NitroConfig config, DataFileManager fileManager, KeyDir keyDir) {
        this.config = config;
        this.fileManager = fileManager;
        this.keyDir = keyDir;
    }

    /**
     * Start background compaction scheduler.
     */
    public void startScheduler() {
        if (running.compareAndSet(false, true)) {
            compactionThread = Thread.ofVirtual().name("nitrodb-compaction").start(() -> {
                while (running.get()) {
                    try {
                        Thread.sleep(config.compactionIntervalMs());
                        if (shouldCompact()) {
                            compact();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (IOException e) {
                        System.err.println("[NitroDB] Compaction error: " + e.getMessage());
                    }
                }
            });
        }
    }

    /**
     * Stop the background compaction scheduler.
     */
    public void stopScheduler() {
        running.set(false);
        if (compactionThread != null) {
            compactionThread.interrupt();
        }
    }

    /**
     * Check if compaction is needed based on estimated dead bytes.
     */
    public boolean shouldCompact() {
        List<Long> immutableFiles = fileManager.getImmutableFileIds();
        if (immutableFiles.size() < 2) {
            return false;
        }

        return immutableFiles.size() >= 3;
    }

    /**
     * Perform compaction: merge all immutable files into new compacted files.
     */
    public void compact() throws IOException {
        if (!compactionLock.tryLock()) {
            return;
        }

        try {
            List<Long> immutableFileIds = fileManager.getImmutableFileIds();
            if (immutableFileIds.size() < 2) {
                return;
            }

            System.out.println("[NitroDB] Starting compaction of " + immutableFileIds.size() + " files");

            long mergeFileId = fileManager.nextFileId();
            DataFile mergeFile = DataFile.open(fileManager.getDataDirectory(), mergeFileId);
            List<HintFile.HintEntry> hintEntries = new ArrayList<>();

            Map<String, RecordPointer> liveEntries = new LinkedHashMap<>();
            for (var entry : keyDir.entries()) {
                if (immutableFileIds.contains(entry.getValue().fileId())) {
                    liveEntries.put(entry.getKey(), entry.getValue());
                }
            }

            for (var entry : liveEntries.entrySet()) {
                String key = entry.getKey();
                RecordPointer ptr = entry.getValue();

                DataFile sourceFile = fileManager.getFile(ptr.fileId());
                if (sourceFile == null) continue;

                LogEntry logEntry = sourceFile.read(ptr.offset());

                if (logEntry.tombstone()) continue;

                if (mergeFile.size() >= config.maxDataFileSize()) {
                    if (!hintEntries.isEmpty()) {
                        HintFile.write(fileManager.getDataDirectory(), mergeFile.getFileId(), hintEntries);
                    }
                    fileManager.registerFile(mergeFile);
                    hintEntries = new ArrayList<>();
                    mergeFileId = fileManager.nextFileId();
                    mergeFile = DataFile.open(fileManager.getDataDirectory(), mergeFileId);
                }

                long newOffset = mergeFile.append(logEntry, false);

                RecordPointer newPtr = new RecordPointer(
                        mergeFile.getFileId(), newOffset,
                        logEntry.totalSize(), logEntry.timestamp()
                );

                // CAS: only update KeyDir if no concurrent write happened
                if (!keyDir.replaceIfSame(key, ptr, newPtr)) {
                    // Key was modified by client during compaction, discard this entry
                    continue;
                }

                hintEntries.add(new HintFile.HintEntry(
                        logEntry.timestamp(), logEntry.keySize(),
                        logEntry.totalSize(), newOffset, key
                ));
            }

            if (!hintEntries.isEmpty()) {
                HintFile.write(fileManager.getDataDirectory(), mergeFile.getFileId(), hintEntries);
            }
            fileManager.registerFile(mergeFile);

            for (long oldId : immutableFileIds) {
                fileManager.removeFile(oldId);
                Path oldHintPath = FileUtils.hintFilePath(fileManager.getDataDirectory(), oldId);
                FileUtils.deleteQuietly(oldHintPath);
            }

            System.out.println("[NitroDB] Compaction complete. Merged " + immutableFileIds.size()
                    + " files, " + liveEntries.size() + " live entries.");

        } finally {
            compactionLock.unlock();
        }
    }
}
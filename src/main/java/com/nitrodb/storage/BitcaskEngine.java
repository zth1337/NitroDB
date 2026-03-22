package com.nitrodb.storage;

import com.nitrodb.config.NitroConfig;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

public final class BitcaskEngine implements StorageEngine {
    private final NitroConfig config;
    private final DataFileManager fileManager;
    private final KeyDir keyDir;
    private final CompactionEngine compactionEngine;
    private volatile boolean closed = false;
    private final BlockingQueue<WriteTask> writeQueue = new ArrayBlockingQueue<>(50000);
    private final Thread writerThread;

    private record WriteTask(LogEntry entry, CompletableFuture<Void> future) {}

    public BitcaskEngine(NitroConfig config) throws IOException {
        this.config = config;
        this.keyDir = new KeyDir();
        this.fileManager = new DataFileManager(config);
        this.compactionEngine = new CompactionEngine(config, fileManager, keyDir);
        this.writerThread = Thread.ofPlatform().name("nitrodb-writer").start(this::writerLoop);
        compactionEngine.startScheduler();
    }

    @Override
    public void put(String key, byte[] value) throws Exception {
        ensureOpen();
        LogEntry entry = LogEntry.create(key, value);
        CompletableFuture<Void> future = new CompletableFuture<>();
        writeQueue.put(new WriteTask(entry, future));
        future.get();
    }

    @Override
    public boolean delete(String key) throws Exception {
        ensureOpen();
        if (!keyDir.containsKey(key)) return false;
        LogEntry tombstone = LogEntry.tombstone(key);
        CompletableFuture<Void> future = new CompletableFuture<>();
        writeQueue.put(new WriteTask(tombstone, future));
        future.get();
        return true;
    }

    private void writerLoop() {
        List<WriteTask> batch = new ArrayList<>(2000);
        while (!closed) {
            try {
                WriteTask first = writeQueue.take();
                batch.add(first);
                writeQueue.drainTo(batch, 1999);
                DataFile activeFile = fileManager.getActiveFile();
                for (WriteTask task : batch) {
                    long offset = activeFile.append(task.entry());
                    updateIndex(task.entry(), activeFile.getFileId(), offset);
                }
                if (config.syncOnWrite()) {
                    activeFile.force();
                }
                for (WriteTask task : batch) task.future.complete(null);
                batch.clear();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                batch.forEach(t -> t.future.completeExceptionally(e));
                batch.clear();
            }
        }
    }

    private void updateIndex(LogEntry entry, long fileId, long offset) {
        String key = entry.keyString();
        if (entry.tombstone()) {
            keyDir.remove(key);
        } else {
            keyDir.put(key, new RecordPointer(fileId, offset, entry.totalSize(), entry.timestamp()));
        }
    }

    @Override
    public Optional<byte[]> get(String key) throws IOException {
        RecordPointer ptr = keyDir.get(key);
        if (ptr == null) return Optional.empty();
        DataFile df = fileManager.getFile(ptr.fileId());
        if (df == null) return Optional.empty();
        LogEntry entry = df.read(ptr.offset());
        return entry.tombstone() ? Optional.empty() : Optional.of(entry.value());
    }

    @Override
    public void close() throws IOException {
        closed = true;
        writerThread.interrupt();
        fileManager.close();
    }

    private void ensureOpen() { if (closed) throw new IllegalStateException("Closed"); }
    @Override public boolean exists(String key) { return keyDir.containsKey(key); }
    @Override public Set<String> keys() { return keyDir.keys(); }
    @Override public long size() { return keyDir.size(); }
    @Override public void compact() throws IOException { compactionEngine.compact(); }
    @Override public void flush() {}
}
package com.nitrodb.storage;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory index mapping keys to their latest RecordPointer.
 * Lock-free reads via ConcurrentHashMap.
 *
 * This is the "hot path" — every GET goes through here.
 */
public final class KeyDir {

    private final ConcurrentHashMap<String, RecordPointer> index;
    private final AtomicLong totalEntries;
    private final AtomicLong totalKeyBytes;

    public KeyDir() {
        this.index = new ConcurrentHashMap<>(1024, 0.75f, Runtime.getRuntime().availableProcessors());
        this.totalEntries = new AtomicLong(0);
        this.totalKeyBytes = new AtomicLong(0);
    }

    /**
     * Put or update a key in the index.
     * Returns the previous pointer if key existed, null otherwise.
     */
    public RecordPointer put(String key, RecordPointer pointer) {
        RecordPointer prev = index.put(key, pointer);
        if (prev == null) {
            totalEntries.incrementAndGet();
            totalKeyBytes.addAndGet(key.length());
        }
        return prev;
    }

    /**
     * Put only if the new entry is newer than existing.
     * Used during startup recovery.
     */
    public void putIfNewer(String key, RecordPointer pointer) {
        index.compute(key, (k, existing) -> {
            if (existing == null || pointer.timestamp() >= existing.timestamp()) {
                if (existing == null) {
                    totalEntries.incrementAndGet();
                    totalKeyBytes.addAndGet(key.length());
                }
                return pointer;
            }
            return existing;
        });
    }

    /**
     * Get the pointer for a key. O(1) lock-free read.
     */
    public RecordPointer get(String key) {
        return index.get(key);
    }

    /**
     * Replace pointer only if current value matches expected.
     * Used during compaction to avoid overwriting concurrent writes.
     */
    public boolean replaceIfSame(String key, RecordPointer expected, RecordPointer newPointer) {
        boolean replaced = index.replace(key, expected, newPointer);
        // Note: totalEntries and totalKeyBytes stay same since key already exists
        return replaced;
    }

    /**
     * Remove a key from the index.
     */
    public RecordPointer remove(String key) {
        RecordPointer removed = index.remove(key);
        if (removed != null) {
            totalEntries.decrementAndGet();
            totalKeyBytes.addAndGet(-key.length());
        }
        return removed;
    }

    /**
     * Check if a key exists.
     */
    public boolean containsKey(String key) {
        return index.containsKey(key);
    }

    /**
     * Get total number of keys.
     */
    public long size() {
        return totalEntries.get();
    }

    /**
     * Get all keys.
     */
    public Set<String> keys() {
        return index.keySet();
    }

    /**
     * Get all entries (for compaction/iteration).
     */
    public Set<Map.Entry<String, RecordPointer>> entries() {
        return index.entrySet();
    }

    /**
     * Get snapshot of all entries pointing to a specific file.
     */
    public Map<String, RecordPointer> entriesForFile(long fileId) {
        var result = new ConcurrentHashMap<String, RecordPointer>();
        for (var entry : index.entrySet()) {
            if (entry.getValue().fileId() == fileId) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    /**
     * Clear the entire index.
     */
    public void clear() {
        index.clear();
        totalEntries.set(0);
        totalKeyBytes.set(0);
    }

    /**
     * Get memory usage estimate in bytes.
     */
    public long estimatedMemoryUsage() {
        // rough estimate: key string overhead + RecordPointer + ConcurrentHashMap node overhead
        long entries = totalEntries.get();
        long keyBytes = totalKeyBytes.get();
        // ~48 bytes per CHM node + 40 bytes per string + key chars + 32 bytes per RecordPointer
        return entries * (48 + 40 + 32) + keyBytes * 2; // char = 2 bytes
    }
}
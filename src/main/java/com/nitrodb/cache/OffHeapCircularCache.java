package com.nitrodb.cache;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Off-heap LRU cache using Panama Memory API with TRUE Circular Buffer.
 *
 * EVICTION STRATEGIES:
 * 1. Logical (LRU): Randomized LRU (Redis algorithm) limits total item count.
 * 2. Physical (Ring Buffer): Automatically overwrites oldest physical bytes 
 *    when max memory is reached, without pausing or completely clearing the cache.
 *
 * THREAD SAFETY:
 * - get(): Highly concurrent, uses Read Lock.
 * - put(): Serialized via Write Lock to safely allocate contiguous memory blocks 
 *          and prevent wrap-around corruption.
 */
public final class OffHeapCircularCache implements AutoCloseable {

    private static final int EVICTION_SAMPLE_SIZE = 5;
    private static final int EVICTION_BATCH_SIZE = 10;

    private final long maxSize;
    private final int maxEntries;
    private final Arena arena;
    private final MemorySegment slab;

    private long writeOffset = 0;

    private final ConcurrentHashMap<String, BufferEntry> index;

    private final ConcurrentSkipListMap<Long, String> offsetIndex;

    private final ReentrantReadWriteLock rwLock;
    private volatile boolean closed = false;

    private volatile String[] keySnapshot = new String[0];
    private volatile long lastSnapshotSize = 0;

    public OffHeapCircularCache(long maxSize, int maxEntries) {
        this.maxSize = maxSize;
        this.maxEntries = maxEntries;
        this.arena = Arena.ofShared();
        this.slab = arena.allocate(maxSize);
        this.index = new ConcurrentHashMap<>(maxEntries / 4, 0.75f,
                Runtime.getRuntime().availableProcessors());
        this.offsetIndex = new ConcurrentSkipListMap<>();
        this.rwLock = new ReentrantReadWriteLock();
    }

    /**
     * Записать значение в кольцевой буфер.
     */
    public void put(String key, byte[] value) {
        int valueLen = value.length;
        if (closed || valueLen > maxSize / 2) return;

        rwLock.writeLock().lock();
        try {
            if (closed) return;

            while (index.size() >= maxEntries) {
                evictRandomizedLRU();
            }

            long startOffset = writeOffset;

            if (startOffset + valueLen > maxSize) {
                invalidateRange(startOffset, maxSize);
                startOffset = 0;
            }

            long endOffset = startOffset + valueLen;

            invalidateRange(startOffset, endOffset);

            writeOffset = endOffset;

            MemorySegment.copy(
                    MemorySegment.ofArray(value), ValueLayout.JAVA_BYTE, 0,
                    slab, ValueLayout.JAVA_BYTE, startOffset,
                    valueLen
            );

            BufferEntry entry = new BufferEntry(startOffset, valueLen, System.nanoTime());
            BufferEntry old = index.put(key, entry);

            if (old != null) {
                offsetIndex.remove(old.offset(), key);
            }
            offsetIndex.put(startOffset, key);

        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Прочитать значение из кэша.
     */
    public Optional<byte[]> get(String key) {
        if (closed) return Optional.empty();

        BufferEntry fastCheck = index.get(key);
        if (fastCheck == null) return Optional.empty();

        rwLock.readLock().lock();
        try {
            if (closed) return Optional.empty();

            BufferEntry entry = index.get(key);
            if (entry == null || entry.offset() != fastCheck.offset()) {
                return Optional.empty();
            }

            byte[] value = new byte[entry.size()];
            MemorySegment.copy(
                    slab, ValueLayout.JAVA_BYTE, entry.offset(),
                    MemorySegment.ofArray(value), ValueLayout.JAVA_BYTE, 0,
                    entry.size()
            );

            index.put(key, new BufferEntry(entry.offset(), entry.size(), System.nanoTime()));

            return Optional.of(value);

        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Вспомогательный метод для полного удаления ключа из всех индексов.
     */
    public void remove(String key) {
        BufferEntry removed = index.remove(key);
        if (removed != null) {
            offsetIndex.remove(removed.offset(), key);
        }
    }

    /**
     * Очистка кэша.
     */
    public void clear() {
        rwLock.writeLock().lock();
        try {
            index.clear();
            offsetIndex.clear();
            writeOffset = 0;
            keySnapshot = new String[0];
            lastSnapshotSize = 0;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public int size() {
        return index.size();
    }

    public long memoryUsage() {
        return offsetIndex.isEmpty() ? 0 : 
               (writeOffset < offsetIndex.lastKey() ? maxSize : writeOffset);
    }

    /**
     * Умная инвалидация физического участка памяти [start, end).
     * Вызывается строго под Write Lock.
     */
    private void invalidateRange(long start, long end) {
        if (start >= end) return;

        var floorEntry = offsetIndex.floorEntry(start);
        if (floorEntry != null) {
            String floorKey = floorEntry.getValue();
            BufferEntry entry = index.get(floorKey);
            if (entry != null && (entry.offset() + entry.size() > start)) {
                remove(floorKey);
            }
        }

        var toRemove = offsetIndex.subMap(start, end);
        for (String key : toRemove.values()) {
            index.remove(key);
        }
        toRemove.clear();
    }

    /**
     * Рандомизированный O(1) LRU (Алгоритм Redis).
     * Вызывается строго под Write Lock.
     */
    private void evictRandomizedLRU() {
        refreshKeySnapshotIfNeeded();

        String[] snapshot = this.keySnapshot;
        if (snapshot.length == 0) return;

        ThreadLocalRandom rng = ThreadLocalRandom.current();

        int evicted = 0;
        while (evicted < EVICTION_BATCH_SIZE && index.size() >= maxEntries) {
            String oldestKey = null;
            long oldestTimestamp = Long.MAX_VALUE;

            int sampled = 0;
            int attempts = 0;
            int maxAttempts = EVICTION_SAMPLE_SIZE * 3;

            while (sampled < EVICTION_SAMPLE_SIZE && attempts < maxAttempts) {
                attempts++;
                int idx = rng.nextInt(snapshot.length);
                String candidateKey = snapshot[idx];

                BufferEntry entry = index.get(candidateKey);
                if (entry == null) continue;

                sampled++;

                if (entry.timestamp() < oldestTimestamp) {
                    oldestTimestamp = entry.timestamp();
                    oldestKey = candidateKey;
                }
            }

            if (oldestKey != null) {
                remove(oldestKey);
                evicted++;
            } else {
                break;
            }
        }
    }

    /**
     * Ленивое перестроение массива ключей для O(1) рандомизации.
     */
    private void refreshKeySnapshotIfNeeded() {
        long currentSize = index.size();
        long snapshotSize = lastSnapshotSize;

        boolean needsRefresh = (snapshotSize == 0 && currentSize > 0)
                || (snapshotSize > 0 && Math.abs(currentSize - snapshotSize) > snapshotSize * 0.2);

        if (needsRefresh) {
            keySnapshot = index.keySet().toArray(new String[0]);
            lastSnapshotSize = keySnapshot.length;
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            index.clear();
            offsetIndex.clear();
            keySnapshot = new String[0];
            arena.close();
        }
    }

    /**
     * Внутренняя структура метаданных записи.
     */
    private record BufferEntry(long offset, int size, long timestamp) {}
}
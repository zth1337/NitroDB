package com.nitrodb.cache;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Off-heap LRU-ish cache using Panama Memory API (java.lang.foreign).
 *
 * Stores values outside the Java heap, eliminating GC pressure
 * even when caching gigabytes of data.
 *
 * EVICTION STRATEGY: Randomized LRU (same algorithm as Redis).
 * Instead of scanning all N entries to find the oldest (O(N)),
 * we sample K random entries and evict the oldest among them (O(K), K=5).
 * This provides near-optimal LRU behavior at constant cost.
 *
 * Uses Arena.ofShared() for thread-safe memory management.
 */
public final class OffHeapCache implements AutoCloseable {

    /**
     * Number of random samples for eviction.
     * Redis uses 5 by default. Higher = more accurate LRU, slightly more CPU.
     * 5 is the sweet spot proven by Redis benchmarks.
     */
    private static final int EVICTION_SAMPLE_SIZE = 5;

    /**
     * Number of entries to evict when cache is full.
     * Evicting a small batch prevents repeated eviction on every put().
     */
    private static final int EVICTION_BATCH_SIZE = 10;

    private final long maxSize;
    private final int maxEntries;
    private final Arena arena;
    private MemorySegment slab;
    private final AtomicLong slabOffset;
    private final ConcurrentHashMap<String, OffHeapEntry> index;
    private final ReentrantReadWriteLock rwLock;
    private volatile boolean closed = false;

    /**
     * Snapshot of keys for O(1) random access during eviction.
     * Rebuilt lazily — doesn't need to be perfectly accurate.
     */
    private volatile String[] keySnapshot = new String[0];
    private volatile long lastSnapshotSize = 0;

    public OffHeapCache(long maxSize, int maxEntries) {
        this.maxSize = maxSize;
        this.maxEntries = maxEntries;
        this.arena = Arena.ofShared();
        this.slab = arena.allocate(maxSize);
        this.slabOffset = new AtomicLong(0);
        this.index = new ConcurrentHashMap<>(maxEntries / 4, 0.75f,
                Runtime.getRuntime().availableProcessors());
        this.rwLock = new ReentrantReadWriteLock();
    }

    /**
     * Put a value into the off-heap cache.
     */
    public void put(String key, byte[] value) {
        if (closed || value.length > maxSize / 2) return;

        rwLock.readLock().lock();
        try {
            if (closed) return;

            long offset = slabOffset.getAndAdd(value.length);

            // If slab is full, reset (simple circular buffer approach)
            if (offset + value.length > maxSize) {
                rwLock.readLock().unlock();
                resetSlab();
                rwLock.readLock().lock();
                if (closed) return;
                offset = slabOffset.getAndAdd(value.length);
                if (offset + value.length > maxSize) return;
            }

            // Evict if too many entries — O(1) randomized LRU
            if (index.size() >= maxEntries) {
                evictRandomizedLRU();
            }

            // Copy value to off-heap
            MemorySegment.copy(
                    MemorySegment.ofArray(value), ValueLayout.JAVA_BYTE, 0,
                    slab, ValueLayout.JAVA_BYTE, offset,
                    value.length
            );

            OffHeapEntry entry = new OffHeapEntry(slab, offset, value.length, System.nanoTime());
            index.put(key, entry);

        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Get a value from the off-heap cache.
     */
    public Optional<byte[]> get(String key) {
        if (closed) return Optional.empty();

        OffHeapEntry entry = index.get(key);
        if (entry == null) return Optional.empty();

        rwLock.readLock().lock();
        try {
            if (closed) return Optional.empty();

            byte[] value = new byte[entry.size()];
            MemorySegment.copy(
                    slab, ValueLayout.JAVA_BYTE, entry.offset(),
                    MemorySegment.ofArray(value), ValueLayout.JAVA_BYTE, 0,
                    entry.size()
            );

            // Update access timestamp (LRU touch)
            index.put(key, new OffHeapEntry(entry.segment(), entry.offset(),
                    entry.size(), System.nanoTime()));

            return Optional.of(value);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Remove a key from cache.
     */
    public void remove(String key) {
        index.remove(key);
    }

    /**
     * Clear all cached data.
     */
    public void clear() {
        index.clear();
        slabOffset.set(0);
        keySnapshot = new String[0];
        lastSnapshotSize = 0;
    }

    /**
     * Get number of cached entries.
     */
    public int size() {
        return index.size();
    }

    /**
     * Get approximate memory usage.
     */
    public long memoryUsage() {
        return slabOffset.get();
    }

    /**
     * Randomized LRU eviction — O(1) per eviction, same algorithm as Redis.
     *
     * Algorithm:
     * 1. Pick EVICTION_SAMPLE_SIZE random keys from the index
     * 2. Among those samples, find the one with the oldest timestamp
     * 3. Evict it
     * 4. Repeat EVICTION_BATCH_SIZE times
     *
     * Why this works well:
     * - With 5 samples, we get ~95% of the accuracy of true LRU (proven by Redis team)
     * - Cost is O(K) where K=5, regardless of cache size (1K or 10M entries)
     * - No need for a linked list (which would add memory overhead and contention)
     *
     * @see <a href="https://redis.io/docs/reference/eviction/">Redis Eviction Policies</a>
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

            // Sample K random entries
            int sampled = 0;
            int attempts = 0;
            int maxAttempts = EVICTION_SAMPLE_SIZE * 3; // Guard against stale snapshot

            while (sampled < EVICTION_SAMPLE_SIZE && attempts < maxAttempts) {
                attempts++;
                int idx = rng.nextInt(snapshot.length);
                String candidateKey = snapshot[idx];

                // Key might have been removed since snapshot was taken
                OffHeapEntry entry = index.get(candidateKey);
                if (entry == null) continue;

                sampled++;

                if (entry.timestamp() < oldestTimestamp) {
                    oldestTimestamp = entry.timestamp();
                    oldestKey = candidateKey;
                }
            }

            // Evict the oldest among samples
            if (oldestKey != null) {
                index.remove(oldestKey);
                evicted++;
            } else {
                break; // No valid candidates found
            }
        }
    }

    /**
     * Lazily rebuild the key snapshot array for random access.
     * Only rebuilds when the index size has changed significantly (>20%).
     *
     * The snapshot doesn't need to be perfectly up-to-date —
     * stale entries are handled gracefully in evictRandomizedLRU().
     */
    private void refreshKeySnapshotIfNeeded() {
        long currentSize = index.size();
        long snapshotSize = lastSnapshotSize;

        // Rebuild if size changed by more than 20%, or snapshot is empty
        boolean needsRefresh = (snapshotSize == 0 && currentSize > 0)
                || (snapshotSize > 0 && Math.abs(currentSize - snapshotSize) > snapshotSize * 0.2);

        if (needsRefresh) {
            // This is O(N) but happens infrequently (only on significant size changes)
            keySnapshot = index.keySet().toArray(new String[0]);
            lastSnapshotSize = keySnapshot.length;
        }
    }

    private void resetSlab() {
        rwLock.writeLock().lock();
        try {
            index.clear();
            slabOffset.set(0);
            keySnapshot = new String[0];
            lastSnapshotSize = 0;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            index.clear();
            keySnapshot = new String[0];
            arena.close();
        }
    }
}
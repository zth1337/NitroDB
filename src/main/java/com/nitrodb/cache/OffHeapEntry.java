package com.nitrodb.cache;

import java.lang.foreign.MemorySegment;

/**
 * Metadata for an off-heap cached entry.
 *
 * @param segment   The off-heap memory segment containing the value
 * @param size      Size of the value in bytes
 * @param timestamp Access timestamp for eviction
 */
public record OffHeapEntry(
        MemorySegment segment,
        long offset,
        int size,
        long timestamp
) {}
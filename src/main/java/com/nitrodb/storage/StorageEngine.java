package com.nitrodb.storage;

import java.io.Closeable;
import java.util.Optional;
import java.util.Set;

/**
 * Storage engine interface.
 */
public sealed interface StorageEngine extends Closeable permits BitcaskEngine {

    /**
     * Store a key-value pair.
     */
    void put(String key, byte[] value) throws Exception;

    /**
     * Retrieve value by key.
     */
    Optional<byte[]> get(String key) throws Exception;

    /**
     * Delete a key.
     */
    boolean delete(String key) throws Exception;

    /**
     * Check if key exists.
     */
    boolean exists(String key);

    /**
     * Get all keys.
     */
    Set<String> keys();

    /**
     * Get number of stored keys.
     */
    long size();

    /**
     * Force compaction.
     */
    void compact() throws Exception;

    /**
     * Flush any pending writes.
     */
    void flush() throws Exception;
}
package com.nitrodb.config;

import java.nio.file.Path;

/**
 * Immutable configuration for NitroDB instance.
 * Uses Builder pattern for flexible construction.
 */
public record NitroConfig(
        int port,
        Path dataDirectory,
        long maxDataFileSize,
        long compactionThresholdBytes,
        long compactionIntervalMs,
        long offHeapCacheSize,
        int offHeapCacheMaxEntries,
        boolean syncOnWrite,
        int maxKeySize,
        int maxValueSize
) {

    public static final int DEFAULT_PORT = 6380;
    public static final long DEFAULT_MAX_DATA_FILE_SIZE = 256 * 1024 * 1024L; // 256MB
    public static final long DEFAULT_COMPACTION_THRESHOLD = 128 * 1024 * 1024L; // 128MB
    public static final long DEFAULT_COMPACTION_INTERVAL = 60_000L; // 1 minute
    public static final long DEFAULT_OFF_HEAP_CACHE_SIZE = 512 * 1024 * 1024L; // 512MB
    public static final int DEFAULT_OFF_HEAP_CACHE_MAX_ENTRIES = 1_000_000;
    public static final int DEFAULT_MAX_KEY_SIZE = 1024; // 1KB
    public static final int DEFAULT_MAX_VALUE_SIZE = 64 * 1024 * 1024; // 64MB

    public NitroConfig {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 0 and 65535, got: " + port);
        }
        if (maxDataFileSize <= 0) {
            throw new IllegalArgumentException("Max data file size must be positive");
        }
        if (compactionThresholdBytes <= 0) {
            throw new IllegalArgumentException("Compaction threshold must be positive");
        }
        if (offHeapCacheSize < 0) {
            throw new IllegalArgumentException("Off-heap cache size must be non-negative");
        }
        if (maxKeySize <= 0 || maxValueSize <= 0) {
            throw new IllegalArgumentException("Max key/value sizes must be positive");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static NitroConfig defaults() {
        return builder().build();
    }

    public static final class Builder {
        private int port = DEFAULT_PORT;
        private Path dataDirectory = Path.of("nitrodb-data");
        private long maxDataFileSize = DEFAULT_MAX_DATA_FILE_SIZE;
        private long compactionThresholdBytes = DEFAULT_COMPACTION_THRESHOLD;
        private long compactionIntervalMs = DEFAULT_COMPACTION_INTERVAL;
        private long offHeapCacheSize = DEFAULT_OFF_HEAP_CACHE_SIZE;
        private int offHeapCacheMaxEntries = DEFAULT_OFF_HEAP_CACHE_MAX_ENTRIES;
        private boolean syncOnWrite = false;
        private int maxKeySize = DEFAULT_MAX_KEY_SIZE;
        private int maxValueSize = DEFAULT_MAX_VALUE_SIZE;

        private Builder() {}

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder dataDirectory(Path dir) {
            this.dataDirectory = dir;
            return this;
        }

        public Builder dataDirectory(String dir) {
            this.dataDirectory = Path.of(dir);
            return this;
        }

        public Builder maxDataFileSize(long size) {
            this.maxDataFileSize = size;
            return this;
        }

        public Builder compactionThresholdBytes(long threshold) {
            this.compactionThresholdBytes = threshold;
            return this;
        }

        public Builder compactionIntervalMs(long interval) {
            this.compactionIntervalMs = interval;
            return this;
        }

        public Builder offHeapCacheSize(long size) {
            this.offHeapCacheSize = size;
            return this;
        }

        public Builder offHeapCacheMaxEntries(int max) {
            this.offHeapCacheMaxEntries = max;
            return this;
        }

        public Builder syncOnWrite(boolean sync) {
            this.syncOnWrite = sync;
            return this;
        }

        public Builder maxKeySize(int size) {
            this.maxKeySize = size;
            return this;
        }

        public Builder maxValueSize(int size) {
            this.maxValueSize = size;
            return this;
        }

        public NitroConfig build() {
            return new NitroConfig(
                    port, dataDirectory, maxDataFileSize,
                    compactionThresholdBytes, compactionIntervalMs,
                    offHeapCacheSize, offHeapCacheMaxEntries,
                    syncOnWrite, maxKeySize, maxValueSize
            );
        }
    }
}
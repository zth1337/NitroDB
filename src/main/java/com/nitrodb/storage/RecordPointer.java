package com.nitrodb.storage;

/**
 * Pointer to a record in a data file.
 * Stored in the in-memory KeyDir index.
 *
 * @param fileId    The ID of the data file containing this record
 * @param offset    Byte offset within the data file
 * @param size      Total size of the log entry in bytes
 * @param timestamp Timestamp when the record was written (epoch millis)
 */
public record RecordPointer(
        long fileId,
        long offset,
        int size,
        long timestamp
) {
    public RecordPointer {
        if (fileId < 0) throw new IllegalArgumentException("fileId must be non-negative");
        if (offset < 0) throw new IllegalArgumentException("offset must be non-negative");
        if (size <= 0) throw new IllegalArgumentException("size must be positive");
        if (timestamp < 0) throw new IllegalArgumentException("timestamp must be non-negative");
    }
}
package com.nitrodb.storage;

import com.nitrodb.util.FileUtils;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents a single data file on disk.
 * Supports append-only writes and lock-free random reads.
 *
 * KEY DESIGN: Uses FileChannel.read(ByteBuffer, position) for reads.
 * This method is STATELESS — it does NOT move the file pointer,
 * so 10,000 virtual threads can read from the same file concurrently
 * WITHOUT any synchronization.
 *
 * Writes still go through synchronized append() since they must be serial.
 */
public final class DataFile implements Closeable {

    private final long fileId;
    private final Path path;
    private final FileChannel channel;
    private final AtomicLong writeOffset;
    private volatile boolean closed = false;

    private DataFile(long fileId, Path path, FileChannel channel, long initialOffset) {
        this.fileId = fileId;
        this.path = path;
        this.channel = channel;
        this.writeOffset = new AtomicLong(initialOffset);
    }

    /**
     * Create a new data file (or open existing) for reading AND writing.
     */
    public static DataFile open(Path directory, long fileId) throws IOException {
        FileUtils.ensureDirectory(directory);
        Path path = FileUtils.dataFilePath(directory, fileId);
        FileChannel channel = FileChannel.open(path,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        long size = channel.size();
        channel.position(size); // Position at end for appending
        return new DataFile(fileId, path, channel, size);
    }

    /**
     * Open an existing data file for reading only.
     */
    public static DataFile openReadOnly(Path directory, long fileId) throws IOException {
        Path path = FileUtils.dataFilePath(directory, fileId);
        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
        return new DataFile(fileId, path, channel, channel.size());
    }

    /**
     * Append a log entry to this file.
     * Synchronized because appends must be serialized.
     *
     * @return the offset at which the entry was written
     */
    public synchronized long append(LogEntry entry, boolean fsync) throws IOException {
        ensureOpen();
        byte[] data = entry.toBytes();
        long offset = writeOffset.get();

        ByteBuffer buf = ByteBuffer.wrap(data);
        while (buf.hasRemaining()) {
            channel.write(buf);
        }

        if (fsync) {
            channel.force(false); // metadata sync not needed
        }

        writeOffset.addAndGet(data.length);
        return offset;
    }

    /**
     * Read a log entry at the given offset.
     *
     * LOCK-FREE: Uses FileChannel.read(ByteBuffer, position) which is
     * an absolute (positional) read — it does NOT move the channel's
     * internal position pointer. This means unlimited concurrent readers
     * with ZERO contention. Perfect for 100K virtual threads.
     */
    public LogEntry read(long offset) throws IOException {
        ensureOpen();

        // Step 1: Read header (positional, no lock needed)
        ByteBuffer headerBuf = ByteBuffer.allocate(LogEntry.HEADER_SIZE);
        readFully(headerBuf, offset);
        byte[] header = headerBuf.array();

        int keySize = com.nitrodb.util.ByteUtils.getInt(header, 12);
        int valueSize = com.nitrodb.util.ByteUtils.getInt(header, 16);

        // Step 2: Read key + value body (positional, no lock needed)
        int bodySize = keySize + valueSize;
        byte[] full = new byte[LogEntry.HEADER_SIZE + bodySize];
        System.arraycopy(header, 0, full, 0, LogEntry.HEADER_SIZE);

        if (bodySize > 0) {
            ByteBuffer bodyBuf = ByteBuffer.allocate(bodySize);
            readFully(bodyBuf, offset + LogEntry.HEADER_SIZE);
            System.arraycopy(bodyBuf.array(), 0, full, LogEntry.HEADER_SIZE, bodySize);
        }

        return LogEntry.fromBytes(full);
    }

    /**
     * Read raw bytes at offset. Lock-free positional read.
     */
    public byte[] readBytes(long offset, int size) throws IOException {
        ensureOpen();
        ByteBuffer buf = ByteBuffer.allocate(size);
        readFully(buf, offset);
        return buf.array();
    }

    /**
     * Positional read that guarantees all bytes are read.
     * Includes a Spin-Wait retry mechanism for concurrent read-after-write
     * metadata lag (common on Windows NTFS).
     */
    private void readFully(ByteBuffer buf, long position) throws IOException {
        long pos = position;
        int retries = 0;

        while (buf.hasRemaining()) {
            int bytesRead = channel.read(buf, pos);

            if (bytesRead == -1) {
                if (retries++ < 100) {
                    Thread.yield();
                    continue;
                }
                throw new IOException("Unexpected EOF at position " + pos + " in " + path + " (read -1 bytes after retries)");
            }

            pos += bytesRead;
            retries = 0;
        }
        buf.flip();
    }

    public long getFileId() {
        return fileId;
    }

    public Path getPath() {
        return path;
    }

    public long getWriteOffset() {
        return writeOffset.get();
    }

    public long size() {
        return writeOffset.get();
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("DataFile " + path + " is closed");
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            channel.close();
        }
    }

    @Override
    public String toString() {
        return "DataFile[id=" + fileId + ", path=" + path + ", size=" + writeOffset.get() + "]";
    }
}
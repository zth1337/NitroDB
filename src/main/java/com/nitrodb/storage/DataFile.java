package com.nitrodb.storage;

import com.nitrodb.util.FileUtils;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;

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

    public static DataFile open(Path directory, long fileId) throws IOException {
        FileUtils.ensureDirectory(directory);
        Path path = FileUtils.dataFilePath(directory, fileId);
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        long size = channel.size();
        channel.position(size);
        return new DataFile(fileId, path, channel, size);
    }

    public static DataFile openReadOnly(Path directory, long fileId) throws IOException {
        Path path = FileUtils.dataFilePath(directory, fileId);
        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
        return new DataFile(fileId, path, channel, channel.size());
    }

    public long append(LogEntry entry) throws IOException {
        ensureOpen();
        byte[] data = entry.toBytes();
        long offset = writeOffset.get();
        ByteBuffer buf = ByteBuffer.wrap(data);
        while (buf.hasRemaining()) {
            channel.write(buf);
        }
        writeOffset.addAndGet(data.length);
        return offset;
    }

    public void force() throws IOException {
        channel.force(false);
    }

    public LogEntry read(long offset) throws IOException {
        ensureOpen();
        ByteBuffer headerBuf = ByteBuffer.allocate(LogEntry.HEADER_SIZE);
        readFully(headerBuf, offset);
        byte[] header = headerBuf.array();
        int keySize = com.nitrodb.util.ByteUtils.getInt(header, 12);
        int valueSize = com.nitrodb.util.ByteUtils.getInt(header, 16);
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

    private void readFully(ByteBuffer buf, long position) throws IOException {
        long pos = position;
        while (buf.hasRemaining()) {
            int bytesRead = channel.read(buf, pos);
            if (bytesRead == -1) throw new IOException("Unexpected EOF");
            pos += bytesRead;
        }
        buf.flip();
    }

    public long getFileId() { return fileId; }
    public Path getPath() { return path; }
    public long size() { return writeOffset.get(); }
    private void ensureOpen() throws IOException { if (closed) throw new IOException("Closed"); }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            channel.close();
        }
    }
}
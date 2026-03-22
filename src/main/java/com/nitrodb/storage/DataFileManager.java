package com.nitrodb.storage;

import com.nitrodb.config.NitroConfig;
import com.nitrodb.util.FileUtils;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

public final class DataFileManager implements Closeable {
    private final NitroConfig config;
    private final Path dataDirectory;
    private final AtomicLong fileIdGenerator;
    private final ConcurrentMap<Long, DataFile> readableFiles;
    private volatile DataFile activeFile;

    public DataFileManager(NitroConfig config) throws IOException {
        this.config = config;
        this.dataDirectory = config.dataDirectory();
        this.readableFiles = new ConcurrentHashMap<>();
        FileUtils.ensureDirectory(dataDirectory);
        List<Path> existingFiles = FileUtils.listDataFiles(dataDirectory);
        long maxId = 0;
        for (Path path : existingFiles) {
            long id = FileUtils.extractFileId(path);
            DataFile df = DataFile.openReadOnly(dataDirectory, id);
            readableFiles.put(id, df);
            maxId = Math.max(maxId, id);
        }
        this.fileIdGenerator = new AtomicLong(maxId + 1);
        this.activeFile = createNewActiveFile();
    }

    public synchronized DataFile getActiveFile() throws IOException {
        if (activeFile.size() >= config.maxDataFileSize()) {
            rotateActiveFile();
        }
        return activeFile;
    }

    public synchronized void rotateActiveFile() throws IOException {
        DataFile old = activeFile;
        readableFiles.put(old.getFileId(), old);
        activeFile = createNewActiveFile();
    }

    public DataFile getFile(long fileId) {
        if (activeFile != null && activeFile.getFileId() == fileId) {
            return activeFile;
        }
        return readableFiles.get(fileId);
    }

    public List<Long> getImmutableFileIds() {
        return readableFiles.keySet().stream().sorted().toList();
    }

    public void removeFile(long fileId) throws IOException {
        DataFile df = readableFiles.remove(fileId);
        if (df != null) {
            df.close();
            FileUtils.deleteQuietly(df.getPath());
        }
    }

    public void registerFile(DataFile file) {
        readableFiles.put(file.getFileId(), file);
    }

    public long nextFileId() {
        return fileIdGenerator.getAndIncrement();
    }

    public Path getDataDirectory() {
        return dataDirectory;
    }

    private DataFile createNewActiveFile() throws IOException {
        long id = fileIdGenerator.getAndIncrement();
        return DataFile.open(dataDirectory, id);
    }

    @Override
    public void close() throws IOException {
        if (activeFile != null) activeFile.close();
        for (DataFile df : readableFiles.values()) df.close();
        readableFiles.clear();
    }
}
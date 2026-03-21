package com.nitrodb.util;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * File system utilities.
 */
public final class FileUtils {

    public static final String DATA_FILE_EXTENSION = ".nitro";
    public static final String HINT_FILE_EXTENSION = ".hint";
    public static final String MERGE_FILE_EXTENSION = ".merge";

    private FileUtils() {
        throw new AssertionError("Utility class");
    }

    /**
     * Ensure directory exists, creating it if necessary.
     */
    public static void ensureDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
    }

    /**
     * List data files in directory, sorted by file ID (ascending).
     */
    public static List<Path> listDataFiles(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.toString().endsWith(DATA_FILE_EXTENSION))
                    .sorted(Comparator.comparingLong(FileUtils::extractFileId))
                    .toList();
        }
    }

    /**
     * List hint files in directory.
     */
    public static List<Path> listHintFiles(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.toString().endsWith(HINT_FILE_EXTENSION))
                    .sorted(Comparator.comparingLong(FileUtils::extractFileId))
                    .toList();
        }
    }

    /**
     * Extract numeric file ID from filename like "00000001.nitro".
     */
    public static long extractFileId(Path path) {
        String name = path.getFileName().toString();
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex > 0) {
            name = name.substring(0, dotIndex);
        }
        return Long.parseLong(name);
    }

    /**
     * Build data file path from directory and file ID.
     */
    public static Path dataFilePath(Path dir, long fileId) {
        return dir.resolve(String.format("%010d%s", fileId, DATA_FILE_EXTENSION));
    }

    /**
     * Build hint file path from directory and file ID.
     */
    public static Path hintFilePath(Path dir, long fileId) {
        return dir.resolve(String.format("%010d%s", fileId, HINT_FILE_EXTENSION));
    }

    /**
     * Build merge file path.
     */
    public static Path mergeFilePath(Path dir, long fileId) {
        return dir.resolve(String.format("%010d%s", fileId, MERGE_FILE_EXTENSION));
    }

    /**
     * Safely delete a file, ignoring if not exists.
     */
    public static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best effort
        }
    }

    /**
     * Recursively delete a directory.
     */
    public static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            // Best effort
                        }
                    });
        }
    }
}
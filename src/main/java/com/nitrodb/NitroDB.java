package com.nitrodb;

import com.nitrodb.config.NitroConfig;
import com.nitrodb.server.NitroServer;
import com.nitrodb.storage.BitcaskEngine;
import com.nitrodb.storage.StorageEngine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * NitroDB — Ultra-fast In-Memory/Disk Key-Value Store
 *
 * Can be used as:
 * 1. Standalone server (redis-cli compatible)
 * 2. Embedded library (direct API access)
 *
 * Usage as standalone server:
 *   java -jar nitrodb.jar [port] [data-dir]
 *
 * Usage as embedded library:
 *   var db = NitroDB.open("/path/to/data");
 *   db.put("key", "value".getBytes());
 *   byte[] value = db.get("key").orElseThrow();
 *   db.close();
 */
public final class NitroDB implements AutoCloseable {

    private final NitroConfig config;
    private final StorageEngine storage;
    private NitroServer server;

    private NitroDB(NitroConfig config, StorageEngine storage) {
        this.config = config;
        this.storage = storage;
    }

    /**
     * Open a NitroDB instance for embedded use.
     */
    public static NitroDB open(String dataDirectory) throws IOException {
        return open(NitroConfig.builder().dataDirectory(dataDirectory).build());
    }

    /**
     * Open a NitroDB instance with custom configuration.
     */
    public static NitroDB open(NitroConfig config) throws IOException {
        StorageEngine storage = new BitcaskEngine(config);
        return new NitroDB(config, storage);
    }

    /**
     * Store a key-value pair.
     */
    public void put(String key, byte[] value) throws Exception {
        storage.put(key, value);
    }

    /**
     * Store a string key-value pair.
     */
    public void put(String key, String value) throws Exception {
        storage.put(key, value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Get a value by key.
     */
    public Optional<byte[]> get(String key) throws Exception {
        return storage.get(key);
    }

    /**
     * Get a string value by key.
     */
    public Optional<String> getString(String key) throws Exception {
        return storage.get(key).map(b -> new String(b, java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Delete a key.
     */
    public boolean delete(String key) throws Exception {
        return storage.delete(key);
    }

    /**
     * Check if a key exists.
     */
    public boolean exists(String key) {
        return storage.exists(key);
    }

    /**
     * Get all keys.
     */
    public Set<String> keys() {
        return storage.keys();
    }

    /**
     * Get the number of stored keys.
     */
    public long size() {
        return storage.size();
    }

    /**
     * Force a compaction cycle.
     */
    public void compact() throws Exception {
        storage.compact();
    }

    /**
     * Start the RESP server for redis-cli access.
     */
    public NitroDB startServer() throws IOException {
        if (server != null) {
            throw new IllegalStateException("Server is already running");
        }
        server = new NitroServer(config, storage);
        server.startAsync();
        return this;
    }

    /**
     * Stop the RESP server.
     */
    public void stopServer() throws IOException {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    @Override
    public void close() throws Exception {
        if (server != null) {
            server.close();
        }
        storage.close();
    }

    public static void main(String[] args) throws Exception {
        int port = NitroConfig.DEFAULT_PORT;
        String dataDir = "nitrodb-data";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port", "-p" -> {
                    if (i + 1 < args.length) port = Integer.parseInt(args[++i]);
                }
                case "--dir", "-d" -> {
                    if (i + 1 < args.length) dataDir = args[++i];
                }
                case "--sync" -> {}
                case "--help", "-h" -> {
                    printUsage();
                    return;
                }
                default -> {
                    try {
                        port = Integer.parseInt(args[i]);
                    } catch (NumberFormatException e) {
                        dataDir = args[i];
                    }
                }
            }
        }

        boolean syncOnWrite = java.util.Arrays.asList(args).contains("--sync");

        NitroConfig config = NitroConfig.builder()
                .port(port)
                .dataDirectory(dataDir)
                .syncOnWrite(syncOnWrite)
                .build();

        NitroServer server = new NitroServer(config);
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> {
            try {
                server.close();
            } catch (IOException e) {
                System.err.println("Error during shutdown: " + e.getMessage());
            }
        }));

        server.start();
    }

    private static void printUsage() {
        System.out.println("""
                NitroDB — Ultra-fast In-Memory/Disk Key-Value Store
                
                Usage: java -jar nitrodb.jar [options]
                
                Options:
                  --port, -p <port>    Server port (default: 6380)
                  --dir, -d <path>     Data directory (default: nitrodb-data)
                  --sync               Enable fsync on every write
                  --help, -h           Show this help message
                
                Examples:
                  java -jar nitrodb.jar
                  java -jar nitrodb.jar --port 6379 --dir /var/lib/nitrodb
                  java -jar nitrodb.jar --port 6380 --sync
                
                Connect with redis-cli:
                  redis-cli -p 6380
                """);
    }
}
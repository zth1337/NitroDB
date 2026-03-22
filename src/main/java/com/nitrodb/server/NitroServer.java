package com.nitrodb.server;

import com.nitrodb.cache.OffHeapCache;
import com.nitrodb.command.CommandDispatcher;
import com.nitrodb.config.NitroConfig;
import com.nitrodb.storage.BitcaskEngine;
import com.nitrodb.storage.StorageEngine;

import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * NitroDB TCP server using Virtual Threads (Project Loom).
 *
 * Each client connection gets its own virtual thread.
 * Virtual threads are extremely lightweight (~few KB vs ~1MB for platform threads),
 * allowing us to handle 100,000+ concurrent connections with simple blocking I/O.
 *
 * This is the beauty of Loom: you write straightforward blocking code,
 * but it performs like async NIO under the hood.
 */
public final class NitroServer implements Closeable {

    private final NitroConfig config;
    private final StorageEngine storage;
    private final OffHeapCache cache;
    private final CommandDispatcher dispatcher;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong connectionCount = new AtomicLong(0);
    private final AtomicLong totalCommandsProcessed = new AtomicLong(0);
    private volatile ServerSocket serverSocket;

    public NitroServer(NitroConfig config) throws IOException {
        this.config = config;
        this.storage = new BitcaskEngine(config);
        this.cache = new OffHeapCache(config.offHeapCacheSize(), config.offHeapCacheMaxEntries());
        this.dispatcher = new CommandDispatcher(storage, cache);

        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Constructor for embedding — bring your own storage engine.
     */
    public NitroServer(NitroConfig config, StorageEngine storage) {
        this.config = config;
        this.storage = storage;
        this.cache = new OffHeapCache(config.offHeapCacheSize(), config.offHeapCacheMaxEntries());
        this.dispatcher = new CommandDispatcher(storage, cache);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Start the server and begin accepting connections.
     * This method blocks until the server is shut down.
     */
    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Server is already running");
        }

        serverSocket = new ServerSocket(config.port());
        serverSocket.setReuseAddress(true);

        printBanner();

        System.out.println("[NitroDB] Listening on port " + config.port());
        System.out.println("[NitroDB] Data directory: " + config.dataDirectory().toAbsolutePath());
        System.out.println("[NitroDB] Ready to accept connections.");

        while (running.get()) {
            try {
                Socket clientSocket = serverSocket.accept();
                clientSocket.setTcpNoDelay(true);
                clientSocket.setKeepAlive(true);

                long connId = connectionCount.incrementAndGet();
                executor.submit(new ClientHandler(clientSocket, dispatcher, config));

            } catch (IOException e) {
                if (running.get()) {
                    System.err.println("[NitroDB] Accept error: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Start the server in a background thread.
     */
    public Thread startAsync() {
        Thread serverThread = Thread.ofPlatform()
                .name("nitrodb-main")
                .daemon(false)
                .start(() -> {
                    try {
                        start();
                    } catch (IOException e) {
                        if (running.get()) {
                            System.err.println("[NitroDB] Server error: " + e.getMessage());
                        }
                    }
                });
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        return serverThread;
    }

    /**
     * Stop the server gracefully.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            System.out.println("[NitroDB] Shutting down...");
            try {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    serverSocket.close();
                }
            } catch (IOException e) {
            }
            executor.shutdown();
            System.out.println("[NitroDB] Server stopped. Total connections served: " + connectionCount.get());
        }
    }

    @Override
    public void close() throws IOException {
        stop();
        cache.close();
        storage.close();
    }

    public boolean isRunning() {
        return running.get();
    }

    public long getConnectionCount() {
        return connectionCount.get();
    }

    public StorageEngine getStorage() {
        return storage;
    }

    public NitroConfig getConfig() {
        return config;
    }

    private void printBanner() {
        String banner = """
                
                 ███╗   ██╗██╗████████╗██████╗  ██████╗ ██████╗ ██████╗
                 ████╗  ██║██║╚══██╔══╝██╔══██╗██╔═══██╗██╔══██╗██╔══██╗
                 ██╔██╗ ██║██║   ██║   ██████╔╝██║   ██║██║  ██║██████╔╝
                 ██║╚██╗██║██║   ██║   ██╔══██╗██║   ██║██║  ██║██╔══██╗
                 ██║ ╚████║██║   ██║   ██║  ██║╚██████╔╝██████╔╝██████╔╝
                 ╚═╝  ╚═══╝╚═╝   ╚═╝   ╚═╝  ╚═╝ ╚═════╝ ╚═════╝ ╚═════╝
                
                 Ultra-fast In-Memory/Disk Key-Value Store
                 Java %s | Virtual Threads | Zero Dependencies | Bitcask Engine
                """.formatted(System.getProperty("java.version"));
        System.out.println(banner);
    }
}
package benchmark;

import com.nitrodb.NitroDB;
import com.nitrodb.config.NitroConfig;
import com.nitrodb.util.FileUtils;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Simple standalone benchmark.
 *
 * Run:
 *   mvn compile test-compile
 *   java --enable-preview -cp target/classes:target/test-classes \
 *        com.nitrodb.benchmark.NitroBenchmark
 */
public class NitroBenchmark {

    private static final Path BENCH_DIR = Path.of("bench-data");
    private static final int WARMUP_OPS = 10_000;
    private static final int BENCHMARK_OPS = 100_000;

    public static void main(String[] args) throws Exception {
        FileUtils.deleteRecursively(BENCH_DIR);

        NitroConfig config = NitroConfig.builder()
                .dataDirectory(BENCH_DIR)
                .maxDataFileSize(64 * 1024 * 1024)
                .compactionIntervalMs(999_999_999)
                .syncOnWrite(false)
                .build();

        try (NitroDB db = NitroDB.open(config)) {
            System.out.println("=== NitroDB Benchmark ===\n");

            // Warmup
            System.out.println("Warming up...");
            for (int i = 0; i < WARMUP_OPS; i++) {
                db.put("warmup-" + i, ("value-" + i).getBytes());
            }
            for (int i = 0; i < WARMUP_OPS; i++) {
                db.get("warmup-" + i);
            }

            // Sequential write
            System.out.println("\n--- Sequential Write ---");
            byte[] value = new byte[100];
            ThreadLocalRandom.current().nextBytes(value);

            long start = System.nanoTime();
            for (int i = 0; i < BENCHMARK_OPS; i++) {
                db.put("bench-key-" + i, value);
            }
            long writeTime = System.nanoTime() - start;
            double writeOpsPerSec = BENCHMARK_OPS / (writeTime / 1_000_000_000.0);
            System.out.printf("  %,d ops in %.2f ms%n", BENCHMARK_OPS, writeTime / 1_000_000.0);
            System.out.printf("  %.0f ops/sec%n", writeOpsPerSec);
            System.out.printf("  %.2f us/op%n", writeTime / 1000.0 / BENCHMARK_OPS);

            // Sequential read
            System.out.println("\n--- Sequential Read ---");
            start = System.nanoTime();
            for (int i = 0; i < BENCHMARK_OPS; i++) {
                db.get("bench-key-" + i);
            }
            long readTime = System.nanoTime() - start;
            double readOpsPerSec = BENCHMARK_OPS / (readTime / 1_000_000_000.0);
            System.out.printf("  %,d ops in %.2f ms%n", BENCHMARK_OPS, readTime / 1_000_000.0);
            System.out.printf("  %.0f ops/sec%n", readOpsPerSec);
            System.out.printf("  %.2f us/op%n", readTime / 1000.0 / BENCHMARK_OPS);

            // Random read
            System.out.println("\n--- Random Read ---");
            start = System.nanoTime();
            for (int i = 0; i < BENCHMARK_OPS; i++) {
                int idx = ThreadLocalRandom.current().nextInt(BENCHMARK_OPS);
                db.get("bench-key-" + idx);
            }
            long randomReadTime = System.nanoTime() - start;
            double randomReadOps = BENCHMARK_OPS / (randomReadTime / 1_000_000_000.0);
            System.out.printf("  %,d ops in %.2f ms%n", BENCHMARK_OPS, randomReadTime / 1_000_000.0);
            System.out.printf("  %.0f ops/sec%n", randomReadOps);
            System.out.printf("  %.2f us/op%n", randomReadTime / 1000.0 / BENCHMARK_OPS);

            // Mixed 80/20
            System.out.println("\n--- Mixed (80%% Read / 20%% Write) ---");
            start = System.nanoTime();
            for (int i = 0; i < BENCHMARK_OPS; i++) {
                if (ThreadLocalRandom.current().nextInt(100) < 80) {
                    int idx = ThreadLocalRandom.current().nextInt(BENCHMARK_OPS);
                    db.get("bench-key-" + idx);
                } else {
                    int idx = ThreadLocalRandom.current().nextInt(BENCHMARK_OPS);
                    db.put("bench-key-" + idx, value);
                }
            }
            long mixedTime = System.nanoTime() - start;
            double mixedOps = BENCHMARK_OPS / (mixedTime / 1_000_000_000.0);
            System.out.printf("  %,d ops in %.2f ms%n", BENCHMARK_OPS, mixedTime / 1_000_000.0);
            System.out.printf("  %.0f ops/sec%n", mixedOps);
            System.out.printf("  %.2f us/op%n", mixedTime / 1000.0 / BENCHMARK_OPS);

            // Concurrent
            System.out.println("\n--- Concurrent (10 virtual threads, mixed) ---");
            int numThreads = 10;
            int opsPerThread = BENCHMARK_OPS / numThreads;
            Thread[] threads = new Thread[numThreads];

            start = System.nanoTime();
            for (int t = 0; t < numThreads; t++) {
                threads[t] = Thread.ofVirtual().start(() -> {
                    try {
                        for (int i = 0; i < opsPerThread; i++) {
                            if (ThreadLocalRandom.current().nextInt(100) < 80) {
                                int idx = ThreadLocalRandom.current().nextInt(BENCHMARK_OPS);
                                db.get("bench-key-" + idx);
                            } else {
                                int idx = ThreadLocalRandom.current().nextInt(BENCHMARK_OPS);
                                db.put("bench-key-" + idx, value);
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("Benchmark thread error: " + e.getMessage());
                    }
                });
            }

            for (Thread t : threads) {
                t.join();
            }
            long concurrentTime = System.nanoTime() - start;
            double concurrentOps = BENCHMARK_OPS / (concurrentTime / 1_000_000_000.0);
            System.out.printf("  %,d total ops in %.2f ms%n", BENCHMARK_OPS, concurrentTime / 1_000_000.0);
            System.out.printf("  %.0f ops/sec%n", concurrentOps);
            System.out.printf("  %.2f us/op%n", concurrentTime / 1000.0 / BENCHMARK_OPS);

            // Summary
            System.out.println("\n--- Summary ---");
            System.out.printf("  Total keys in DB: %,d%n", db.size());
            System.out.printf("  Sequential write: %,.0f ops/sec%n", writeOpsPerSec);
            System.out.printf("  Sequential read:  %,.0f ops/sec%n", readOpsPerSec);
            System.out.printf("  Random read:      %,.0f ops/sec%n", randomReadOps);
            System.out.printf("  Mixed r/w:        %,.0f ops/sec%n", mixedOps);
            System.out.printf("  Concurrent:       %,.0f ops/sec%n", concurrentOps);

        } finally {
            FileUtils.deleteRecursively(BENCH_DIR);
        }
    }
}
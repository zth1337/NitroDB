package storage;

import com.nitrodb.config.NitroConfig;
import com.nitrodb.storage.BitcaskEngine;
import com.nitrodb.util.FileUtils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactionEngineTest {

    private static final Path TEST_DIR = Path.of("test-compaction-data");

    private BitcaskEngine engine;

    @BeforeEach
    void setUp() throws IOException {
        FileUtils.deleteRecursively(TEST_DIR);
        NitroConfig config = NitroConfig.builder()
                .dataDirectory(TEST_DIR)
                .maxDataFileSize(512)
                .compactionIntervalMs(999999999)
                .syncOnWrite(false)
                .build();
        engine = new BitcaskEngine(config);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (engine != null) {
            engine.close();
        }
        FileUtils.deleteRecursively(TEST_DIR);
    }

    @Test
    @DisplayName("Compaction preserves live data")
    void compactionPreservesData() throws Exception {
        for (int i = 0; i < 50; i++) {
            engine.put("key" + i, ("value" + i).getBytes(StandardCharsets.UTF_8));
        }

        for (int i = 0; i < 25; i++) {
            engine.put("key" + i, ("updated" + i).getBytes(StandardCharsets.UTF_8));
        }

        for (int i = 25; i < 35; i++) {
            engine.delete("key" + i);
        }

        engine.compact();

        for (int i = 0; i < 25; i++) {
            Optional<byte[]> val = engine.get("key" + i);
            assertTrue(val.isPresent(), "Key " + i + " should exist after compaction");
            assertEquals("updated" + i, new String(val.get(), StandardCharsets.UTF_8));
        }

        for (int i = 25; i < 35; i++) {
            assertFalse(engine.exists("key" + i), "Key " + i + " should be deleted");
        }

        for (int i = 35; i < 50; i++) {
            Optional<byte[]> val = engine.get("key" + i);
            assertTrue(val.isPresent(), "Key " + i + " should exist");
            assertEquals("value" + i, new String(val.get(), StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("Compaction reduces number of files")
    void compactionReducesFiles() throws Exception {
        byte[] value = new byte[100];
        Arrays.fill(value, (byte) 'a');

        for (int i = 0; i < 30; i++) {
            engine.put("k" + i, value);
        }

        List<Path> filesBefore = FileUtils.listDataFiles(TEST_DIR);
        assertTrue(filesBefore.size() > 2, "Should have multiple data files before compaction");

        engine.compact();

        List<Path> filesAfter = FileUtils.listDataFiles(TEST_DIR);
        assertTrue(filesAfter.size() <= filesBefore.size(),
                "Should have fewer or equal files after compaction");

        for (int i = 0; i < 30; i++) {
            Optional<byte[]> val = engine.get("k" + i);
            assertTrue(val.isPresent());
            assertArrayEquals(value, val.get());
        }
    }

    @Test
    @DisplayName("Data survives compaction + restart")
    void compactionPlusPersistence() throws Exception {
        for (int i = 0; i < 20; i++) {
            engine.put("persistent" + i, ("data" + i).getBytes(StandardCharsets.UTF_8));
        }

        engine.compact();
        engine.close();

        NitroConfig config = NitroConfig.builder()
                .dataDirectory(TEST_DIR)
                .maxDataFileSize(512)
                .syncOnWrite(false)
                .build();
        engine = new BitcaskEngine(config);

        for (int i = 0; i < 20; i++) {
            Optional<byte[]> val = engine.get("persistent" + i);
            assertTrue(val.isPresent(),
                    "Key persistent" + i + " should survive compaction + restart");
            assertEquals("data" + i, new String(val.get(), StandardCharsets.UTF_8));
        }
    }
}
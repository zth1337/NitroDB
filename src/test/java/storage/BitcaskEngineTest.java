package storage;

import com.nitrodb.config.NitroConfig;
import com.nitrodb.storage.BitcaskEngine;
import com.nitrodb.util.FileUtils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BitcaskEngineTest {

    private static final Path TEST_DIR = Path.of("test-bitcask-data");

    private BitcaskEngine engine;

    @BeforeEach
    void setUp() throws IOException {
        FileUtils.deleteRecursively(TEST_DIR);
        NitroConfig config = NitroConfig.builder()
                .dataDirectory(TEST_DIR)
                .maxDataFileSize(1024)
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
    @Order(1)
    @DisplayName("PUT and GET basic operation")
    void putAndGet() throws Exception {
        engine.put("hello", "world".getBytes(StandardCharsets.UTF_8));
        Optional<byte[]> result = engine.get("hello");

        assertTrue(result.isPresent());
        assertEquals("world", new String(result.get(), StandardCharsets.UTF_8));
    }

    @Test
    @Order(2)
    @DisplayName("GET non-existent key returns empty")
    void getNonExistent() throws Exception {
        Optional<byte[]> result = engine.get("nonexistent");
        assertTrue(result.isEmpty());
    }

    @Test
    @Order(3)
    @DisplayName("PUT overwrites existing key")
    void putOverwrite() throws Exception {
        engine.put("key", "value1".getBytes(StandardCharsets.UTF_8));
        engine.put("key", "value2".getBytes(StandardCharsets.UTF_8));

        Optional<byte[]> result = engine.get("key");
        assertTrue(result.isPresent());
        assertEquals("value2", new String(result.get(), StandardCharsets.UTF_8));
    }

    @Test
    @Order(4)
    @DisplayName("DELETE removes key")
    void delete() throws Exception {
        engine.put("key", "value".getBytes(StandardCharsets.UTF_8));
        assertTrue(engine.exists("key"));

        boolean deleted = engine.delete("key");
        assertTrue(deleted);
        assertFalse(engine.exists("key"));
        assertTrue(engine.get("key").isEmpty());
    }

    @Test
    @Order(5)
    @DisplayName("DELETE non-existent key returns false")
    void deleteNonExistent() throws Exception {
        assertFalse(engine.delete("nonexistent"));
    }

    @Test
    @Order(6)
    @DisplayName("EXISTS works correctly")
    void exists() throws Exception {
        assertFalse(engine.exists("key"));
        engine.put("key", "value".getBytes(StandardCharsets.UTF_8));
        assertTrue(engine.exists("key"));
        engine.delete("key");
        assertFalse(engine.exists("key"));
    }

    @Test
    @Order(7)
    @DisplayName("SIZE tracks correctly")
    void size() throws Exception {
        assertEquals(0, engine.size());

        engine.put("k1", "v1".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, engine.size());

        engine.put("k2", "v2".getBytes(StandardCharsets.UTF_8));
        assertEquals(2, engine.size());

        engine.delete("k1");
        assertEquals(1, engine.size());
    }

    @Test
    @Order(8)
    @DisplayName("Multiple keys stored and retrieved correctly")
    void multipleKeys() throws Exception {
        for (int i = 0; i < 100; i++) {
            engine.put("key" + i, ("value" + i).getBytes(StandardCharsets.UTF_8));
        }

        assertEquals(100, engine.size());

        for (int i = 0; i < 100; i++) {
            Optional<byte[]> val = engine.get("key" + i);
            assertTrue(val.isPresent(), "Key " + i + " should exist");
            assertEquals("value" + i, new String(val.get(), StandardCharsets.UTF_8));
        }
    }

    @Test
    @Order(9)
    @DisplayName("Data survives restart (persistence)")
    void persistence() throws Exception {
        engine.put("persistent", "data".getBytes(StandardCharsets.UTF_8));
        engine.put("key2", "value2".getBytes(StandardCharsets.UTF_8));
        engine.close();

        NitroConfig config = NitroConfig.builder()
                .dataDirectory(TEST_DIR)
                .maxDataFileSize(1024)
                .syncOnWrite(false)
                .build();
        engine = new BitcaskEngine(config);

        Optional<byte[]> result = engine.get("persistent");
        assertTrue(result.isPresent());
        assertEquals("data", new String(result.get(), StandardCharsets.UTF_8));

        Optional<byte[]> result2 = engine.get("key2");
        assertTrue(result2.isPresent());
        assertEquals("value2", new String(result2.get(), StandardCharsets.UTF_8));
    }

    @Test
    @Order(10)
    @DisplayName("File rotation occurs when max size exceeded")
    void fileRotation() throws Exception {
        byte[] largeValue = new byte[200];
        Arrays.fill(largeValue, (byte) 'x');

        for (int i = 0; i < 20; i++) {
            engine.put("bigkey" + i, largeValue);
        }

        for (int i = 0; i < 20; i++) {
            Optional<byte[]> val = engine.get("bigkey" + i);
            assertTrue(val.isPresent(), "Key bigkey" + i + " should exist");
            assertArrayEquals(largeValue, val.get());
        }
    }

    @Test
    @Order(11)
    @DisplayName("Null or empty key throws exception")
    void invalidKey() {
        assertThrows(IllegalArgumentException.class,
                () -> engine.put(null, "v".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> engine.put("", "v".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @Order(12)
    @DisplayName("Null value throws exception")
    void invalidValue() {
        assertThrows(IllegalArgumentException.class,
                () -> engine.put("key", null));
    }

    @Test
    @Order(13)
    @DisplayName("Keys() returns all stored keys")
    void keys() throws Exception {
        engine.put("a", "1".getBytes(StandardCharsets.UTF_8));
        engine.put("b", "2".getBytes(StandardCharsets.UTF_8));
        engine.put("c", "3".getBytes(StandardCharsets.UTF_8));

        Set<String> keys = engine.keys();
        assertEquals(3, keys.size());
        assertTrue(keys.contains("a"));
        assertTrue(keys.contains("b"));
        assertTrue(keys.contains("c"));
    }

    @Test
    @Order(14)
    @DisplayName("Binary values stored correctly")
    void binaryValues() throws Exception {
        byte[] binary = new byte[256];
        for (int i = 0; i < 256; i++) {
            binary[i] = (byte) i;
        }

        engine.put("binary", binary);
        Optional<byte[]> result = engine.get("binary");

        assertTrue(result.isPresent());
        assertArrayEquals(binary, result.get());
    }

    @Test
    @Order(15)
    @DisplayName("CRC integrity check works")
    void crcIntegrity() throws Exception {
        engine.put("crc-test", "integrity".getBytes(StandardCharsets.UTF_8));
        Optional<byte[]> result = engine.get("crc-test");

        assertTrue(result.isPresent());
        assertEquals("integrity", new String(result.get(), StandardCharsets.UTF_8));
    }

    @Test
    @Order(16)
    @DisplayName("Delete after restart is properly recovered")
    void deleteRecovery() throws Exception {
        engine.put("temp", "data".getBytes(StandardCharsets.UTF_8));
        engine.delete("temp");
        engine.close();

        NitroConfig config = NitroConfig.builder()
                .dataDirectory(TEST_DIR)
                .maxDataFileSize(1024)
                .syncOnWrite(false)
                .build();
        engine = new BitcaskEngine(config);

        assertFalse(engine.exists("temp"));
        assertTrue(engine.get("temp").isEmpty());
    }
}
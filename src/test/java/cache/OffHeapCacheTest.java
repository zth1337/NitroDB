package cache;

import com.nitrodb.cache.OffHeapCache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OffHeapCacheTest {

    private OffHeapCache cache;

    @BeforeEach
    void setUp() {
        cache = new OffHeapCache(1024 * 1024, 1000);
    }

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    @Test
    @DisplayName("Put and get value from off-heap cache")
    void putAndGet() {
        byte[] value = "hello world".getBytes(StandardCharsets.UTF_8);
        cache.put("key", value);

        Optional<byte[]> result = cache.get("key");
        assertTrue(result.isPresent());
        assertArrayEquals(value, result.get());
    }

    @Test
    @DisplayName("Get non-existent key returns empty")
    void getNonExistent() {
        Optional<byte[]> result = cache.get("nonexistent");
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Remove key from cache")
    void remove() {
        cache.put("key", "value".getBytes(StandardCharsets.UTF_8));
        assertTrue(cache.get("key").isPresent());

        cache.remove("key");
        assertTrue(cache.get("key").isEmpty());
    }

    @Test
    @DisplayName("Multiple entries stored correctly")
    void multipleEntries() {
        for (int i = 0; i < 100; i++) {
            cache.put("key" + i, ("value" + i).getBytes(StandardCharsets.UTF_8));
        }

        assertEquals(100, cache.size());

        for (int i = 0; i < 100; i++) {
            Optional<byte[]> val = cache.get("key" + i);
            assertTrue(val.isPresent());
            assertEquals("value" + i, new String(val.get(), StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("Cache handles binary data")
    void binaryData() {
        byte[] binary = new byte[]{0, 1, 2, (byte) 255, (byte) 128, 0};
        cache.put("binary", binary);

        Optional<byte[]> result = cache.get("binary");
        assertTrue(result.isPresent());
        assertArrayEquals(binary, result.get());
    }

    @Test
    @DisplayName("Clear removes all entries")
    void clear() {
        cache.put("a", "1".getBytes(StandardCharsets.UTF_8));
        cache.put("b", "2".getBytes(StandardCharsets.UTF_8));
        assertEquals(2, cache.size());

        cache.clear();
        assertEquals(0, cache.size());
    }

    @Test
    @DisplayName("Memory usage increases with entries")
    void memoryUsage() {
        long before = cache.memoryUsage();
        cache.put("key", new byte[1000]);
        long after = cache.memoryUsage();
        assertTrue(after > before);
    }

    @Test
    @DisplayName("Cache works after close and reopen")
    void closeAndReopen() {
        cache.put("key", "value".getBytes(StandardCharsets.UTF_8));
        cache.close();

        cache = new OffHeapCache(1024 * 1024, 1000);
        assertTrue(cache.get("key").isEmpty());
    }

    @Test
    @DisplayName("Eviction happens when max entries reached")
    void eviction() {
        OffHeapCache smallCache = new OffHeapCache(1024 * 1024, 50);
        try {
            for (int i = 0; i < 100; i++) {
                smallCache.put("evict-key-" + i, ("val" + i).getBytes(StandardCharsets.UTF_8));
            }
            // Size should not exceed maxEntries by much
            assertTrue(smallCache.size() <= 60,
                    "Cache size should be near maxEntries after eviction, got: " + smallCache.size());
        } finally {
            smallCache.close();
        }
    }
}
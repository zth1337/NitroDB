package storage;

import com.nitrodb.storage.KeyDir;
import com.nitrodb.storage.RecordPointer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyDirTest {

    private KeyDir keyDir;

    @BeforeEach
    void setUp() {
        keyDir = new KeyDir();
    }

    @Test
    @DisplayName("Put and get pointer")
    void putAndGet() {
        RecordPointer ptr = new RecordPointer(1, 0, 100, System.currentTimeMillis());
        keyDir.put("key", ptr);

        RecordPointer result = keyDir.get("key");
        assertNotNull(result);
        assertEquals(1, result.fileId());
        assertEquals(0, result.offset());
        assertEquals(100, result.size());
    }

    @Test
    @DisplayName("Get non-existent returns null")
    void getNonExistent() {
        assertNull(keyDir.get("nonexistent"));
    }

    @Test
    @DisplayName("Put overwrites and returns previous")
    void putOverwrite() {
        RecordPointer ptr1 = new RecordPointer(1, 0, 100, 1000);
        RecordPointer ptr2 = new RecordPointer(2, 200, 150, 2000);

        assertNull(keyDir.put("key", ptr1));
        RecordPointer prev = keyDir.put("key", ptr2);

        assertNotNull(prev);
        assertEquals(1, prev.fileId());
        assertEquals(2, keyDir.get("key").fileId());
    }

    @Test
    @DisplayName("Remove works correctly")
    void remove() {
        RecordPointer ptr = new RecordPointer(1, 0, 100, System.currentTimeMillis());
        keyDir.put("key", ptr);
        assertEquals(1, keyDir.size());

        RecordPointer removed = keyDir.remove("key");
        assertNotNull(removed);
        assertEquals(0, keyDir.size());
        assertFalse(keyDir.containsKey("key"));
    }

    @Test
    @DisplayName("Size tracks correctly")
    void sizeTracking() {
        assertEquals(0, keyDir.size());

        keyDir.put("a", new RecordPointer(1, 0, 10, 1));
        keyDir.put("b", new RecordPointer(1, 10, 10, 2));
        assertEquals(2, keyDir.size());

        keyDir.put("a", new RecordPointer(1, 20, 10, 3));
        assertEquals(2, keyDir.size());

        keyDir.remove("a");
        assertEquals(1, keyDir.size());
    }

    @Test
    @DisplayName("PutIfNewer only updates when timestamp is newer")
    void putIfNewer() {
        RecordPointer older = new RecordPointer(1, 0, 100, 1000);
        RecordPointer newer = new RecordPointer(2, 200, 150, 2000);

        keyDir.putIfNewer("key", newer);
        assertEquals(2, keyDir.get("key").fileId());

        keyDir.putIfNewer("key", older);
        assertEquals(2, keyDir.get("key").fileId());

        RecordPointer newest = new RecordPointer(3, 300, 200, 3000);
        keyDir.putIfNewer("key", newest);
        assertEquals(3, keyDir.get("key").fileId());
    }

    @Test
    @DisplayName("ContainsKey works")
    void containsKey() {
        assertFalse(keyDir.containsKey("key"));
        keyDir.put("key", new RecordPointer(1, 0, 10, 1));
        assertTrue(keyDir.containsKey("key"));
    }

    @Test
    @DisplayName("Keys returns all keys")
    void keys() {
        keyDir.put("a", new RecordPointer(1, 0, 10, 1));
        keyDir.put("b", new RecordPointer(1, 10, 10, 2));
        keyDir.put("c", new RecordPointer(1, 20, 10, 3));

        Set<String> keys = keyDir.keys();
        assertEquals(3, keys.size());
        assertTrue(keys.contains("a"));
        assertTrue(keys.contains("b"));
        assertTrue(keys.contains("c"));
    }

    @Test
    @DisplayName("Clear empties the index")
    void clear() {
        keyDir.put("a", new RecordPointer(1, 0, 10, 1));
        keyDir.put("b", new RecordPointer(1, 10, 10, 2));

        keyDir.clear();
        assertEquals(0, keyDir.size());
        assertFalse(keyDir.containsKey("a"));
        assertFalse(keyDir.containsKey("b"));
    }

    @Test
    @DisplayName("Concurrent access is safe")
    void concurrentAccess() throws InterruptedException {
        int threads = 10;
        int keysPerThread = 1000;
        Thread[] threadArray = new Thread[threads];

        for (int t = 0; t < threads; t++) {
            int threadId = t;
            threadArray[t] = Thread.ofVirtual().start(() -> {
                for (int i = 0; i < keysPerThread; i++) {
                    String key = "t" + threadId + "_k" + i;
                    keyDir.put(key, new RecordPointer(
                            threadId, (long) i * 100, 100, System.currentTimeMillis()));
                }
            });
        }

        for (Thread thread : threadArray) {
            thread.join();
        }

        assertEquals((long) threads * keysPerThread, keyDir.size());
    }
}
package server;

import com.nitrodb.config.NitroConfig;
import com.nitrodb.server.NitroServer;
import com.nitrodb.util.FileUtils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests that start the actual NitroDB server
 * and communicate via RESP protocol (like redis-cli would).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NitroServerIntegrationTest {

    private static final Path TEST_DIR = Path.of("test-server-data");
    private static final int TEST_PORT = 16380;

    private NitroServer server;
    private Thread serverThread;

    @BeforeEach
    void setUp() throws Exception {
        FileUtils.deleteRecursively(TEST_DIR);
        NitroConfig config = NitroConfig.builder()
                .port(TEST_PORT)
                .dataDirectory(TEST_DIR)
                .syncOnWrite(false)
                .build();
        server = new NitroServer(config);
        serverThread = server.startAsync();
        Thread.sleep(300);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.close();
        }
        if (serverThread != null) {
            serverThread.interrupt();
        }
        Thread.sleep(100);
        FileUtils.deleteRecursively(TEST_DIR);
    }

    @Test
    @Order(1)
    @DisplayName("PING returns PONG")
    void ping() throws Exception {
        String response = sendCommand("*1\r\n$4\r\nPING\r\n");
        assertEquals("+PONG\r\n", response);
    }

    @Test
    @Order(2)
    @DisplayName("SET and GET")
    void setAndGet() throws Exception {
        String setResponse = sendCommand(
                "*3\r\n$3\r\nSET\r\n$5\r\nmykey\r\n$7\r\nmyvalue\r\n"
        );
        assertEquals("+OK\r\n", setResponse);

        String getResponse = sendCommand(
                "*2\r\n$3\r\nGET\r\n$5\r\nmykey\r\n"
        );
        assertEquals("$7\r\nmyvalue\r\n", getResponse);
    }

    @Test
    @Order(3)
    @DisplayName("GET non-existent key returns nil")
    void getNonExistent() throws Exception {
        String response = sendCommand(
                "*2\r\n$3\r\nGET\r\n$11\r\nnonexistent\r\n"
        );
        assertEquals("$-1\r\n", response);
    }

    @Test
    @Order(4)
    @DisplayName("DEL removes key")
    void del() throws Exception {
        sendCommand("*3\r\n$3\r\nSET\r\n$5\r\ndelme\r\n$5\r\nvalue\r\n");

        String delResponse = sendCommand(
                "*2\r\n$3\r\nDEL\r\n$5\r\ndelme\r\n"
        );
        assertEquals(":1\r\n", delResponse);

        String getResponse = sendCommand(
                "*2\r\n$3\r\nGET\r\n$5\r\ndelme\r\n"
        );
        assertEquals("$-1\r\n", getResponse);
    }

    @Test
    @Order(5)
    @DisplayName("EXISTS command")
    void exists() throws Exception {
        String notExists = sendCommand(
                "*2\r\n$6\r\nEXISTS\r\n$3\r\nfoo\r\n"
        );
        assertEquals(":0\r\n", notExists);

        sendCommand("*3\r\n$3\r\nSET\r\n$3\r\nfoo\r\n$3\r\nbar\r\n");

        String doesExist = sendCommand(
                "*2\r\n$6\r\nEXISTS\r\n$3\r\nfoo\r\n"
        );
        assertEquals(":1\r\n", doesExist);
    }

    @Test
    @Order(6)
    @DisplayName("DBSIZE command")
    void dbSize() throws Exception {
        sendCommand("*3\r\n$3\r\nSET\r\n$2\r\nk1\r\n$2\r\nv1\r\n");
        sendCommand("*3\r\n$3\r\nSET\r\n$2\r\nk2\r\n$2\r\nv2\r\n");

        String response = sendCommand("*1\r\n$6\r\nDBSIZE\r\n");
        assertTrue(response.startsWith(":"));
    }

    @Test
    @Order(7)
    @DisplayName("ECHO command")
    void echo() throws Exception {
        String response = sendCommand(
                "*2\r\n$4\r\nECHO\r\n$11\r\nhello world\r\n"
        );
        assertEquals("$11\r\nhello world\r\n", response);
    }

    @Test
    @Order(8)
    @DisplayName("INCR command")
    void incr() throws Exception {
        sendCommand("*3\r\n$3\r\nSET\r\n$7\r\ncounter\r\n$1\r\n0\r\n");

        String response1 = sendCommand("*2\r\n$4\r\nINCR\r\n$7\r\ncounter\r\n");
        assertEquals(":1\r\n", response1);

        String response2 = sendCommand("*2\r\n$4\r\nINCR\r\n$7\r\ncounter\r\n");
        assertEquals(":2\r\n", response2);
    }

    @Test
    @Order(9)
    @DisplayName("Multiple concurrent clients")
    void concurrentClients() throws Exception {
        int numClients = 50;
        Thread[] threads = new Thread[numClients];
        boolean[] results = new boolean[numClients];

        for (int i = 0; i < numClients; i++) {
            int clientId = i;
            threads[i] = Thread.ofVirtual().start(() -> {
                try {
                    String key = "client" + clientId;
                    String val = "value" + clientId;
                    String setResp = sendCommand(
                            "*3\r\n$3\r\nSET\r\n$" + key.length() +
                                    "\r\n" + key + "\r\n$" + val.length() +
                                    "\r\n" + val + "\r\n"
                    );
                    results[clientId] = "+OK\r\n".equals(setResp);
                } catch (Exception e) {
                    results[clientId] = false;
                }
            });
        }

        for (Thread t : threads) {
            t.join(5000);
        }

        int successCount = 0;
        for (boolean r : results) {
            if (r) successCount++;
        }
        assertTrue(successCount >= numClients * 0.9,
                "At least 90% of concurrent clients should succeed, got: " + successCount);
    }

    /**
     * Send a RESP command and read the response.
     */
    private String sendCommand(String respCommand) throws IOException {
        try (Socket socket = new Socket("localhost", TEST_PORT)) {
            socket.setSoTimeout(5000);

            OutputStream out = socket.getOutputStream();
            out.write(respCommand.getBytes(StandardCharsets.UTF_8));
            out.flush();

            InputStream in = new BufferedInputStream(socket.getInputStream());
            return readResponse(in);
        }
    }

    /**
     * Read a complete RESP response.
     */
    private String readResponse(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int firstByte = in.read();
        if (firstByte == -1) return "";

        char type = (char) firstByte;
        sb.append(type);

        switch (type) {
            case '+', '-', ':' -> {
                sb.append(readLine(in));
                sb.append("\r\n");
            }
            case '$' -> {
                String lengthStr = readLine(in);
                sb.append(lengthStr).append("\r\n");
                int length = Integer.parseInt(lengthStr);
                if (length >= 0) {
                    byte[] data = new byte[length];
                    int totalRead = 0;
                    while (totalRead < length) {
                        int read = in.read(data, totalRead, length - totalRead);
                        if (read == -1) break;
                        totalRead += read;
                    }
                    sb.append(new String(data, StandardCharsets.UTF_8));
                    in.read(); // \r
                    in.read(); // \n
                    sb.append("\r\n");
                }
            }
            case '*' -> {
                String countStr = readLine(in);
                sb.append(countStr).append("\r\n");
            }
        }

        return sb.toString();
    }

    private String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b == -1 || b == '\r') {
                in.read(); // consume \n
                break;
            }
            line.append((char) b);
        }
        return line.toString();
    }
}
package com.nitrodb.protocol;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * Parser for the RESP (REdis Serialization Protocol).
 *
 * Supports RESP2 protocol:
 * - Simple Strings (+OK\r\n)
 * - Errors (-ERR message\r\n)
 * - Integers (:1\r\n)
 * - Bulk Strings ($5\r\nhello\r\n)
 * - Arrays (*2\r\n$3\r\nfoo\r\n$3\r\nbar\r\n)
 *
 * Also supports inline commands (e.g., "PING\r\n") for redis-cli compatibility.
 */
public final class RespParser {

    private final InputStream input;
    private final byte[] lineBuf = new byte[65536];

    public RespParser(InputStream input) {
        this.input = input;
    }

    /**
     * Parse the next RESP value from the stream.
     *
     * @return parsed RespType, or null if stream is closed
     */
    public RespType parse() throws IOException {
        int b = input.read();
        if (b == -1) return null;

        return switch (b) {
            case '+' -> parseSimpleString();
            case '-' -> parseError();
            case ':' -> parseInteger();
            case '$' -> parseBulkString();
            case '*' -> parseArray();
            default -> parseInlineCommand((byte) b);
        };
    }

    private RespType.SimpleString parseSimpleString() throws IOException {
        return new RespType.SimpleString(readLine());
    }

    private RespType.Error parseError() throws IOException {
        return new RespType.Error(readLine());
    }

    private RespType.Integer parseInteger() throws IOException {
        return new RespType.Integer(Long.parseLong(readLine()));
    }

    private RespType.BulkString parseBulkString() throws IOException {
        int length = java.lang.Integer.parseInt(readLine());
        if (length == -1) {
            return new RespType.BulkString(null);
        }
        if (length < 0) {
            throw new IOException("Invalid bulk string length: " + length);
        }

        byte[] data = new byte[length];
        int totalRead = 0;
        while (totalRead < length) {
            int read = input.read(data, totalRead, length - totalRead);
            if (read == -1) throw new IOException("Unexpected EOF reading bulk string");
            totalRead += read;
        }

        int cr = input.read();
        int lf = input.read();
        if (cr != '\r' || lf != '\n') {
            throw new IOException("Expected \\r\\n after bulk string data");
        }

        return new RespType.BulkString(data);
    }

    private RespType.Array parseArray() throws IOException {
        int count = java.lang.Integer.parseInt(readLine());
        if (count == -1) {
            return new RespType.Array(null);
        }
        if (count < 0) {
            throw new IOException("Invalid array length: " + count);
        }

        RespType[] elements = new RespType[count];
        for (int i = 0; i < count; i++) {
            elements[i] = parse();
            if (elements[i] == null) {
                throw new IOException("Unexpected end of stream in array");
            }
        }

        return new RespType.Array(elements);
    }

    /**
     * Parse an inline command (not RESP-formatted).
     * Used for simple commands like "PING" typed in telnet/redis-cli.
     */
    private RespType parseInlineCommand(byte firstByte) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append((char) firstByte);

        while (true) {
            int b = input.read();
            if (b == -1) break;
            if (b == '\n') break;
            if (b == '\r') {
                int next = input.read(); // consume \n
                break;
            }
            sb.append((char) b);
        }

        String line = sb.toString().trim();
        if (line.isEmpty()) {
            return null;
        }

        String[] parts = line.split("\\s+");
        RespType[] elements = new RespType[parts.length];
        for (int i = 0; i < parts.length; i++) {
            elements[i] = new RespType.BulkString(parts[i].getBytes(StandardCharsets.UTF_8));
        }

        return new RespType.Array(elements);
    }

    /**
     * Read a line terminated by \r\n.
     */
    private String readLine() throws IOException {
        int idx = 0;
        while (true) {
            int b = input.read();
            if (b == -1) throw new IOException("Unexpected end of stream");
            if (b == '\r') {
                int next = input.read();
                if (next == '\n') break;
                throw new IOException("Expected \\n after \\r");
            }
            if (idx >= lineBuf.length) {
                throw new IOException("Line too long");
            }
            lineBuf[idx++] = (byte) b;
        }
        return new String(lineBuf, 0, idx, StandardCharsets.UTF_8);
    }
}
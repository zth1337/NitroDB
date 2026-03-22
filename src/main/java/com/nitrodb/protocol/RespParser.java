package com.nitrodb.protocol;

import java.io.*;
import java.nio.charset.StandardCharsets;

public final class RespParser {
    private final InputStream input;
    private final int maxPayloadSize;

    public RespParser(InputStream input, int maxPayloadSize) {
        this.input = input;
        this.maxPayloadSize = maxPayloadSize;
    }

    public RespType parse() throws IOException {
        int b = input.read();
        if (b == -1) return null;
        return switch (b) {
            case '+' -> new RespType.SimpleString(readLine());
            case '-' -> new RespType.Error(readLine());
            case ':' -> new RespType.Integer(readLineAsLong());
            case '$' -> parseBulkString();
            case '*' -> parseArray();
            default -> null;
        };
    }

    private RespType.BulkString parseBulkString() throws IOException {
        int length = (int) readLineAsLong();
        if (length == -1) return new RespType.BulkString(null);
        if (length > maxPayloadSize) {
            throw new IOException("Payload too large: " + length);
        }
        byte[] data = new byte[length];
        int read = 0;
        while (read < length) {
            int n = input.read(data, read, length - read);
            if (n == -1) throw new IOException("EOF");
            read += n;
        }
        input.read(); input.read();
        return new RespType.BulkString(data);
    }

    private long readLineAsLong() throws IOException {
        long value = 0;
        boolean negative = false;
        while (true) {
            int b = input.read();
            if (b == -1) throw new IOException("EOF");
            if (b == '\r') { input.read(); break; }
            if (b == '-') negative = true;
            else value = value * 10 + (b - '0');
        }
        return negative ? -value : value;
    }

    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int b = input.read();
            if (b == -1 || b == '\r') { if (b == '\r') input.read(); break; }
            sb.append((char) b);
        }
        return sb.toString();
    }

    private RespType.Array parseArray() throws IOException {
        int count = (int) readLineAsLong();
        if (count == -1) return new RespType.Array(null);
        RespType[] elements = new RespType[count];
        for (int i = 0; i < count; i++) elements[i] = parse();
        return new RespType.Array(elements);
    }
}
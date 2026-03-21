package com.nitrodb.protocol;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * Writes RESP-encoded responses to an output stream.
 */
public final class RespWriter {

    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NULL_BULK = "$-1\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NULL_ARRAY = "*-1\r\n".getBytes(StandardCharsets.US_ASCII);

    private final OutputStream output;

    public RespWriter(OutputStream output) {
        this.output = output;
    }

    /**
     * Write any RespType value.
     */
    public void write(RespType value) throws IOException {
        switch (value) {
            case RespType.SimpleString ss -> writeSimpleString(ss.value());
            case RespType.Error err -> writeError(err.message());
            case RespType.Integer i -> writeInteger(i.value());
            case RespType.BulkString bs -> {
                if (bs.isNull()) writeNull();
                else writeBulkString(bs.data());
            }
            case RespType.Array arr -> {
                if (arr.isNull()) writeNullArray();
                else writeArray(arr.elements());
            }
        }
        output.flush();
    }

    public void writeSimpleString(String value) throws IOException {
        output.write('+');
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.write(CRLF);
    }

    public void writeError(String message) throws IOException {
        output.write('-');
        output.write(message.getBytes(StandardCharsets.UTF_8));
        output.write(CRLF);
    }

    public void writeInteger(long value) throws IOException {
        output.write(':');
        output.write(Long.toString(value).getBytes(StandardCharsets.US_ASCII));
        output.write(CRLF);
    }

    public void writeBulkString(byte[] data) throws IOException {
        output.write('$');
        output.write(java.lang.Integer.toString(data.length).getBytes(StandardCharsets.US_ASCII));
        output.write(CRLF);
        output.write(data);
        output.write(CRLF);
    }

    public void writeBulkString(String value) throws IOException {
        writeBulkString(value.getBytes(StandardCharsets.UTF_8));
    }

    public void writeNull() throws IOException {
        output.write(NULL_BULK);
    }

    public void writeNullArray() throws IOException {
        output.write(NULL_ARRAY);
    }

    public void writeArray(RespType[] elements) throws IOException {
        output.write('*');
        output.write(java.lang.Integer.toString(elements.length).getBytes(StandardCharsets.US_ASCII));
        output.write(CRLF);
        for (RespType element : elements) {
            write(element);
        }
    }

    public void flush() throws IOException {
        output.flush();
    }
}
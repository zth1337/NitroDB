package com.nitrodb.protocol;

/**
 * RESP data types.
 *
 * RESP protocol prefixes:
 *   + Simple String
 *   - Error
 *   : Integer
 *   $ Bulk String
 *   * Array
 */
public sealed interface RespType {

    record SimpleString(String value) implements RespType {}

    record Error(String message) implements RespType {}

    record Integer(long value) implements RespType {}

    record BulkString(byte[] data) implements RespType {
        public String asString() {
            return data == null ? null : new String(data, java.nio.charset.StandardCharsets.UTF_8);
        }

        public boolean isNull() {
            return data == null;
        }
    }

    record Array(RespType[] elements) implements RespType {
        public boolean isNull() {
            return elements == null;
        }

        public int size() {
            return elements == null ? 0 : elements.length;
        }
    }

    /**
     * Null bulk string singleton.
     */
    RespType NULL_BULK_STRING = new BulkString(null);

    /**
     * Null array singleton.
     */
    RespType NULL_ARRAY = new Array(null);

    /**
     * Common responses.
     */
    RespType OK = new SimpleString("OK");
    RespType PONG = new SimpleString("PONG");
    RespType ZERO = new Integer(0);
    RespType ONE = new Integer(1);
}
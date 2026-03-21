package protocol;

import com.nitrodb.protocol.RespType;
import com.nitrodb.protocol.RespParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RespParserTest {

    private RespParser parserFor(String input) {
        return new RespParser(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("Parse Simple String")
    void parseSimpleString() throws IOException {
        RespParser parser = parserFor("+OK\r\n");
        RespType result = parser.parse();

        assertInstanceOf(RespType.SimpleString.class, result);
        assertEquals("OK", ((RespType.SimpleString) result).value());
    }

    @Test
    @DisplayName("Parse Error")
    void parseError() throws IOException {
        RespParser parser = parserFor("-ERR unknown command\r\n");
        RespType result = parser.parse();

        assertInstanceOf(RespType.Error.class, result);
        assertEquals("ERR unknown command", ((RespType.Error) result).message());
    }

    @Test
    @DisplayName("Parse Integer")
    void parseInteger() throws IOException {
        RespParser parser = parserFor(":42\r\n");
        RespType result = parser.parse();

        assertInstanceOf(RespType.Integer.class, result);
        assertEquals(42, ((RespType.Integer) result).value());
    }

    @Test
    @DisplayName("Parse negative Integer")
    void parseNegativeInteger() throws IOException {
        RespParser parser = parserFor(":-100\r\n");
        RespType result = parser.parse();

        assertInstanceOf(RespType.Integer.class, result);
        assertEquals(-100, ((RespType.Integer) result).value());
    }

    @Test
    @DisplayName("Parse Bulk String")
    void parseBulkString() throws IOException {
        RespParser parser = parserFor("$5\r\nhello\r\n");
        RespType result = parser.parse();

        assertInstanceOf(RespType.BulkString.class, result);
        assertEquals("hello", ((RespType.BulkString) result).asString());
    }

    @Test
    @DisplayName("Parse Null Bulk String")
    void parseNullBulkString() throws IOException {
        RespParser parser = parserFor("$-1\r\n");
        RespType result = parser.parse();

        assertInstanceOf(RespType.BulkString.class, result);
        assertTrue(((RespType.BulkString) result).isNull());
    }

    @Test
    @DisplayName("Parse Empty Bulk String")
    void parseEmptyBulkString() throws IOException {
        RespParser parser = parserFor("$0\r\n\r\n");
        RespType result = parser.parse();

        assertInstanceOf(RespType.BulkString.class, result);
        assertEquals("", ((RespType.BulkString) result).asString());
    }

    @Test
    @DisplayName("Parse Array (SET command)")
    void parseArray() throws IOException {
        String input = "*3\r\n$3\r\nSET\r\n$5\r\nmykey\r\n$7\r\nmyvalue\r\n";
        RespParser parser = parserFor(input);
        RespType result = parser.parse();

        assertInstanceOf(RespType.Array.class, result);
        RespType.Array array = (RespType.Array) result;
        assertEquals(3, array.size());

        assertEquals("SET", ((RespType.BulkString) array.elements()[0]).asString());
        assertEquals("mykey", ((RespType.BulkString) array.elements()[1]).asString());
        assertEquals("myvalue", ((RespType.BulkString) array.elements()[2]).asString());
    }

    @Test
    @DisplayName("Parse Null Array")
    void parseNullArray() throws IOException {
        RespParser parser = parserFor("*-1\r\n");
        RespType result = parser.parse();

        assertInstanceOf(RespType.Array.class, result);
        assertTrue(((RespType.Array) result).isNull());
    }

    @Test
    @DisplayName("Parse Inline Command")
    void parseInlineCommand() throws IOException {
        RespParser parser = parserFor("PING\r\n");
        RespType result = parser.parse();

        assertInstanceOf(RespType.Array.class, result);
        RespType.Array array = (RespType.Array) result;
        assertEquals(1, array.size());
        assertEquals("PING", ((RespType.BulkString) array.elements()[0]).asString());
    }

    @Test
    @DisplayName("Parse Inline Command with arguments")
    void parseInlineCommandWithArgs() throws IOException {
        RespParser parser = parserFor("SET foo bar\r\n");
        RespType result = parser.parse();

        assertInstanceOf(RespType.Array.class, result);
        RespType.Array array = (RespType.Array) result;
        assertEquals(3, array.size());
        assertEquals("SET", ((RespType.BulkString) array.elements()[0]).asString());
        assertEquals("foo", ((RespType.BulkString) array.elements()[1]).asString());
        assertEquals("bar", ((RespType.BulkString) array.elements()[2]).asString());
    }

    @Test
    @DisplayName("Parse multiple commands sequentially")
    void parseMultipleCommands() throws IOException {
        String input = "*1\r\n$4\r\nPING\r\n*3\r\n$3\r\nSET\r\n$1\r\na\r\n$1\r\nb\r\n";
        RespParser parser = parserFor(input);

        RespType first = parser.parse();
        assertInstanceOf(RespType.Array.class, first);
        assertEquals(1, ((RespType.Array) first).size());

        RespType second = parser.parse();
        assertInstanceOf(RespType.Array.class, second);
        assertEquals(3, ((RespType.Array) second).size());
    }

    @Test
    @DisplayName("Parse binary-safe Bulk String")
    void parseBinaryBulkString() throws IOException {
        byte[] binary = new byte[]{0x00, 0x01, 0x02, (byte) 0xFF};
        String header = "$4\r\n";
        byte[] headerBytes = header.getBytes(StandardCharsets.US_ASCII);
        byte[] inputBytes = new byte[headerBytes.length + binary.length + 2];
        System.arraycopy(headerBytes, 0, inputBytes, 0, headerBytes.length);
        System.arraycopy(binary, 0, inputBytes, headerBytes.length, binary.length);
        inputBytes[inputBytes.length - 2] = '\r';
        inputBytes[inputBytes.length - 1] = '\n';

        RespParser parser = new RespParser(new ByteArrayInputStream(inputBytes));
        RespType result = parser.parse();

        assertInstanceOf(RespType.BulkString.class, result);
        assertArrayEquals(binary, ((RespType.BulkString) result).data());
    }
}
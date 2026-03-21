package com.nitrodb.command;

import com.nitrodb.protocol.RespType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parsed command with type and arguments.
 */
public record Command(CommandType type, List<byte[]> args) {

    /**
     * Parse a RESP Array into a Command.
     */
    public static Command fromResp(RespType resp) {
        if (resp instanceof RespType.Array array && !array.isNull()) {
            if (array.size() == 0) {
                return new Command(CommandType.UNKNOWN, List.of());
            }

            String name = extractString(array.elements()[0]);
            CommandType type = CommandType.fromString(name);

            List<byte[]> args = new ArrayList<>(array.size() - 1);
            for (int i = 1; i < array.size(); i++) {
                args.add(extractBytes(array.elements()[i]));
            }

            return new Command(type, args);
        }
        return new Command(CommandType.UNKNOWN, List.of());
    }

    /**
     * Get argument as string.
     */
    public String argAsString(int index) {
        if (index >= args.size()) return null;
        return new String(args.get(index), StandardCharsets.UTF_8);
    }

    /**
     * Get argument as raw bytes.
     */
    public byte[] argAsBytes(int index) {
        if (index >= args.size()) return null;
        return args.get(index);
    }

    /**
     * Get argument count.
     */
    public int argCount() {
        return args.size();
    }

    private static String extractString(RespType type) {
        return switch (type) {
            case RespType.BulkString bs -> bs.asString();
            case RespType.SimpleString ss -> ss.value();
            default -> type.toString();
        };
    }

    private static byte[] extractBytes(RespType type) {
        return switch (type) {
            case RespType.BulkString bs -> bs.data();
            case RespType.SimpleString ss -> ss.value().getBytes(StandardCharsets.UTF_8);
            case RespType.Integer i -> Long.toString(i.value()).getBytes(StandardCharsets.UTF_8);
            default -> new byte[0];
        };
    }
}
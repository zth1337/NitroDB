package com.nitrodb.command;

import com.nitrodb.cache.OffHeapCache;
import com.nitrodb.protocol.RespType;
import com.nitrodb.storage.StorageEngine;
import com.nitrodb.cache.OffHeapCircularCache;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Dispatches parsed commands to the storage engine.
 * Uses Java 21 pattern matching for clean command routing.
 */
public final class CommandDispatcher {

    private final StorageEngine storage;
    private final OffHeapCache cache;
    private final long startTime;

    public CommandDispatcher(StorageEngine storage, OffHeapCache cache) {
        this.storage = storage;
        this.cache = cache;
        this.startTime = System.currentTimeMillis();
    }

    /**
     * Execute a command and return the RESP response.
     */
    public RespType dispatch(Command cmd) {
        try {
            return switch (cmd.type()) {
                case PING -> handlePing(cmd);
                case ECHO -> handleEcho(cmd);
                case SET -> handleSet(cmd);
                case GET -> handleGet(cmd);
                case DEL -> handleDel(cmd);
                case EXISTS -> handleExists(cmd);
                case SETNX -> handleSetNx(cmd);
                case MGET -> handleMGet(cmd);
                case MSET -> handleMSet(cmd);
                case APPEND -> handleAppend(cmd);
                case STRLEN -> handleStrLen(cmd);
                case INCR -> handleIncr(cmd);
                case DECR -> handleDecr(cmd);
                case INCRBY -> handleIncrBy(cmd);
                case DECRBY -> handleDecrBy(cmd);
                case KEYS -> handleKeys(cmd);
                case TYPE -> handleType(cmd);
                case RENAME -> handleRename(cmd);
                case DBSIZE -> handleDbSize(cmd);
                case FLUSHDB -> handleFlushDb(cmd);
                case INFO -> handleInfo(cmd);
                case CONFIG -> handleConfig(cmd);
                case COMMAND -> handleCommand(cmd);
                case SELECT -> handleSelect(cmd);
                case QUIT -> handleQuit(cmd);
                case UNKNOWN -> new RespType.Error("ERR unknown command '" + cmd.argAsString(0) + "'");
            };
        } catch (IllegalArgumentException e) {
            return new RespType.Error("ERR " + e.getMessage());
        } catch (Exception e) {
            return new RespType.Error("ERR internal error: " + e.getMessage());
        }
    }


    private RespType handlePing(Command cmd) {
        if (cmd.argCount() > 0) {
            return new RespType.BulkString(cmd.argAsBytes(0));
        }
        return RespType.PONG;
    }

    private RespType handleEcho(Command cmd) {
        if (cmd.argCount() < 1) {
            return new RespType.Error("ERR wrong number of arguments for 'echo' command");
        }
        return new RespType.BulkString(cmd.argAsBytes(0));
    }

    private RespType handleSet(Command cmd) throws Exception {
        if (cmd.argCount() < 2) {
            return new RespType.Error("ERR wrong number of arguments for 'set' command");
        }

        String key = cmd.argAsString(0);
        byte[] value = cmd.argAsBytes(1);

        boolean nx = false;
        boolean xx = false;

        for (int i = 2; i < cmd.argCount(); i++) {
            String option = cmd.argAsString(i).toUpperCase();
            switch (option) {
                case "NX" -> nx = true;
                case "XX" -> xx = true;
                case "EX", "PX", "EXAT", "PXAT" -> i++;
                case "KEEPTTL" -> {}
                default -> {}
            }
        }

        if (nx && storage.exists(key)) {
            return new RespType.BulkString(null); // nil
        }
        if (xx && !storage.exists(key)) {
            return new RespType.BulkString(null); // nil
        }

        storage.put(key, value);
        cache.put(key, value);
        return RespType.OK;
    }

    private RespType handleGet(Command cmd) throws Exception {
        if (cmd.argCount() < 1) {
            return new RespType.Error("ERR wrong number of arguments for 'get' command");
        }

        String key = cmd.argAsString(0);

        Optional<byte[]> cached = cache.get(key);
        if (cached.isPresent()) {
            return new RespType.BulkString(cached.get());
        }

        Optional<byte[]> value = storage.get(key);
        if (value.isPresent()) {
            cache.put(key, value.get()); // Populate cache
            return new RespType.BulkString(value.get());
        }

        return new RespType.BulkString(null); // nil
    }

    private RespType handleDel(Command cmd) throws Exception {
        if (cmd.argCount() < 1) {
            return new RespType.Error("ERR wrong number of arguments for 'del' command");
        }

        int deleted = 0;
        for (int i = 0; i < cmd.argCount(); i++) {
            String key = cmd.argAsString(i);
            if (storage.delete(key)) {
                cache.remove(key);
                deleted++;
            }
        }

        return new RespType.Integer(deleted);
    }

    private RespType handleExists(Command cmd) throws Exception {
        if (cmd.argCount() < 1) {
            return new RespType.Error("ERR wrong number of arguments for 'exists' command");
        }

        int count = 0;
        for (int i = 0; i < cmd.argCount(); i++) {
            if (storage.exists(cmd.argAsString(i))) {
                count++;
            }
        }

        return new RespType.Integer(count);
    }

    private RespType handleSetNx(Command cmd) throws Exception {
        if (cmd.argCount() < 2) {
            return new RespType.Error("ERR wrong number of arguments for 'setnx' command");
        }

        String key = cmd.argAsString(0);
        if (storage.exists(key)) {
            return RespType.ZERO;
        }

        storage.put(key, cmd.argAsBytes(1));
        cache.put(key, cmd.argAsBytes(1));
        return RespType.ONE;
    }

    private RespType handleMGet(Command cmd) throws Exception {
        if (cmd.argCount() < 1) {
            return new RespType.Error("ERR wrong number of arguments for 'mget' command");
        }

        RespType[] results = new RespType[cmd.argCount()];
        for (int i = 0; i < cmd.argCount(); i++) {
            String key = cmd.argAsString(i);
            Optional<byte[]> val = storage.get(key);
            results[i] = val.map(v -> (RespType) new RespType.BulkString(v))
                    .orElse(new RespType.BulkString(null));
        }

        return new RespType.Array(results);
    }

    private RespType handleMSet(Command cmd) throws Exception {
        if (cmd.argCount() < 2 || cmd.argCount() % 2 != 0) {
            return new RespType.Error("ERR wrong number of arguments for 'mset' command");
        }

        for (int i = 0; i < cmd.argCount(); i += 2) {
            String key = cmd.argAsString(i);
            byte[] value = cmd.argAsBytes(i + 1);
            storage.put(key, value);
            cache.put(key, value);
        }

        return RespType.OK;
    }

    private RespType handleAppend(Command cmd) throws Exception {
        if (cmd.argCount() < 2) {
            return new RespType.Error("ERR wrong number of arguments for 'append' command");
        }

        String key = cmd.argAsString(0);
        byte[] appendValue = cmd.argAsBytes(1);

        Optional<byte[]> existing = storage.get(key);
        byte[] newValue;
        if (existing.isPresent()) {
            byte[] old = existing.get();
            newValue = new byte[old.length + appendValue.length];
            System.arraycopy(old, 0, newValue, 0, old.length);
            System.arraycopy(appendValue, 0, newValue, old.length, appendValue.length);
        } else {
            newValue = appendValue;
        }

        storage.put(key, newValue);
        cache.put(key, newValue);
        return new RespType.Integer(newValue.length);
    }

    private RespType handleStrLen(Command cmd) throws Exception {
        if (cmd.argCount() < 1) {
            return new RespType.Error("ERR wrong number of arguments for 'strlen' command");
        }

        Optional<byte[]> value = storage.get(cmd.argAsString(0));
        return new RespType.Integer(value.map(v -> v.length).orElse(0));
    }

    private RespType handleIncr(Command cmd) throws Exception {
        return handleIncrByAmount(cmd, 1);
    }

    private RespType handleDecr(Command cmd) throws Exception {
        return handleIncrByAmount(cmd, -1);
    }

    private RespType handleIncrBy(Command cmd) throws Exception {
        if (cmd.argCount() < 2) {
            return new RespType.Error("ERR wrong number of arguments for 'incrby' command");
        }
        try {
            long amount = Long.parseLong(cmd.argAsString(1));
            return handleIncrByAmount(cmd, amount);
        } catch (NumberFormatException e) {
            return new RespType.Error("ERR value is not an integer or out of range");
        }
    }

    private RespType handleDecrBy(Command cmd) throws Exception {
        if (cmd.argCount() < 2) {
            return new RespType.Error("ERR wrong number of arguments for 'decrby' command");
        }
        try {
            long amount = Long.parseLong(cmd.argAsString(1));
            return handleIncrByAmount(cmd, -amount);
        } catch (NumberFormatException e) {
            return new RespType.Error("ERR value is not an integer or out of range");
        }
    }

    private RespType handleIncrByAmount(Command cmd, long amount) throws Exception {
        if (cmd.argCount() < 1) {
            return new RespType.Error("ERR wrong number of arguments");
        }

        String key = cmd.argAsString(0);
        long currentValue = 0;

        Optional<byte[]> existing = storage.get(key);
        if (existing.isPresent()) {
            try {
                currentValue = Long.parseLong(new String(existing.get(), StandardCharsets.UTF_8));
            } catch (NumberFormatException e) {
                return new RespType.Error("ERR value is not an integer or out of range");
            }
        }

        long newValue = currentValue + amount;
        byte[] newBytes = Long.toString(newValue).getBytes(StandardCharsets.UTF_8);
        storage.put(key, newBytes);
        cache.put(key, newBytes);

        return new RespType.Integer(newValue);
    }

    private RespType handleKeys(Command cmd) {
        if (cmd.argCount() < 1) {
            return new RespType.Error("ERR wrong number of arguments for 'keys' command");
        }

        String pattern = cmd.argAsString(0);
        Set<String> allKeys = storage.keys();

        String regex = globToRegex(pattern);
        Pattern compiled = Pattern.compile(regex);

        List<RespType> matching = new ArrayList<>();
        for (String key : allKeys) {
            if (compiled.matcher(key).matches()) {
                matching.add(new RespType.BulkString(key.getBytes(StandardCharsets.UTF_8)));
            }
        }

        return new RespType.Array(matching.toArray(new RespType[0]));
    }

    private RespType handleType(Command cmd) throws Exception {
        if (cmd.argCount() < 1) {
            return new RespType.Error("ERR wrong number of arguments for 'type' command");
        }

        if (storage.exists(cmd.argAsString(0))) {
            return new RespType.SimpleString("string");
        }
        return new RespType.SimpleString("none");
    }

    private RespType handleRename(Command cmd) throws Exception {
        if (cmd.argCount() < 2) {
            return new RespType.Error("ERR wrong number of arguments for 'rename' command");
        }

        String oldKey = cmd.argAsString(0);
        String newKey = cmd.argAsString(1);

        Optional<byte[]> value = storage.get(oldKey);
        if (value.isEmpty()) {
            return new RespType.Error("ERR no such key");
        }

        storage.put(newKey, value.get());
        storage.delete(oldKey);
        cache.remove(oldKey);
        cache.put(newKey, value.get());

        return RespType.OK;
    }

    private RespType handleDbSize(Command cmd) {
        return new RespType.Integer(storage.size());
    }

    private RespType handleFlushDb(Command cmd) throws Exception {
        for (String key : new ArrayList<>(storage.keys())) {
            storage.delete(key);
        }
        cache.clear();
        return RespType.OK;
    }

    private RespType handleInfo(Command cmd) {
        String section = cmd.argCount() > 0 ? cmd.argAsString(0).toLowerCase() : "all";

        StringBuilder info = new StringBuilder();
        info.append("# Server\r\n");
        info.append("nitrodb_version:1.0.0\r\n");
        info.append("java_version:").append(System.getProperty("java.version")).append("\r\n");
        info.append("uptime_in_seconds:").append((System.currentTimeMillis() - startTime) / 1000).append("\r\n");
        info.append("uptime_in_days:").append((System.currentTimeMillis() - startTime) / 86400000).append("\r\n");
        info.append("\r\n");

        info.append("# Keyspace\r\n");
        info.append("db0:keys=").append(storage.size()).append(",expires=0,avg_ttl=0\r\n");
        info.append("\r\n");

        info.append("# Memory\r\n");
        Runtime rt = Runtime.getRuntime();
        info.append("used_memory:").append(rt.totalMemory() - rt.freeMemory()).append("\r\n");
        info.append("used_memory_peak:").append(rt.totalMemory()).append("\r\n");
        info.append("offheap_cache_entries:").append(cache.size()).append("\r\n");
        info.append("offheap_cache_memory:").append(cache.memoryUsage()).append("\r\n");

        return new RespType.BulkString(info.toString().getBytes(StandardCharsets.UTF_8));
    }

    private RespType handleConfig(Command cmd) {
        if (cmd.argCount() < 1) {
            return new RespType.Error("ERR wrong number of arguments for 'config' command");
        }

        String subCommand = cmd.argAsString(0).toUpperCase();
        return switch (subCommand) {
            case "GET" -> {
                if (cmd.argCount() < 2) yield new RespType.Array(new RespType[0]);
                String param = cmd.argAsString(1);
                yield new RespType.Array(new RespType[]{
                        new RespType.BulkString(param.getBytes(StandardCharsets.UTF_8)),
                        new RespType.BulkString("".getBytes(StandardCharsets.UTF_8))
                });
            }
            case "SET" -> RespType.OK;
            case "RESETSTAT" -> RespType.OK;
            default -> new RespType.Error("ERR Unknown subcommand or wrong number of arguments for 'config|" + subCommand + "'");
        };
    }

    private RespType handleCommand(Command cmd) {
        if (cmd.argCount() > 0) {
            String sub = cmd.argAsString(0).toUpperCase();
            if ("DOCS".equals(sub)) {
                return new RespType.Array(new RespType[0]);
            }
        }
        return RespType.OK;
    }

    private RespType handleSelect(Command cmd) {
        return RespType.OK;
    }

    private RespType handleQuit(Command cmd) {
        return RespType.OK;
    }

    /**
     * Convert Redis glob pattern to Java regex.
     */
    private String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                case '[' -> regex.append('[');
                case ']' -> regex.append(']');
                case '\\' -> {
                    if (i + 1 < glob.length()) {
                        regex.append("\\").append(glob.charAt(++i));
                    }
                }
                default -> {
                    if ("(){}+^$.|".indexOf(c) >= 0) {
                        regex.append('\\');
                    }
                    regex.append(c);
                }
            }
        }
        regex.append('$');
        return regex.toString();
    }
}
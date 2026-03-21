package com.nitrodb.command;

/**
 * Supported Redis commands.
 */
public enum CommandType {
    // String commands
    GET,
    SET,
    DEL,
    EXISTS,
    SETNX,
    MGET,
    MSET,
    APPEND,
    STRLEN,
    INCR,
    DECR,
    INCRBY,
    DECRBY,

    // Key commands
    KEYS,
    TYPE,
    RENAME,
    DBSIZE,
    FLUSHDB,

    // Server commands
    PING,
    ECHO,
    INFO,
    CONFIG,
    COMMAND,
    QUIT,
    SELECT,

    UNKNOWN;

    public static CommandType fromString(String name) {
        if (name == null) return UNKNOWN;
        return switch (name.toUpperCase()) {
            case "GET" -> GET;
            case "SET" -> SET;
            case "DEL" -> DEL;
            case "EXISTS" -> EXISTS;
            case "SETNX" -> SETNX;
            case "MGET" -> MGET;
            case "MSET" -> MSET;
            case "APPEND" -> APPEND;
            case "STRLEN" -> STRLEN;
            case "INCR" -> INCR;
            case "DECR" -> DECR;
            case "INCRBY" -> INCRBY;
            case "DECRBY" -> DECRBY;
            case "KEYS" -> KEYS;
            case "TYPE" -> TYPE;
            case "RENAME" -> RENAME;
            case "DBSIZE" -> DBSIZE;
            case "FLUSHDB", "FLUSHALL" -> FLUSHDB;
            case "PING" -> PING;
            case "ECHO" -> ECHO;
            case "INFO" -> INFO;
            case "CONFIG" -> CONFIG;
            case "COMMAND" -> COMMAND;
            case "QUIT" -> QUIT;
            case "SELECT" -> SELECT;
            default -> UNKNOWN;
        };
    }
}
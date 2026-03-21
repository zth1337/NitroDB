package com.nitrodb.server;

import com.nitrodb.command.Command;
import com.nitrodb.command.CommandDispatcher;
import com.nitrodb.command.CommandType;
import com.nitrodb.protocol.RespParser;
import com.nitrodb.protocol.RespType;
import com.nitrodb.protocol.RespWriter;

import java.io.*;
import java.net.Socket;

/**
 * Handles a single client connection.
 * Runs on a virtual thread — one thread per connection.
 * Simple blocking I/O that's efficient thanks to Project Loom.
 */
public final class ClientHandler implements Runnable {

    private final Socket socket;
    private final CommandDispatcher dispatcher;
    private final String clientId;

    public ClientHandler(Socket socket, CommandDispatcher dispatcher) {
        this.socket = socket;
        this.dispatcher = dispatcher;
        this.clientId = socket.getRemoteSocketAddress().toString();
    }

    @Override
    public void run() {
        try (socket;
             BufferedInputStream bis = new BufferedInputStream(socket.getInputStream(), 8192);
             BufferedOutputStream bos = new BufferedOutputStream(socket.getOutputStream(), 8192)) {

            RespParser parser = new RespParser(bis);
            RespWriter writer = new RespWriter(bos);

            while (!socket.isClosed()) {
                RespType request = parser.parse();
                if (request == null) {
                    break;
                }

                Command cmd = Command.fromResp(request);
                RespType response = dispatcher.dispatch(cmd);

                writer.write(response);
                writer.flush();

                if (cmd.type() == CommandType.QUIT) {
                    break;
                }
            }

        } catch (IOException e) {
            if (!socket.isClosed()) {
                String msg = e.getMessage();
                if (msg != null && !msg.contains("Connection reset") && !msg.contains("Broken pipe")) {
                    System.err.println("[NitroDB] Client " + clientId + " error: " + msg);
                }
            }
        }
    }
}
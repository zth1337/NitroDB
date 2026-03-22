package com.nitrodb.server;

import com.nitrodb.command.Command;
import com.nitrodb.command.CommandDispatcher;
import com.nitrodb.config.NitroConfig;
import com.nitrodb.protocol.*;
import java.io.*;
import java.net.Socket;

public final class ClientHandler implements Runnable {
    private final Socket socket;
    private final CommandDispatcher dispatcher;
    private final NitroConfig config;

    public ClientHandler(Socket socket, CommandDispatcher dispatcher, NitroConfig config) {
        this.socket = socket;
        this.dispatcher = dispatcher;
        this.config = config;
    }

    @Override
    public void run() {
        try (socket;
             BufferedInputStream bis = new BufferedInputStream(socket.getInputStream(), 16384);
             BufferedOutputStream bos = new BufferedOutputStream(socket.getOutputStream(), 16384)) {
            RespParser parser = new RespParser(bis, config.maxValueSize());
            RespWriter writer = new RespWriter(bos);
            while (!socket.isClosed()) {
                RespType request = parser.parse();
                if (request == null) break;
                Command cmd = Command.fromResp(request);
                RespType response = dispatcher.dispatch(cmd);
                writer.write(response);
                writer.flush();
            }
        } catch (IOException e) {}
    }
}
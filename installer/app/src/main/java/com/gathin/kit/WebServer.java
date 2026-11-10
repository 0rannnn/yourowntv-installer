package com.gathin.kit;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A very small HTTP server for the phone on the same Wi-Fi: one request per connection, a handler per path. Enough for
 * a page, its pictures and a few JSON calls; nothing here is reachable outside the home network.
 */
final class WebServer {
    static final int PORT = 8800;

    static final class Request {
        String method, path;
        final Map<String, String> query = new HashMap<>();
        byte[] body = new byte[0];

        String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    static final class Response {
        final int status;
        final String type;
        final byte[] data;

        Response(int status, String type, byte[] data) {
            this.status = status;
            this.type = type;
            this.data = data;
        }

        static Response json(String json) {
            return new Response(200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
        }

        static Response notFound() {
            return new Response(404, "text/plain; charset=utf-8", "not found".getBytes(StandardCharsets.UTF_8));
        }
    }

    interface Handler {
        Response handle(Request request) throws Exception;
    }

    private final Handler handler;
    private ServerSocket server;

    WebServer(Handler handler) {
        this.handler = handler;
    }

    void start() throws IOException {
        server = new ServerSocket(PORT);
        Thread accept = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    new Thread(() -> serve(socket), "kit-http").start();
                } catch (IOException ignored) {
                    // closed, or one failed connection
                }
            }
        }, "kit-http-accept");
        accept.setDaemon(true);
        accept.start();
    }

    void stop() {
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
    }

    private void serve(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(30_000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            String line = readLine(in);
            if (line == null || line.isEmpty()) return;
            String[] parts = line.split(" ");
            Request request = new Request();
            request.method = parts[0];
            String target = parts.length > 1 ? parts[1] : "/";
            int q = target.indexOf('?');
            request.path = q >= 0 ? target.substring(0, q) : target;
            if (q >= 0) {
                for (String pair : target.substring(q + 1).split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0) request.query.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
                }
            }
            int length = 0;
            String header;
            while ((header = readLine(in)) != null && !header.isEmpty()) {
                if (header.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                    length = Integer.parseInt(header.substring(15).trim());
                }
            }
            if (length > 0) {
                byte[] body = new byte[length];
                int read = 0;
                while (read < length) {
                    int n = in.read(body, read, length - read);
                    if (n < 0) break;
                    read += n;
                }
                request.body = body;
            }
            Response response;
            try {
                response = handler.handle(request);
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                response = new Response(500, "application/json; charset=utf-8",
                        ("{\"error\":" + Json.quote(message) + "}").getBytes(StandardCharsets.UTF_8));
            }
            if (response == null) response = Response.notFound();
            OutputStream out = s.getOutputStream();
            String head = "HTTP/1.1 " + response.status + " OK\r\nContent-Type: " + response.type
                    + "\r\nContent-Length: " + response.data.length
                    + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.UTF_8));
            out.write(response.data);
            out.flush();
        } catch (IOException ignored) {
            // the phone went away mid-request
        }
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') line.write(c);
        }
        if (c < 0 && line.size() == 0) return null;
        return line.toString("UTF-8");
    }
}

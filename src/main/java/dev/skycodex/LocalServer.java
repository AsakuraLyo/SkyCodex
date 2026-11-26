package dev.skycodex;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.Base64;
import java.util.concurrent.*;

/** Authenticated, loopback-only API. Every Minecraft access runs on its main thread. */
final class LocalServer {
    private final Controller controller;
    private final Gson gson = new Gson();
    private final byte[] tokenBytes = new byte[32];
    private String token;
    LocalServer(Controller c) { controller = c; }

    void start() throws Exception {
        new SecureRandom().nextBytes(tokenBytes);
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        int port = server.getAddress().getPort();
        server.setExecutor(Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "SkyCodex HTTP"); t.setDaemon(true); return t; }));
        server.createContext("/v1/", this::handle);
        server.start();
        Path config = FMLPaths.CONFIGDIR.get().resolve("skycodex-connection.json");
        Files.createDirectories(config.getParent());
        JsonObject data = new JsonObject();
        data.addProperty("port", port); data.addProperty("token", token); data.addProperty("version", Controller.VERSION);
        Path temp = config.resolveSibling("skycodex-connection.json.tmp");
        Files.writeString(temp, gson.toJson(data));
        try { Files.setPosixFilePermissions(temp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException ignored) {}
        Files.move(temp, config, StandardCopyOption.REPLACE_EXISTING);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0), "SkyCodex shutdown"));
    }

    private void handle(HttpExchange x) {
        try {
            String expectedHost = "127.0.0.1:" + x.getLocalAddress().getPort();
            if (!expectedHost.equals(x.getRequestHeaders().getFirst("Host")) || x.getRequestHeaders().containsKey("Origin")) {
                send(x, 403, error("Invalid origin or host")); return;
            }
            String auth = x.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.UTF_8), auth.getBytes(StandardCharsets.UTF_8))) {
                send(x, 401, error("Authentication required")); return;
            }
            if (!x.getRequestMethod().equals("POST")) { send(x, 405, error("POST required")); return; }
            byte[] body = x.getRequestBody().readNBytes(2_000_001);
            if (body.length > 2_000_000) { send(x, 413, error("Request too large")); return; }
            JsonObject args = body.length == 0 ? new JsonObject() : JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
            String op = x.getRequestURI().getPath().substring(4);
            CompletableFuture<JsonObject> future = new CompletableFuture<>();
            // Run on the client tick (see SkyCodex), NOT via Minecraft.execute(): inside an executed task the event loop
            // is re-entrant, so Baritone's first loot-table load (which joins work scheduled on the main thread) deadlocks.
            controller.pending.add(() -> {
                if (future.isCancelled()) return;
                try { future.complete(controller.request(op, args)); }
                catch (Exception e) { future.complete(error(e.getMessage() == null ? e.toString() : e.getMessage())); }
            });
            try { send(x, 200, future.get(8, TimeUnit.SECONDS)); }
            catch (TimeoutException e) { future.cancel(false); send(x, 504, error("Game thread did not respond. Check game pause/loading state.")); }
        } catch (Exception e) {
            try { send(x, 400, error(e.getMessage() == null ? "Invalid request" : e.getMessage())); } catch (Exception ignored) {}
        } finally { x.close(); }
    }
    static JsonObject error(String message) { JsonObject o = new JsonObject(); o.addProperty("error", message); return o; }
    private void send(HttpExchange x, int status, JsonObject data) throws Exception {
        byte[] bytes = gson.toJson(data).getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        x.getResponseHeaders().set("Cache-Control", "no-store");
        x.sendResponseHeaders(status, bytes.length);
        x.getResponseBody().write(bytes);
    }
}

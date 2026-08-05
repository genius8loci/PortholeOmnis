package org.genius8loci.portholeomnis;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Держит дочерний процесс `porthole expose` на всё время сессии
 * и разбирает его поток --json (одна JSON-строка на событие).
 */
public final class PortholeLauncher {

    private static Process process;
    private static Thread shutdownHook;

    private PortholeLauncher() {
    }

    public static synchronized boolean isRunning() {
        return process != null && process.isAlive();
    }

    /**
     * @param port       локальный TCP-порт LAN-мира
     * @param forceRelay принудительный релей Valve (скрывает IP хоста)
     * @param onCode     вызывается при lobby_ready, аргумент — код лобби
     * @param onInfo     информационные сообщения и ошибки для чата
     */
    public static synchronized void start(int port, boolean forceRelay,
                                          Consumer<String> onCode,
                                          Consumer<String> onInfo) {
        if (isRunning()) {
            return;
        }
        Path exe = PortholeLocator.locate();
        if (exe == null) {
            onInfo.accept("porthole.not_found");
            return;
        }
        if (!SteamClient.isRunning()) {
            onInfo.accept("porthole.steam_not_running");
            return;
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(exe.toString());
        cmd.add("expose");
        // proto/host:port[:exposed] — слушаем то, что дал Minecraft,
        // публикуем всегда ADVERTISED_PORT.
        cmd.add("tcp/127.0.0.1:" + port + ":" + PortholeOmnis.ADVERTISED_PORT);
        cmd.add("--json");
        if (forceRelay) {
            cmd.add("--force-relay");
        }

        PortholeOmnis.LOGGER.info("[porthole] exec: {}", String.join(" ", cmd));

        try {
            process = new ProcessBuilder(cmd)
                    .directory(exe.getParent().toFile())
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            process = null;
            PortholeOmnis.LOGGER.error("[porthole] launch failed", e);
            onInfo.accept("porthole.launch_failed:" + e.getMessage());
            return;
        }

        WindowsJobObject.adopt(process);
        PortholeWatchdog.track(process);
        shutdownHook = new Thread(PortholeLauncher::destroyQuietly, "portholeomnis-hook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        Process p = process;
        Thread pump = new Thread(() -> pump(p, onCode, onInfo), "portholeomnis-stdout");
        pump.setDaemon(true);
        pump.start();
    }

    private static void pump(Process p, Consumer<String> onCode, Consumer<String> onInfo) {
        // События приходят с повторами — дедупликация по сырой строке.
        Set<String> seen = new HashSet<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                // Первые строки печатает steam_api64.dll до передачи управления — не JSON.
                if (!line.startsWith("{")) {
                    PortholeOmnis.LOGGER.info("[porthole] {}", line);
                    continue;
                }
                JsonObject o;
                try {
                    JsonElement el = JsonParser.parseString(line);
                    if (!el.isJsonObject()) {
                        continue;
                    }
                    o = el.getAsJsonObject();
                } catch (RuntimeException malformed) {
                    continue;
                }
                if (!seen.add(line)) {
                    continue;
                }
                handle(o, onCode, onInfo);
            }
        } catch (IOException ignored) {
            // поток закрыт вместе с процессом
        }
    }

    private static void handle(JsonObject o, Consumer<String> onCode, Consumer<String> onInfo) {
        String event = o.has("event") && o.get("event").isJsonPrimitive()
                ? o.get("event").getAsString() : "";
        switch (event) {
            case "lobby_ready" -> {
                if (o.has("code")) {
                    String code = o.get("code").getAsString();
                    // lobby_id — u64, выходит за пределы точного double; только getAsLong.
                    if (o.has("lobby_id")) {
                        PortholeOmnis.LOGGER.info("[porthole] lobby {} code {}",
                                o.get("lobby_id").getAsLong(), code);
                    }
                    onCode.accept(code);
                }
            }
            case "error" -> {
                PortholeOmnis.LOGGER.error("[porthole] {}", message(o));
                onInfo.accept("porthole.error:" + message(o));
            }
            default -> {
                // Схема ещё не стабилизирована: неизвестные события не роняют мод.
                String m = message(o);
                if (!m.isEmpty()) {
                    PortholeOmnis.LOGGER.info("[porthole] {}", m);
                }
            }
        }
    }

    private static String message(JsonObject o) {
        return o.has("message") && o.get("message").isJsonPrimitive()
                ? o.get("message").getAsString() : "";
    }

    public static synchronized void stop() {
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM уже завершается
            }
            shutdownHook = null;
        }
        destroyQuietly();
    }

    private static void destroyQuietly() {
        Process p = process;
        process = null;
        if (p == null) {
            return;
        }
        PortholeWatchdog.untrack(p.pid());
        p.destroy();
        try {
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}

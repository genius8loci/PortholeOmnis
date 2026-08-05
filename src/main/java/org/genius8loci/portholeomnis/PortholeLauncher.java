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
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Держит дочерний процесс `porthole expose` на всё время сессии
 * и разбирает его поток --json (одна JSON-строка на событие).
 */
public final class PortholeLauncher {

    /** Сколько даём процессу на штатное завершение, прежде чем добить. */
    private static final long DESTROY_GRACE_SECONDS = 3;

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
        // Процесс мог умереть сам. Тогда от прошлого запуска остались зарегистрированный
        // shutdown hook (перезапись поля потеряла бы его в Runtime навсегда) и строка
        // в файле вотчдога — снимаем и то, и другое.
        stop();

        String unavailable = PortholeLocator.unavailableId();
        if (unavailable != null) {
            onInfo.accept(unavailable);
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

        PortholeLobby.begin(forceRelay);
        WindowsJobObject.adopt(process);
        PortholeWatchdog.track(process);
        shutdownHook = new Thread(PortholeLauncher::destroyBlocking, "portholeomnis-hook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        Process p = process;
        Thread pump = new Thread(() -> pump(p, onCode, onInfo), "portholeomnis-stdout");
        pump.setDaemon(true);
        pump.start();
    }

    private static void pump(Process p, Consumer<String> onCode, Consumer<String> onInfo) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                p.getInputStream(), StandardCharsets.UTF_8))) {
            readEvents(r, onCode, onInfo);
        } catch (IOException ignored) {
            // поток закрыт вместе с процессом
        } finally {
            // Поток кончился — процесс умер. Панель обязана перестать показывать
            // участников, которых уже нет.
            PortholeLobby.end();
        }
    }

    /**
     * Разбор потока событий. Отделён от процесса, чтобы дедуп можно было проверить
     * тестом, скормив строки напрямую.
     *
     * <p>Дедуп только подряд идущих строк. Porthole шлёт события пачками одинаковых
     * копий — их и гасим. Глобальный набор здесь был бы хуже, чем бесполезен:
     * повторный вход того же игрока даёт байт в байт ту же строку peer_connected,
     * и участник просто не появлялся бы. Между уходом и возвратом всегда есть
     * peer_disconnected, так что соседними эти строки не окажутся.
     */
    static void readEvents(BufferedReader r, Consumer<String> onCode, Consumer<String> onInfo)
            throws IOException {
        String previous = null;
        String line;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            // Первые строки печатает steam_api64.dll до передачи управления — не JSON.
            if (!line.startsWith("{")) {
                PortholeOmnis.LOGGER.info("[porthole] {}", line);
                continue;
            }
            if (line.equals(previous)) {
                continue;
            }
            previous = line;
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
            handle(o, onCode, onInfo);
        }
    }

    static void handle(JsonObject o, Consumer<String> onCode, Consumer<String> onInfo) {
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
                    PortholeLobby.code(code);
                    onCode.accept(code);
                }
            }
            // Пир подключился и подтвердил готовность. Steam ID лежит отдельным полем —
            // в отличие от info/member, где это свободный текст.
            case "peer_connected", "ready", "port_accepted" ->
                    PortholeLobby.peerConnected(steamId(o));
            case "peer_disconnected" -> PortholeLobby.peerDisconnected(steamId(o));
            // Телеметрия канала: приходит несколько раз в секунду на каждого пира.
            case "link" -> PortholeLobby.link(steamId(o),
                    intOf(o, "ping_ms"), floatOf(o, "quality"));
            case "info" -> {
                String m = message(o);
                PortholeLobby.parseInfo(m);
                if (!m.isEmpty()) {
                    PortholeOmnis.LOGGER.info("[porthole] {}", m);
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

    /** steam_id приходит строкой: u64 не влезает в точный диапазон double. */
    private static long steamId(JsonObject o) {
        return o.has("steam_id") && o.get("steam_id").isJsonPrimitive()
                ? PortholeLobby.parseSteamId(o.get("steam_id").getAsString())
                : 0;
    }

    private static int intOf(JsonObject o, String field) {
        try {
            return o.has(field) && o.get(field).isJsonPrimitive() ? o.get(field).getAsInt() : 0;
        } catch (RuntimeException notANumber) {
            return 0;
        }
    }

    private static float floatOf(JsonObject o, String field) {
        try {
            return o.has(field) && o.get(field).isJsonPrimitive() ? o.get(field).getAsFloat() : 0f;
        } catch (RuntimeException notANumber) {
            return 0f;
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

    /** Снимает процесс с учёта. Возвращает его же — или null, если снимать нечего. */
    private static synchronized Process detach() {
        Process p = process;
        process = null;
        if (p != null) {
            PortholeWatchdog.untrack(p.pid());
        }
        return p;
    }

    /**
     * Убивает процесс, не задерживая вызывающего: stop() приходит с клиентского потока
     * (закрытие мира, выход из игры), а три секунды ожидания на нём — замершая игра.
     */
    private static void destroyQuietly() {
        Process p = detach();
        if (p == null) {
            return;
        }
        p.destroy();
        Thread reaper = new Thread(() -> awaitExit(p), "portholeomnis-reaper");
        reaper.setDaemon(true);
        reaper.start();
    }

    /**
     * Вариант для shutdown hook: ждать обязан сам хук. Посторонний поток JVM во время
     * завершения не дождётся, и упрямый процесс пережил бы игру.
     */
    private static void destroyBlocking() {
        Process p = detach();
        if (p == null) {
            return;
        }
        p.destroy();
        awaitExit(p);
    }

    private static void awaitExit(Process p) {
        try {
            if (!p.waitFor(DESTROY_GRACE_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}

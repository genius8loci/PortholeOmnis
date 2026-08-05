package org.genius8loci.portholeomnis;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Гостевая сторона: держит процесс `porthole connect` на всё время сессии.
 *
 * <p>Схема событий `connect --json` неизвестна, поэтому готовность туннеля
 * определяется не по событию, а пробным TCP-подключением к локальному порту.
 * Порт задаём сами через {@code --remap}, так что гадать его не приходится.
 *
 * <p>Все колбэки вызываются из фонового потока — переводить их в клиентский
 * поток обязан вызывающий.
 */
public final class PortholeConnector {

    /** Сколько ждём, пока порт начнёт принимать соединения. */
    private static final long READY_TIMEOUT_SECONDS = 30;
    private static final long PROBE_INTERVAL_MILLIS = 500;

    private static Process process;
    private static Thread shutdownHook;
    private static volatile String errorKey;

    private PortholeConnector() {
    }

    public static synchronized boolean isRunning() {
        return process != null && process.isAlive();
    }

    /**
     * @param code     share-код лобби
     * @param status   ключ перевода для строки состояния
     * @param onReady  порт на 127.0.0.1, к которому уже можно подключаться
     * @param onFail   ключ перевода с описанием отказа
     */
    public static void connect(String code, boolean forceRelay,
                               Consumer<String> status,
                               IntConsumer onReady,
                               Consumer<String> onFail) {
        Thread worker = new Thread(() -> run(code, forceRelay, status, onReady, onFail),
                "portholeomnis-connect");
        worker.setDaemon(true);
        worker.start();
    }

    private static void run(String code, boolean forceRelay,
                            Consumer<String> status,
                            IntConsumer onReady,
                            Consumer<String> onFail) {
        Path exe = PortholeLocator.locate();
        if (exe == null) {
            onFail.accept("portholeomnis.porthole.not_found");
            return;
        }
        if (!SteamClient.isRunning()) {
            onFail.accept("portholeomnis.porthole.steam_not_running");
            return;
        }

        int port;
        try {
            port = freePort();
        } catch (IOException e) {
            PortholeOmnis.LOGGER.error("[porthole] не удалось подобрать свободный порт", e);
            onFail.accept("portholeomnis.connect.no_port");
            return;
        }

        status.accept("portholeomnis.connect.status.starting");
        if (!start(exe, code, forceRelay, port, onFail)) {
            return;
        }

        status.accept("portholeomnis.connect.status.waiting");
        awaitReady(port, onReady, onFail);
    }

    /**
     * Порт берём у ОС и сразу отпускаем: между закрытием сокета и захватом порта
     * процессом Porthole есть окно гонки, но 25565 у гостя может быть занят
     * собственным миром, поэтому хардкод хуже.
     */
    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private static synchronized boolean start(Path exe, String code, boolean forceRelay,
                                              int port, Consumer<String> onFail) {
        stop();
        errorKey = null;

        List<String> cmd = new ArrayList<>();
        cmd.add(exe.toString());
        cmd.add("connect");
        cmd.add(code);
        cmd.add("--auto-approve");
        // exposed:local — хост публикует ADVERTISED_PORT, у себя слушаем свой.
        cmd.add("--remap");
        cmd.add(PortholeOmnis.ADVERTISED_PORT + ":" + port);
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
            PortholeOmnis.LOGGER.error("[porthole] connect не запустился", e);
            onFail.accept("portholeomnis.porthole.launch_failed");
            return false;
        }

        WindowsJobObject.adopt(process);
        PortholeWatchdog.track(process);
        shutdownHook = new Thread(PortholeConnector::destroyQuietly, "portholeomnis-connect-hook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        Process p = process;
        Thread pump = new Thread(() -> pump(p), "portholeomnis-connect-stdout");
        pump.setDaemon(true);
        pump.start();
        return true;
    }

    /** Схема connect не разобрана — всё пишем в лог, ловим только явный error. */
    private static void pump(Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                PortholeOmnis.LOGGER.info("[porthole connect] {}", line);
                if (!line.startsWith("{")) {
                    continue;
                }
                try {
                    JsonElement el = JsonParser.parseString(line);
                    if (!el.isJsonObject()) {
                        continue;
                    }
                    JsonObject o = el.getAsJsonObject();
                    if (o.has("event") && o.get("event").isJsonPrimitive()
                            && "error".equals(o.get("event").getAsString())) {
                        errorKey = "portholeomnis.porthole.error";
                    }
                } catch (RuntimeException malformed) {
                    // сырой CLI: неразобранная строка не должна ронять мод
                }
            }
        } catch (IOException ignored) {
            // поток закрыт вместе с процессом
        }
    }

    /**
     * Ждём, пока локальный порт начнёт принимать соединения. Пробное подключение
     * открывается и сразу закрывается — для Porthole это выглядит как гость,
     * который подключился и отвалился.
     */
    private static void awaitReady(int port, IntConsumer onReady, Consumer<String> onFail) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(READY_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            String failed = errorKey;
            if (failed != null) {
                stop();
                onFail.accept(failed);
                return;
            }
            if (!isRunning()) {
                stop();
                onFail.accept("portholeomnis.connect.exited");
                return;
            }
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress("127.0.0.1", port), 1000);
                PortholeOmnis.LOGGER.info("[porthole] туннель принимает соединения на 127.0.0.1:{}", port);
                onReady.accept(port);
                return;
            } catch (IOException notYet) {
                // порт ещё не открыт — это норма, пока идёт согласование
            }
            try {
                Thread.sleep(PROBE_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        stop();
        onFail.accept("portholeomnis.connect.timeout");
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

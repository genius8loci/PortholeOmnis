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
import java.util.concurrent.atomic.AtomicReference;
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
    /** Сколько даём процессу на штатное завершение, прежде чем добить. */
    private static final long DESTROY_GRACE_SECONDS = 3;
    /** Поколение, которого не бывает: {@link #generation} растёт с единицы. */
    private static final int NO_ATTEMPT = 0;

    private static Process process;
    private static Thread shutdownHook;
    /**
     * Номер текущей попытки подключения. Воркер помнит своё поколение и, увидев чужое,
     * молча уходит: иначе воркер отменённой попытки по своему дедлайну свернул бы
     * туннель следующей.
     */
    private static int generation;

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
        String unavailable = PortholeLocator.unavailableId();
        if (unavailable != null) {
            onFail.accept(PortholeOmnis.key(unavailable));
            return;
        }
        Path exe = PortholeLocator.locate();
        if (exe == null) {
            onFail.accept(PortholeOmnis.key("porthole.not_found"));
            return;
        }
        if (!SteamClient.isRunning()) {
            onFail.accept(PortholeOmnis.key("porthole.steam_not_running"));
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
        // Ошибку из stdout своего процесса воркер читает через свою же ссылку —
        // общее статическое поле досталось бы и следующей попытке.
        AtomicReference<String> error = new AtomicReference<>();
        int gen = start(exe, code, forceRelay, port, error, onFail);
        if (gen == NO_ATTEMPT) {
            return;
        }

        status.accept("portholeomnis.connect.status.waiting");
        awaitReady(gen, port, error, onReady, onFail);
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

    /** @return поколение запущенной попытки либо {@link #NO_ATTEMPT}, если не взлетело. */
    private static synchronized int start(Path exe, String code, boolean forceRelay,
                                          int port, AtomicReference<String> error,
                                          Consumer<String> onFail) {
        stop();

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
            return NO_ATTEMPT;
        }

        WindowsJobObject.adopt(process);
        PortholeWatchdog.track(process);
        shutdownHook = new Thread(PortholeConnector::destroyBlocking, "portholeomnis-connect-hook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        Process p = process;
        Thread pump = new Thread(() -> pump(p, error), "portholeomnis-connect-stdout");
        pump.setDaemon(true);
        pump.start();
        return ++generation;
    }

    /** Схема connect не разобрана — всё пишем в лог, ловим только явный error. */
    private static void pump(Process p, AtomicReference<String> error) {
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
                        error.set("portholeomnis.porthole.error");
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
    private static void awaitReady(int gen, int port, AtomicReference<String> error,
                                   IntConsumer onReady, Consumer<String> onFail) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(READY_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            String failed = error.get();
            if (failed != null) {
                if (stopIfCurrent(gen)) {
                    onFail.accept(failed);
                }
                return;
            }
            if (!aliveAndCurrent(gen)) {
                // Либо процесс умер, либо попытку отменили. Во втором случае
                // stopIfCurrent вернёт false и гость не увидит чужой ошибки.
                if (stopIfCurrent(gen)) {
                    onFail.accept("portholeomnis.connect.exited");
                }
                return;
            }
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress("127.0.0.1", port), 1000);
                PortholeOmnis.LOGGER.info("[porthole] туннель принимает соединения на 127.0.0.1:{}", port);
                if (isCurrent(gen)) {
                    onReady.accept(port);
                }
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
        if (stopIfCurrent(gen)) {
            onFail.accept("portholeomnis.connect.timeout");
        }
    }

    private static synchronized boolean isCurrent(int gen) {
        return generation == gen;
    }

    /** Поколение и живость проверяются вместе: между двумя вызовами попытку могли сменить. */
    private static synchronized boolean aliveAndCurrent(int gen) {
        return generation == gen && process != null && process.isAlive();
    }

    /**
     * Сворачивает туннель, только если он всё ещё принадлежит поколению {@code gen}.
     *
     * @return true, если попытка была наша — тогда и о её исходе сообщать нам
     */
    private static synchronized boolean stopIfCurrent(int gen) {
        if (generation != gen) {
            return false;
        }
        stop();
        return true;
    }

    public static synchronized void stop() {
        // Смена поколения — сигнал воркеру текущей попытки: его процесса больше нет,
        // молчать и ничего не трогать.
        generation++;
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
     * Убивает процесс, не задерживая вызывающего: stop() приходит с клиентского потока,
     * а три секунды ожидания на нём — это три секунды замершей игры.
     */
    private static void destroyQuietly() {
        Process p = detach();
        if (p == null) {
            return;
        }
        p.destroy();
        Thread reaper = new Thread(() -> awaitExit(p), "portholeomnis-connect-reaper");
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

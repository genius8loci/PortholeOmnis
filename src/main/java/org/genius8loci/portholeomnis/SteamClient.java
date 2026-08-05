package org.genius8loci.portholeomnis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Всё, что касается самого клиента Steam: где он лежит, запущен ли, как его дёрнуть. */
public final class SteamClient {

    private static final String OS = System.getProperty("os.name", "").toLowerCase();
    private static final boolean WINDOWS = OS.contains("win");
    private static final boolean MACOS = OS.contains("mac");
    private static final String PROCESS = WINDOWS ? "steam.exe" : "steam";

    /** Сколько ждём появления процесса Steam, прежде чем всё равно открыть ссылку. */
    private static final long LAUNCH_WAIT_MILLIS = 8_000;
    private static final long POLL_MILLIS = 250;

    /**
     * Время жизни кэша проверки. Перечисление процессов на Windows — это OpenProcess
     * и QueryFullProcessImageName на каждый процесс системы, сотни вызовов ядра;
     * с клиентского потока это заметный подлаг.
     */
    private static final long CACHE_NANOS = TimeUnit.SECONDS.toNanos(5);

    private record Probe(boolean running, long at) {
    }

    private static volatile Probe cache;

    private SteamClient() {
    }

    /**
     * Запущен ли клиент Steam. Porthole без него не работает.
     *
     * <p>Ответ кэшируется на {@link #CACHE_NANOS}: метод зовут при открытии экрана и
     * каждую секунду из его тика, а сама проверка дорогая. Кому нужен свежий ответ —
     * {@link #refresh()}.
     */
    public static boolean isRunning() {
        Probe c = cache;
        if (c != null && System.nanoTime() - c.at() < CACHE_NANOS) {
            return c.running();
        }
        return refresh();
    }

    /** Проверка мимо кэша: для опроса в цикле, где протухший ответ и есть та самая беда. */
    public static boolean refresh() {
        boolean running = scan();
        cache = new Probe(running, System.nanoTime());
        return running;
    }

    /**
     * <p>Проверка намеренно «мягкая»: если перечислить процессы не удалось или ни у
     * одного процесса нет имени команды (урезанные права, песочница), считаем, что
     * Steam запущен. Ложный запрет хуже пропущенной проверки — с ним пользователь
     * с рабочей установкой не сможет запустить туннель вообще.
     */
    private static boolean scan() {
        int named = 0;
        try {
            for (ProcessHandle h : ProcessHandle.allProcesses().toList()) {
                String cmd = h.info().command().orElse(null);
                if (cmd == null) {
                    continue;
                }
                named++;
                if (fileName(cmd).equalsIgnoreCase(PROCESS)) {
                    return true;
                }
            }
        } catch (RuntimeException e) {
            PortholeOmnis.LOGGER.warn("не удалось перечислить процессы", e);
            return true;
        }
        return named == 0;
    }

    /**
     * Открывает страницу Porthole в клиенте Steam, при необходимости запустив сам Steam.
     * Работает в фоновом потоке: запуск Steam занимает секунды, клиентский поток ждать не должен.
     */
    public static void openStorePage() {
        Thread t = new Thread(SteamClient::doOpenStorePage, "portholeomnis-steam-store");
        t.setDaemon(true);
        t.start();
    }

    private static void doOpenStorePage() {
        // Мимо кэша: поток фоновый, а поднимать Steam второй раз ни к чему.
        if (!refresh()) {
            launchAndWait();
        }
        openUri(PortholeOmnis.STORE_STEAM_URI);
    }

    private static void launchAndWait() {
        Path exe = executable();
        if (exe == null) {
            // Steam не нашёлся — протокольный обработчик Windows поднимет его сам,
            // если steam:// вообще зарегистрирован.
            PortholeOmnis.LOGGER.info("[steam] исполняемый файл не найден, полагаемся на обработчик steam://");
            return;
        }
        try {
            PortholeOmnis.LOGGER.info("[steam] запускаем {}", exe);
            new ProcessBuilder(exe.toString()).directory(exe.getParent().toFile()).start();
        } catch (IOException e) {
            PortholeOmnis.LOGGER.error("[steam] не удалось запустить клиент", e);
            return;
        }
        long deadline = System.currentTimeMillis() + LAUNCH_WAIT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            // Именно refresh: с кэшем цикл первые пять секунд читал бы протухшее «нет».
            if (refresh()) {
                return;
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        PortholeOmnis.LOGGER.warn("[steam] клиент не поднялся за {} мс, открываем ссылку всё равно",
                LAUNCH_WAIT_MILLIS);
    }

    /**
     * Отдаёт ссылку системному обработчику протокола.
     *
     * <p>Ванильный {@code Util.getOperatingSystem().open(String)} здесь бесполезен:
     * он гонит строку через {@code URI.toURL()}, а для схемы {@code steam} обработчика
     * протокола в Java нет — вылетает MalformedURLException, который тихо уходит в лог.
     * На Windows зовём тот же обработчик, что и диалог «Выполнить».
     */
    private static void openUri(String uri) {
        List<String> cmd;
        if (WINDOWS) {
            cmd = List.of("rundll32", "url.dll,FileProtocolHandler", uri);
        } else if (MACOS) {
            // xdg-open на macOS нет; штатный открывальщик схем — open(1).
            cmd = List.of("open", uri);
        } else {
            cmd = List.of("xdg-open", uri);
        }
        try {
            PortholeOmnis.LOGGER.info("[steam] открываем {}", uri);
            new ProcessBuilder(cmd).start();
        } catch (IOException e) {
            PortholeOmnis.LOGGER.error("[steam] не удалось открыть {}", uri, e);
        }
    }

    /** Исполняемый файл клиента Steam или null. */
    public static Path executable() {
        String name = WINDOWS ? "steam.exe" : "steam";
        for (Path root : roots()) {
            Path exe = root.resolve(name);
            if (Files.isRegularFile(exe)) {
                return exe;
            }
        }
        return null;
    }

    /** Корневые каталоги установок Steam, которые реально существуют. */
    public static List<Path> roots() {
        List<Path> roots = new ArrayList<>();
        if (WINDOWS) {
            String reg = queryRegistry();
            if (reg != null) {
                roots.add(Paths.get(reg));
            }
            roots.add(Paths.get("C:\\Program Files (x86)\\Steam"));
            roots.add(Paths.get("C:\\Program Files\\Steam"));
        } else {
            String home = System.getProperty("user.home", "");
            roots.add(Paths.get(home, ".local", "share", "Steam"));
            roots.add(Paths.get(home, ".steam", "steam"));
            roots.add(Paths.get(home, ".var", "app", "com.valvesoftware.Steam",
                    "data", "Steam"));
        }
        roots.removeIf(p -> !Files.isDirectory(p));
        return roots;
    }

    /** Значение HKCU\Software\Valve\Steam\SteamPath, либо null. */
    private static String queryRegistry() {
        try {
            Process p = new ProcessBuilder("reg", "query",
                    "HKCU\\Software\\Valve\\Steam", "/v", "SteamPath")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            p.waitFor();
            for (String line : out.split("\\R")) {
                int i = line.indexOf("REG_SZ");
                if (i >= 0) {
                    return line.substring(i + "REG_SZ".length()).trim();
                }
            }
        } catch (IOException | InterruptedException ignored) {
            // реестр недоступен — остаются пути по умолчанию
        }
        return null;
    }

    static String fileName(String path) {
        int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return path.substring(cut + 1);
    }
}

package org.genius8loci.portholeomnis;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Подстраховка на случай, когда Minecraft умирает так, что shutdown hook не отрабатывает:
 * жёсткий вылет JVM, kill из лаунчера, отключение питания.
 *
 * <p>Каждый запущенный процесс Porthole записывается в файл рядом с игрой, а при следующем
 * старте мода осиротевшие процессы добиваются. Кроме PID хранится момент старта процесса:
 * без него после перезагрузки можно было бы убить чужой процесс, которому ОС выдала
 * переиспользованный PID.
 *
 * <p>Файл заводится отдельный на каждый экземпляр игры и назван по PID её JVM. Иначе вторая
 * запущенная копия Minecraft при старте убивала бы туннель первой — а это ровно тот случай,
 * когда хост и гость сидят на одной машине.
 */
final class PortholeWatchdog {

    private static final String PREFIX = ".portholeomnis-processes-";
    private static final long OWNER = ProcessHandle.current().pid();

    /** PID → момент старта в миллисекундах эпохи, 0 если ОС его не отдала. */
    private static final Map<Long, Long> tracked = new LinkedHashMap<>();

    private PortholeWatchdog() {
    }

    static synchronized void track(Process process) {
        long pid = process.pid();
        long start = process.info().startInstant().map(Instant::toEpochMilli).orElse(0L);
        tracked.put(pid, start);
        flush();
    }

    static synchronized void untrack(long pid) {
        if (tracked.remove(pid) != null) {
            flush();
        }
    }

    /** Добивает процессы, оставшиеся от прошлых запусков. Зовётся один раз при инициализации. */
    static synchronized void killLeftovers() {
        Path dir = gameDir();
        if (dir == null) {
            return;
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(p -> p.getFileName().toString().startsWith(PREFIX))
                    .forEach(files::add);
        } catch (IOException e) {
            PortholeOmnis.LOGGER.warn("не удалось прочитать каталог игры", e);
            return;
        }
        for (Path file : files) {
            long owner = ownerOf(file);
            // Наш собственный PID сюда попасть не может — файл ещё не создан, значит
            // это остатки от процесса, чей PID ОС успела переиспользовать под нас.
            if (owner != OWNER && owner > 0 && isAlive(owner)) {
                continue; // файлом владеет живая копия игры, не лезем
            }
            killAll(file);
        }
    }

    private static void killAll(Path file) {
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length != 2) {
                    continue;
                }
                try {
                    kill(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
                } catch (NumberFormatException malformed) {
                    // битая строка — пропускаем, файл всё равно удаляется целиком
                }
            }
            Files.deleteIfExists(file);
        } catch (IOException e) {
            PortholeOmnis.LOGGER.warn("не удалось обработать {}", file, e);
        }
    }

    private static void kill(long pid, long start) {
        ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
        if (handle == null || !handle.isAlive()) {
            return;
        }
        ProcessHandle.Info info = handle.info();

        // Оба условия обязательны: имя отсекает чужой процесс с тем же PID,
        // момент старта — случай, когда ОС переиспользовала PID под другой Porthole,
        // запущенный пользователем вручную уже после падения игры.
        String command = info.command().orElse("");
        if (!SteamClient.fileName(command).equalsIgnoreCase(PortholeLocator.binaryName())) {
            return;
        }
        long actualStart = info.startInstant().map(Instant::toEpochMilli).orElse(0L);
        if (start != 0 && actualStart != 0 && start != actualStart) {
            return;
        }

        PortholeOmnis.LOGGER.info("[porthole] добиваем процесс {} от прошлого запуска", pid);
        handle.destroy();
    }

    private static boolean isAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static long ownerOf(Path file) {
        String name = file.getFileName().toString();
        try {
            return Long.parseLong(name.substring(PREFIX.length()));
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            return -1;
        }
    }

    private static void flush() {
        Path file = file();
        if (file == null) {
            return;
        }
        try {
            if (tracked.isEmpty()) {
                Files.deleteIfExists(file);
                return;
            }
            List<String> lines = new ArrayList<>(tracked.size());
            for (Map.Entry<Long, Long> e : tracked.entrySet()) {
                lines.add(e.getKey() + " " + e.getValue());
            }
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            PortholeOmnis.LOGGER.warn("не удалось обновить {}", file, e);
        }
    }

    private static Path file() {
        Path dir = gameDir();
        return dir == null ? null : dir.resolve(PREFIX + OWNER);
    }

    private static Path gameDir() {
        try {
            return FabricLoader.getInstance().getGameDir();
        } catch (RuntimeException e) {
            PortholeOmnis.LOGGER.warn("не удалось определить каталог игры", e);
            return null;
        }
    }
}

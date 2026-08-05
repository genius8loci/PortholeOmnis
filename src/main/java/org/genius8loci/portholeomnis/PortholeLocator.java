package org.genius8loci.portholeomnis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Поиск porthole.exe / porthole в установленной библиотеке Steam. */
public final class PortholeLocator {

    private static final String INSTALL_DIR = "porthole";
    private static final String OS = System.getProperty("os.name", "").toLowerCase();
    private static final boolean WINDOWS = OS.contains("win");
    /** Porthole в Steam выходит только под Windows и Linux/SteamOS. */
    private static final boolean SUPPORTED_OS = !OS.contains("mac");
    private static final String EXE = WINDOWS ? "porthole.exe" : "porthole";

    /** "path"  "D:\\SteamLibrary" — путь в vdf экранирован по правилам C. */
    private static final Pattern VDF_PATH = Pattern.compile("\"path\"\\s+\"(.+?)\"");

    private PortholeLocator() {
    }

    /** Имя процесса Porthole — по нему опознаются осиротевшие процессы. */
    public static String binaryName() {
        return EXE;
    }

    /**
     * Причина, по которой Porthole использовать нельзя, как короткий идентификатор
     * (см. {@link PortholeOmnis#key(String)}), либо null, если всё на месте.
     *
     * <p>macOS вынесен в отдельную ветку намеренно: сборки под неё не существует, и
     * «Porthole не найден» отправило бы человека искать в Steam то, чего там нет.
     */
    public static String unavailableId() {
        if (!SUPPORTED_OS) {
            return "porthole.unsupported_os";
        }
        return locate() == null ? "porthole.not_found" : null;
    }

    /** @return путь к бинарнику или null, если не найден. */
    public static Path locate() {
        String override = System.getenv("PORTHOLE_EXE");
        if (override != null && !override.isBlank()) {
            Path p = Paths.get(override);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        for (Path root : SteamClient.roots()) {
            for (Path lib : libraryFolders(root)) {
                Path exe = lib.resolve("steamapps").resolve("common")
                        .resolve(INSTALL_DIR).resolve(EXE);
                if (Files.isRegularFile(exe)) {
                    return exe;
                }
            }
        }
        return null;
    }

    /** Корень Steam плюс все дополнительные библиотеки из libraryfolders.vdf. */
    static List<Path> libraryFolders(Path steamRoot) {
        List<Path> libs = new ArrayList<>();
        libs.add(steamRoot);
        Path vdf = steamRoot.resolve("steamapps").resolve("libraryfolders.vdf");
        if (!Files.isRegularFile(vdf)) {
            return libs;
        }
        try {
            String text = Files.readString(vdf, StandardCharsets.UTF_8);
            Matcher m = VDF_PATH.matcher(text);
            while (m.find()) {
                libs.add(Paths.get(m.group(1).replace("\\\\", "\\")));
            }
        } catch (IOException ignored) {
            // повреждённый vdf не должен ронять мод
        }
        return libs;
    }
}

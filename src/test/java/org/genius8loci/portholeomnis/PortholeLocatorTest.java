package org.genius8loci.portholeomnis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Разбор libraryfolders.vdf — единственная часть поиска, не зависящая от Minecraft. */
class PortholeLocatorTest {

    @TempDir
    Path steamRoot;

    @Test
    void returnsRootItselfWhenVdfIsAbsent() {
        List<Path> libs = PortholeLocator.libraryFolders(steamRoot);

        assertEquals(List.of(steamRoot), libs);
    }

    @Test
    void addsEveryLibraryPathFromVdf() throws IOException {
        writeVdf("""
                "libraryfolders"
                {
                	"0"
                	{
                		"path"		"C:\\\\Program Files (x86)\\\\Steam"
                		"label"		""
                	}
                	"1"
                	{
                		"path"		"D:\\\\SteamLibrary"
                		"label"		""
                	}
                }
                """);

        List<Path> libs = PortholeLocator.libraryFolders(steamRoot);

        // Корень всегда первый, дальше — то, что нашлось в файле.
        assertEquals(3, libs.size());
        assertEquals(steamRoot, libs.get(0));
        assertEquals(Path.of("C:\\Program Files (x86)\\Steam"), libs.get(1));
        assertEquals(Path.of("D:\\SteamLibrary"), libs.get(2));
    }

    /** Пути в vdf экранированы по правилам C: удвоенный слэш должен схлопнуться. */
    @Test
    void unescapesDoubledBackslashes() throws IOException {
        writeVdf("\"path\"\t\t\"D:\\\\Games\\\\SteamLibrary\"\n");

        List<Path> libs = PortholeLocator.libraryFolders(steamRoot);

        assertEquals(Path.of("D:\\Games\\SteamLibrary"), libs.get(1));
    }

    /** Linux-пути слэшей не экранируют — трогать их не надо. */
    @Test
    void keepsForwardSlashPathsIntact() throws IOException {
        writeVdf("\"path\"\t\t\"/home/user/games/SteamLibrary\"\n");

        List<Path> libs = PortholeLocator.libraryFolders(steamRoot);

        assertEquals(Path.of("/home/user/games/SteamLibrary"), libs.get(1));
    }

    /** Повреждённый vdf не должен ронять мод: корень возвращается в любом случае. */
    @Test
    void survivesGarbage() throws IOException {
        writeVdf("не vdf вовсе, \"path\" без закрывающей кавычки");

        List<Path> libs = PortholeLocator.libraryFolders(steamRoot);

        assertTrue(libs.contains(steamRoot));
    }

    private void writeVdf(String content) throws IOException {
        Path steamapps = Files.createDirectories(steamRoot.resolve("steamapps"));
        Files.writeString(steamapps.resolve("libraryfolders.vdf"), content,
                StandardCharsets.UTF_8);
    }
}

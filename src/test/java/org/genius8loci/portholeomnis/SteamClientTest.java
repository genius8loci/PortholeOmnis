package org.genius8loci.portholeomnis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * По {@code fileName} опознаются и клиент Steam, и осиротевшие процессы Porthole,
 * поэтому разделители обеих ОС он обязан понимать независимо от того, где запущен.
 */
class SteamClientTest {

    @Test
    void cutsWindowsPath() {
        assertEquals("steam.exe",
                SteamClient.fileName("C:\\Program Files (x86)\\Steam\\steam.exe"));
    }

    @Test
    void cutsUnixPath() {
        assertEquals("steam", SteamClient.fileName("/usr/bin/steam"));
    }

    /** ProcessHandle на Windows иногда отдаёт путь со смешанными разделителями. */
    @Test
    void cutsMixedSeparators() {
        assertEquals("porthole.exe",
                SteamClient.fileName("D:/SteamLibrary\\steamapps/common\\porthole\\porthole.exe"));
    }

    @Test
    void keepsBareName() {
        assertEquals("porthole", SteamClient.fileName("porthole"));
    }

    @Test
    void handlesEmptyInput() {
        // info().command() пустой строки не отдаёт, но и падать здесь не на чем.
        assertEquals("", SteamClient.fileName(""));
    }

    @Test
    void handlesTrailingSeparator() {
        assertEquals("", SteamClient.fileName("C:\\Steam\\"));
    }
}

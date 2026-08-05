package org.genius8loci.portholeomnis;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Разбор событий `porthole expose --json`. Схема сырая, так что проверяем и мусор. */
class PortholeLauncherTest {

    private final List<String> codes = new ArrayList<>();
    private final List<String> info = new ArrayList<>();

    @Test
    void lobbyReadyGivesCode() {
        handle("{\"event\":\"lobby_ready\",\"code\":\"ABCD-1234\"}");

        assertEquals(List.of("ABCD-1234"), codes);
        assertTrue(info.isEmpty());
    }

    /**
     * lobby_id — u64: 18446744073709551615 не влезает в точный диапазон double,
     * поэтому читаться он обязан только через getAsLong.
     */
    @Test
    void hugeLobbyIdDoesNotBreakCode() {
        handle("{\"event\":\"lobby_ready\",\"code\":\"XYZ\",\"lobby_id\":109775241059178867}");

        assertEquals(List.of("XYZ"), codes);
    }

    @Test
    void lobbyReadyWithoutCodeIsIgnored() {
        handle("{\"event\":\"lobby_ready\",\"lobby_id\":1}");

        assertTrue(codes.isEmpty());
        assertTrue(info.isEmpty());
    }

    @Test
    void errorGoesToChatWithItsMessage() {
        handle("{\"event\":\"error\",\"message\":\"steam offline\"}");

        assertEquals(List.of("porthole.error:steam offline"), info);
        assertTrue(codes.isEmpty());
    }

    @Test
    void errorWithoutMessageStillReported() {
        handle("{\"event\":\"error\"}");

        assertEquals(List.of("porthole.error:"), info);
    }

    /** Неизвестное событие с текстом уходит в лог, но не в чат и не в код лобби. */
    @Test
    void unknownEventIsSilent() {
        handle("{\"event\":\"peer_joined\",\"message\":\"кто-то зашёл\"}");

        assertTrue(codes.isEmpty());
        assertTrue(info.isEmpty());
    }

    @Test
    void objectWithoutEventIsSilent() {
        handle("{\"message\":\"просто строка\"}");

        assertTrue(codes.isEmpty());
        assertTrue(info.isEmpty());
    }

    /** Нестроковый event не должен ронять разбор (getAsString на объекте кидает). */
    @Test
    void nonPrimitiveEventIsSilent() {
        handle("{\"event\":{\"nested\":true}}");

        assertTrue(codes.isEmpty());
        assertTrue(info.isEmpty());
    }

    private void handle(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        PortholeLauncher.handle(o, codes::add, info::add);
    }
}

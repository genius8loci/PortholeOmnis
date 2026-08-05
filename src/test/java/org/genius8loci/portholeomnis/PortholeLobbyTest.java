package org.genius8loci.portholeomnis;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Разбор событий лобби. Схема снята с живого прогона хост + гость на разных
 * аккаунтах, строки ниже — дословно оттуда.
 */
class PortholeLobbyTest {

    private static final long HOST = 76561199561112264L;
    private static final long GUEST = 76561198774076344L;

    private final List<String> codes = new ArrayList<>();
    private final List<String> info = new ArrayList<>();

    @BeforeEach
    void reset() {
        PortholeLobby.begin(true);
        codes.clear();
        info.clear();
    }

    private void feed(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        PortholeLauncher.handle(o, codes::add, info::add);
    }

    @Test
    void tracksPeerAcrossConnectAndDisconnect() {
        feed("{\"event\":\"peer_connected\",\"steam_id\":\"" + GUEST + "\"}");
        assertEquals(List.of(GUEST), PortholeLobby.peers());

        feed("{\"event\":\"peer_disconnected\",\"reason\":\"RemoteTimeout\",\"steam_id\":\"" + GUEST + "\"}");
        assertTrue(PortholeLobby.peers().isEmpty());
    }

    /** ready и port_accepted приходят следом за peer_connected на того же пира. */
    @Test
    void repeatedPresenceEventsDoNotDuplicatePeer() {
        feed("{\"event\":\"peer_connected\",\"steam_id\":\"" + GUEST + "\"}");
        feed("{\"event\":\"ready\",\"steam_id\":\"" + GUEST + "\"}");
        feed("{\"event\":\"port_accepted\",\"port\":25565,\"proto\":\"tcp\",\"steam_id\":\"" + GUEST + "\"}");

        assertEquals(List.of(GUEST), PortholeLobby.peers());
    }

    /**
     * Регрессия: дедуп по сырой строке глотал повторный вход — строка peer_connected
     * для того же Steam ID байт в байт совпадает с прошлой. Гость заходил второй раз
     * и в панели не появлялся.
     *
     * <p>Идёт через readEvents, а не через handle: дедуп живёт именно в разборе потока,
     * и тест мимо него ничего бы не проверил.
     */
    @Test
    void peerCanRejoinAfterLeaving() throws IOException {
        String connected = "{\"event\":\"peer_connected\",\"steam_id\":\"" + GUEST + "\"}";
        stream(connected,
                "{\"event\":\"peer_disconnected\",\"reason\":\"RemoteTimeout\",\"steam_id\":\"" + GUEST + "\"}",
                connected);

        assertEquals(List.of(GUEST), PortholeLobby.peers());
    }

    /** Пачки одинаковых копий подряд Porthole шлёт постоянно — их гасить надо. */
    @Test
    void consecutiveDuplicatesAreCollapsed() throws IOException {
        String joined = "{\"event\":\"info\",\"message\":\"member valkdz joined\"}";
        stream(joined, joined, joined,
                "{\"code\":\"7RFV58\",\"event\":\"lobby_ready\",\"lobby_id\":1}");

        assertEquals(1, codes.size());
        assertEquals("7RFV58", codes.get(0));
    }

    /** Пинг обязан обновляться: одинаковые подряд гасятся, изменившиеся — нет. */
    @Test
    void changingPingGetsThrough() throws IOException {
        stream("{\"event\":\"link\",\"ping_ms\":17,\"quality\":1.0,\"steam_id\":\"" + GUEST + "\"}",
                "{\"event\":\"link\",\"ping_ms\":17,\"quality\":1.0,\"steam_id\":\"" + GUEST + "\"}",
                "{\"event\":\"link\",\"ping_ms\":42,\"quality\":1.0,\"steam_id\":\"" + GUEST + "\"}");

        assertEquals(42, PortholeLobby.link(GUEST).pingMs());
    }

    /** Первые строки печатает steam_api64.dll — не JSON, разбор их обязан пережить. */
    @Test
    void nonJsonPreambleIsSkipped() throws IOException {
        stream("Setting breakpad minidump AppID = 4963920",
                "SteamInternal_SetMinidumpSteamID:  Caching Steam ID:  765 [API loaded no]",
                "{\"code\":\"7RFV58\",\"event\":\"lobby_ready\",\"lobby_id\":1}");

        assertEquals("7RFV58", PortholeLobby.shareCode());
    }

    /**
     * Плашка на вход должна быть одна: ready и port_accepted приходят следом за
     * peer_connected и про то же самое подключение.
     */
    @Test
    void oneJoinRaisesOneEvent() {
        feed("{\"event\":\"peer_connected\",\"steam_id\":\"" + GUEST + "\"}");
        feed("{\"event\":\"ready\",\"steam_id\":\"" + GUEST + "\"}");
        feed("{\"event\":\"port_accepted\",\"port\":25565,\"proto\":\"tcp\",\"steam_id\":\"" + GUEST + "\"}");

        assertEquals(List.of(new PortholeLobby.Event(GUEST, true)), drainEvents());
    }

    /** Выход неизвестного пира плашки не заслуживает: показывать нечего. */
    @Test
    void leavingRaisesEventOnlyForKnownPeer() {
        String left = "{\"event\":\"peer_disconnected\",\"reason\":\"RemoteTimeout\",\"steam_id\":\""
                + GUEST + "\"}";
        feed(left);
        assertEquals(List.of(), drainEvents());

        feed("{\"event\":\"peer_connected\",\"steam_id\":\"" + GUEST + "\"}");
        drainEvents();
        feed(left);

        assertEquals(List.of(new PortholeLobby.Event(GUEST, false)), drainEvents());
    }

    /** Туннель свернулся — рассказывать про уход каждого гостя было бы враньём. */
    @Test
    void endDropsPendingEvents() {
        feed("{\"event\":\"peer_connected\",\"steam_id\":\"" + GUEST + "\"}");
        PortholeLobby.end();

        assertEquals(List.of(), drainEvents());
    }

    private List<PortholeLobby.Event> drainEvents() {
        List<PortholeLobby.Event> out = new ArrayList<>();
        PortholeLobby.Event event;
        while ((event = PortholeLobby.pollEvent()) != null) {
            out.add(event);
        }
        return out;
    }

    private void stream(String... lines) throws IOException {
        BufferedReader r = new BufferedReader(new StringReader(String.join("\n", lines)));
        PortholeLauncher.readEvents(r, codes::add, info::add);
    }

    /** Процесс кончился — показывать «подключён» больше некого. */
    @Test
    void endClearsEverything() {
        feed("{\"code\":\"7RFV58\",\"event\":\"lobby_ready\",\"lobby_id\":1}");
        feed("{\"event\":\"peer_connected\",\"steam_id\":\"" + GUEST + "\"}");
        feed("{\"event\":\"link\",\"ping_ms\":17,\"quality\":1.0,\"steam_id\":\"" + GUEST + "\"}");

        PortholeLobby.end();

        assertTrue(PortholeLobby.peers().isEmpty());
        assertNull(PortholeLobby.link(GUEST));
        assertNull(PortholeLobby.shareCode());
    }

    @Test
    void linkTelemetryIsStoredPerPeer() {
        feed("{\"event\":\"link\",\"ping_ms\":17,\"quality\":1.0,\"in_bps\":10.5,"
                + "\"out_bps\":5.9,\"steam_id\":\"" + GUEST + "\"}");

        PortholeLobby.Link link = PortholeLobby.link(GUEST);
        assertEquals(17, link.pingMs());
        assertEquals(1.0f, link.quality());
    }

    @Test
    void linkIsForgottenOnDisconnect() {
        feed("{\"event\":\"link\",\"ping_ms\":17,\"quality\":1.0,\"steam_id\":\"" + GUEST + "\"}");
        feed("{\"event\":\"peer_disconnected\",\"reason\":\"RemoteTimeout\",\"steam_id\":\"" + GUEST + "\"}");

        assertNull(PortholeLobby.link(GUEST));
    }

    /** Свой Steam ID отдельного поля не имеет — только внутри info-сообщения. */
    @Test
    void ownSteamIdComesFromInfoMessage() {
        feed("{\"event\":\"info\",\"message\":\"steam id: " + HOST + "\"}");

        assertEquals(HOST, PortholeLobby.hostSteamId());
    }

    @Test
    void lobbyReadyGivesShareCode() {
        feed("{\"code\":\"7RFV58\",\"event\":\"lobby_ready\",\"lobby_id\":109775241177722024}");

        assertEquals("7RFV58", PortholeLobby.shareCode());
    }

    /**
     * info/member — свободный текст: там оказывается то ник, то Steam ID цифрами,
     * смотря разрезолвил ли Steam имя. Учёт участников на него опираться не должен.
     */
    @Test
    void memberInfoDoesNotAffectPeerList() {
        feed("{\"event\":\"info\",\"message\":\"member valkdz joined\"}");
        feed("{\"event\":\"info\",\"message\":\"member " + GUEST + " joined\"}");

        assertTrue(PortholeLobby.peers().isEmpty());
    }

    @Test
    void parsesOnlyPlausibleSteamIds() {
        assertEquals(GUEST, PortholeLobby.parseSteamId(String.valueOf(GUEST)));
        assertEquals(0, PortholeLobby.parseSteamId("valkdz"));
        assertEquals(0, PortholeLobby.parseSteamId(""));
        assertEquals(0, PortholeLobby.parseSteamId(null));
        // Ниже базы SteamID64 — не аккаунт.
        assertEquals(0, PortholeLobby.parseSteamId("12345"));
        // Длиннее u64: parseLong кинет, а мод упасть не должен.
        assertEquals(0, PortholeLobby.parseSteamId("99999999999999999999999"));
    }

    @Test
    void relayModeIsRememberedFromLaunchFlag() {
        PortholeLobby.begin(false);
        assertEquals(false, PortholeLobby.isRelay());
        PortholeLobby.begin(true);
        assertEquals(true, PortholeLobby.isRelay());
    }
}

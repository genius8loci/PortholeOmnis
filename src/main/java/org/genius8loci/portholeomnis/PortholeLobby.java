package org.genius8loci.portholeomnis;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Состояние текущего лобби хоста: код, режим передачи и кто в нём сидит.
 *
 * <p>Наполняется из потока событий {@code porthole expose --json}. Схема снята
 * с живого прогона хост + гость на разных аккаунтах:
 * <pre>
 * {"event":"info","message":"steam id: 76561199561112264"}
 * {"code":"7RFV58","event":"lobby_ready","lobby_id":109775241177722024}
 * {"event":"info","message":"member valkdz joined"}
 * {"event":"peer_connected","steam_id":"76561198774076344"}
 * {"event":"ready","steam_id":"76561198774076344"}
 * {"event":"port_accepted","port":25565,"proto":"tcp","steam_id":"…"}
 * {"event":"link","ping_ms":17,"quality":1.0,"in_bps":…,"out_bps":…,"steam_id":"…"}
 * {"event":"info","message":"member valkdz left"}
 * {"event":"peer_disconnected","reason":"RemoteTimeout","steam_id":"…"}
 * </pre>
 *
 * <p>Участники считаются по {@code peer_connected} / {@code peer_disconnected},
 * а не по {@code info/member}: там Steam ID лежит отдельным полем, тогда как в info
 * это свободный текст, где в зависимости от того, разрезолвил ли Steam имя,
 * оказывается то ник, то тот же Steam ID цифрами.
 *
 * <p>Пишется из потока разбора stdout, читается из клиентского — отсюда volatile
 * и синхронизация на списке.
 */
public final class PortholeLobby {

    /** Качество канала до пира. quality — доля доставленных пакетов, 0..1. */
    public record Link(int pingMs, float quality) {
    }

    /** Кто-то вошёл или вышел. Забирается отрисовкой плашек. */
    public record Event(long steamId, boolean joined) {
    }

    /**
     * Хвост очереди, если её никто не забирает. На экране всё равно помещается
     * пара плашек, а копить события бесконечно нельзя.
     */
    private static final int EVENT_LIMIT = 16;

    private static final Set<Long> peers = new LinkedHashSet<>();
    private static final Map<Long, Link> links = new ConcurrentHashMap<>();
    private static final Queue<Event> events = new ConcurrentLinkedQueue<>();

    private static volatile String shareCode;
    private static volatile long hostSteamId;
    private static volatile boolean relay;

    private PortholeLobby() {
    }

    /** Туннель поднят и код известен — только тогда панель имеет что показывать. */
    public static boolean isActive() {
        return shareCode != null && PortholeLauncher.isRunning();
    }

    public static String shareCode() {
        return shareCode;
    }

    public static long hostSteamId() {
        return hostSteamId;
    }

    public static boolean isRelay() {
        return relay;
    }

    /** Steam ID подключённых пиров в порядке подключения. */
    public static List<Long> peers() {
        synchronized (peers) {
            return new ArrayList<>(peers);
        }
    }

    /** Канал до пира или null, если телеметрия ещё не приходила. */
    public static Link link(long steamId) {
        return links.get(steamId);
    }

    /** @return следующее событие входа/выхода или null, если очередь пуста */
    public static Event pollEvent() {
        return events.poll();
    }

    private static void emit(Event event) {
        events.add(event);
        while (events.size() > EVENT_LIMIT) {
            events.poll();
        }
    }

    static synchronized void begin(boolean forceRelay) {
        relay = forceRelay;
        shareCode = null;
        hostSteamId = 0;
        links.clear();
        events.clear();
        synchronized (peers) {
            peers.clear();
        }
    }

    /**
     * Процесс кончился: держать список тех, кто «подключён», больше нельзя.
     *
     * <p>Плашек «вышел» на всех разом не будет: ушёл не гость, а туннель целиком,
     * и пачка уведомлений про это только соврала бы.
     */
    static synchronized void end() {
        shareCode = null;
        links.clear();
        events.clear();
        synchronized (peers) {
            peers.clear();
        }
    }

    static void link(long steamId, int pingMs, float quality) {
        if (steamId != 0) {
            links.put(steamId, new Link(pingMs, quality));
        }
    }

    static void code(String code) {
        shareCode = code;
    }

    static void peerConnected(long steamId) {
        if (steamId == 0) {
            return;
        }
        boolean added;
        synchronized (peers) {
            added = peers.add(steamId);
        }
        // Один вход даёт три события — peer_connected, ready, port_accepted.
        // Плашка нужна одна, поэтому смотрим на то, изменился ли список.
        if (added) {
            emit(new Event(steamId, true));
        }
    }

    static void peerDisconnected(long steamId) {
        links.remove(steamId);
        boolean removed;
        synchronized (peers) {
            removed = peers.remove(steamId);
        }
        if (removed) {
            emit(new Event(steamId, false));
        }
    }

    /**
     * Свой Steam ID приходит только внутри {@code info}-сообщения вида
     * {@code "steam id: 765..."} — отдельного поля под него в схеме нет.
     */
    static void parseInfo(String message) {
        String prefix = "steam id: ";
        if (!message.startsWith(prefix)) {
            return;
        }
        hostSteamId = parseSteamId(message.substring(prefix.length()).trim());
    }

    /** @return Steam ID или 0, если строка им не является. */
    static long parseSteamId(String raw) {
        if (raw == null || raw.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) < '0' || raw.charAt(i) > '9') {
                return 0;
            }
        }
        try {
            long id = Long.parseLong(raw);
            // SteamID64 индивидуальных аккаунтов начинается с 7656119…
            return id > 76561197960265728L ? id : 0;
        } catch (NumberFormatException tooLong) {
            return 0;
        }
    }
}

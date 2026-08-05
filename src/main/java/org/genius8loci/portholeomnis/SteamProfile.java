package org.genius8loci.portholeomnis;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ник и аватарка по Steam ID через публичный XML профиля.
 *
 * <p>{@code https://steamcommunity.com/profiles/<id>?xml=1} отдаёт, среди прочего,
 * {@code <steamID><![CDATA[ник]]></steamID>} и {@code <avatarFull><![CDATA[url]]></avatarFull>}.
 * Ключ Web API не нужен, но и гарантий никаких: это не документированный API, а
 * XML-вид страницы профиля. Всё, что отсюда не пришло, деградирует до Steam ID.
 *
 * <p>Важно: {@code avatarFull} и {@code steamID} встречаются в документе многократно —
 * дальше идут блоки друзей и групп. Берётся строго первое вхождение, иначе в панели
 * окажется чужое лицо.
 *
 * <p>Сеть и декодирование PNG/JPEG — только в фоне; на клиентский поток выносится
 * ровно регистрация текстуры, её иначе делать нельзя.
 */
public final class SteamProfile {

    /** Приватный профиль или сеть без интернета — не повод долбиться каждый кадр. */
    private static final long RETRY_AFTER_MILLIS = 5 * 60 * 1000;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final Pattern STEAM_NAME = Pattern.compile(
            "<steamID>\\s*(?:<!\\[CDATA\\[)?\\s*(.*?)\\s*(?:\\]\\]>)?\\s*</steamID>", Pattern.DOTALL);
    private static final Pattern AVATAR = Pattern.compile(
            "<avatarFull>\\s*(?:<!\\[CDATA\\[)?\\s*(\\S+?)\\s*(?:\\]\\]>)?\\s*</avatarFull>", Pattern.DOTALL);

    /** Один поток: профилей за сессию единицы, параллелить нечего. */
    private static final Executor LOADER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "portholeomnis-steam-profile");
        t.setDaemon(true);
        return t;
    });

    private static final Map<Long, Entry> cache = new ConcurrentHashMap<>();
    private static volatile HttpClient http;

    /**
     * Ник и текстура аватарки; ник или текстура могут быть null, если не доехали.
     * {@code avatarSize} — сторона исходной картинки: drawTexture требует её знать,
     * а Steam отдаёт avatarFull 184×184 без всякой гарантии, что так будет всегда.
     */
    public record Profile(String personaName, Identifier avatar, int avatarSize) {
    }

    private static final class Entry {
        volatile Profile profile;
        volatile boolean loading;
        volatile long failedAt;
    }

    private SteamProfile() {
    }

    /**
     * @return профиль, если он уже загружен, иначе null — и загрузка стартует в фоне.
     *         Звать можно хоть каждый кадр: всё, кроме первого раза, это чтение мапы.
     */
    public static Profile get(long steamId) {
        if (steamId == 0) {
            return null;
        }
        Entry e = cache.computeIfAbsent(steamId, id -> new Entry());
        if (e.profile != null) {
            return e.profile;
        }
        if (!e.loading && System.currentTimeMillis() - e.failedAt > RETRY_AFTER_MILLIS) {
            e.loading = true;
            LOADER.execute(() -> load(steamId, e));
        }
        return null;
    }

    private static void load(long steamId, Entry e) {
        try {
            String xml = fetchString("https://steamcommunity.com/profiles/" + steamId + "?xml=1");
            String name = firstGroup(STEAM_NAME, xml);
            String avatarUrl = firstGroup(AVATAR, xml);

            Identifier texture = null;
            int size = 0;
            if (avatarUrl != null) {
                NativeImage image = NativeImage.read(new ByteArrayInputStream(fetchBytes(avatarUrl)));
                size = image.getWidth();
                texture = registerAvatar(steamId, image);
            }
            if (name == null && texture == null) {
                fail(steamId, e, "профиль без ника и аватарки (закрыт?)");
                return;
            }
            e.profile = new Profile(name, texture, size);
            PortholeOmnis.LOGGER.info("[steam] профиль {} → {}", steamId, name);
        } catch (IOException | RuntimeException failed) {
            fail(steamId, e, failed.toString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail(steamId, e, "прервано");
        } finally {
            e.loading = false;
        }
    }

    private static void fail(long steamId, Entry e, String why) {
        e.failedAt = System.currentTimeMillis();
        PortholeOmnis.LOGGER.warn("[steam] не удалось получить профиль {}: {}", steamId, why);
    }

    /**
     * Текстура регистрируется на клиентском потоке, но ссылку возвращаем сразу:
     * до первой отрисовки регистрация успевает, а если нет — кадр-другой пусто.
     */
    private static Identifier registerAvatar(long steamId, NativeImage image) {
        Identifier id = new Identifier(PortholeOmnis.MOD_ID, "steam_avatar/" + steamId);
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            try {
                client.getTextureManager().registerTexture(id, new NativeImageBackedTexture(image));
            } catch (RuntimeException e) {
                PortholeOmnis.LOGGER.warn("[steam] текстура {} не зарегистрировалась", id, e);
                image.close();
            }
        });
        return id;
    }

    private static String fetchString(String url) throws IOException, InterruptedException {
        HttpResponse<String> r = client().send(request(url), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new IOException("HTTP " + r.statusCode() + " от " + url);
        }
        return r.body();
    }

    private static byte[] fetchBytes(String url) throws IOException, InterruptedException {
        HttpResponse<byte[]> r = client().send(request(url), HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200) {
            throw new IOException("HTTP " + r.statusCode() + " от " + url);
        }
        return r.body();
    }

    private static HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("User-Agent", PortholeOmnis.MOD_ID)
                .GET()
                .build();
    }

    private static HttpClient client() {
        HttpClient c = http;
        if (c == null) {
            c = HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            http = c;
        }
        return c;
    }

    /** Первое вхождение: дальше в документе идут друзья и группы со своими тегами. */
    private static String firstGroup(Pattern p, String xml) {
        Matcher m = p.matcher(xml);
        if (!m.find()) {
            return null;
        }
        String value = m.group(1).trim();
        return value.isEmpty() ? null : value;
    }
}

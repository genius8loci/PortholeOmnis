package org.genius8loci.portholeomnis;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.List;

/**
 * Отрисовка сводки по лобби: общая для панели в меню паузы и для отдельного экрана
 * со списком целиком.
 *
 * <p>Ничего не кэширует: {@link PortholeLobby} обновляется потоком разбора stdout,
 * и панель обязана показывать его состояние на текущий кадр — иначе пинг замирает.
 *
 * <p>Фон тёмно-синий и непрозрачный, текст белый с тенью — так же, как в ванильных
 * экранах: на тёмном это самый читаемый вариант, а мир за панелью не просвечивает
 * и не рябит под текстом.
 */
public final class PortholeLobbyPanel {

    public static final int WIDTH_MAX = 160;
    /** Уже этого не читается — лучше кнопка и отдельный экран. */
    public static final int WIDTH_MIN = 110;

    /** Отступ от рамки до содержимого. */
    public static final int PAD = 6;
    public static final int ROW = 20;
    public static final int BUTTON_HEIGHT = 20;

    private static final int LINE = 12;
    private static final int AVATAR = 16;

    /** Смещения внутри шапки, от её верхнего края. */
    public static final int CODE_Y = LINE;
    public static final int COPY_BUTTON_Y = LINE * 2 + 2;
    private static final int MODE_Y = COPY_BUTTON_Y + BUTTON_HEIGHT + 4;
    private static final int COUNT_Y = MODE_Y + LINE + 2;
    /** Высота шапки: ниже начинается список участников. */
    public static final int HEADER = COUNT_Y + LINE + 4;

    /** Непрозрачный: панель не должна просвечивать миром, который за ней. */
    private static final int BACKGROUND = 0xFF16233F;
    /** Контур светлее заливки — тёмный на тёмном не виден. Тот же синий, что у кнопок. */
    public static final int BORDER = 0xFF3C6FE0;
    /** Разделитель между шапкой и списком — тот же синий, приглушённый. */
    private static final int SEPARATOR = 0x553C6FE0;

    private static final int TITLE = 0x8FB8FF;
    private static final int CODE = 0x55FF55;
    private static final int LABEL = 0xA8B4C8;
    private static final int NAME = 0xFFFFFF;
    private static final int PLACEHOLDER = 0x30FFFFFF;

    private static final int PING_GOOD = 0x55FF55;
    private static final int PING_FAIR = 0xFFFF55;
    private static final int PING_BAD = 0xFF5555;

    private PortholeLobbyPanel() {
    }

    /** Сколько строк участников влезет в панель такой высоты. */
    public static int rowsThatFit(int panelHeight) {
        return Math.max((panelHeight - PAD * 2 - HEADER) / ROW, 0);
    }

    /** Высота панели, чтобы показать столько строк без обрезки. */
    public static int heightFor(int rows) {
        return PAD * 2 + HEADER + Math.max(rows, 1) * ROW;
    }

    /** Непрозрачная тёмно-синяя заливка и светлый контур в один пиксель. */
    public static void drawFrame(DrawContext context, int left, int top, int right, int bottom) {
        context.fill(left, top, right, bottom, BACKGROUND);
        context.fill(left, top, right, top + 1, BORDER);
        context.fill(left, bottom - 1, right, bottom, BORDER);
        context.fill(left, top, left + 1, bottom, BORDER);
        context.fill(right - 1, top, right, bottom, BORDER);
    }

    /**
     * Шапка: название, код и подписи. Место под кнопку копирования оставляется
     * пустым — саму кнопку добавляет вызывающий, это виджет, а не рисунок.
     */
    public static void drawHeader(DrawContext context, TextRenderer text,
                                  int left, int top, int width, int peerCount) {
        context.drawTextWithShadow(text, Text.translatable("portholeomnis.lobby.title"),
                left, top, TITLE);

        String code = PortholeLobby.shareCode();
        context.drawTextWithShadow(text, Text.literal(code == null ? "…" : code),
                left, top + CODE_Y, CODE);

        context.drawTextWithShadow(text, Text.translatable(PortholeLobby.isRelay()
                        ? "portholeomnis.lobby.mode.relay"
                        : "portholeomnis.lobby.mode.direct"),
                left, top + MODE_Y, LABEL);

        context.drawTextWithShadow(text, Text.translatable("portholeomnis.lobby.peers", peerCount),
                left, top + COUNT_Y, LABEL);

        // Тонкая черта отделяет сводку от списка — иначе строки читаются как её продолжение.
        int line = top + HEADER - 4;
        context.fill(left, line, left + width, line + 1, SEPARATOR);
    }

    /**
     * Строки участников, начиная с {@code from}.
     *
     * @param limit сколько строк нарисовать
     */
    public static void drawRows(DrawContext context, TextRenderer text,
                                int left, int top, int width, List<Long> peers,
                                int from, int limit) {
        if (peers.isEmpty()) {
            context.drawTextWithShadow(text, Text.translatable("portholeomnis.lobby.nobody"),
                    left, top + 4, LABEL);
            return;
        }
        int end = Math.min(peers.size(), from + limit);
        for (int i = from; i < end; i++) {
            drawPeer(context, text, left, top + (i - from) * ROW, width, peers.get(i));
        }
    }

    private static void drawPeer(DrawContext context, TextRenderer text,
                                 int left, int y, int width, long steamId) {
        SteamProfile.Profile profile = SteamProfile.get(steamId);

        if (profile != null && profile.avatar() != null && profile.avatarSize() > 0) {
            int size = profile.avatarSize();
            context.drawTexture(profile.avatar(), left, y, AVATAR, AVATAR,
                    0f, 0f, size, size, size, size);
        } else {
            // Профиль ещё едет или закрыт — рамка на месте аватарки, чтобы строка не прыгала.
            context.fill(left, y, left + AVATAR, y + AVATAR, PLACEHOLDER);
        }

        int room = width - AVATAR - 4;
        PortholeLobby.Link link = PortholeLobby.link(steamId);
        if (link != null) {
            String ping = link.pingMs() + " ms";
            int pingWidth = text.getWidth(ping);
            context.drawTextWithShadow(text, Text.literal(ping),
                    left + width - pingWidth, y + 4, pingColor(link));
            room -= pingWidth + 4;
        }

        // Пока ник не подъехал, показываем Steam ID: молчать хуже, чем показать цифры.
        String name = profile != null && profile.personaName() != null
                ? profile.personaName()
                : Long.toString(steamId);
        context.drawTextWithShadow(text, Text.literal(text.trimToWidth(name, Math.max(room, 0))),
                left + AVATAR + 4, y + 4, NAME);
    }

    private static int pingColor(PortholeLobby.Link link) {
        if (link.quality() < 0.9f || link.pingMs() > 150) {
            return PING_BAD;
        }
        return link.pingMs() > 60 ? PING_FAIR : PING_GOOD;
    }
}

package org.genius8loci.portholeomnis;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;

/**
 * Плашки о входе и выходе гостей — в правом нижнем углу, как уведомления Steam:
 * аватарка, ник и строка о том, что произошло.
 *
 * <p>События берутся из {@link PortholeLobby#pollEvent()}: их кладёт поток разбора
 * stdout, а забирает отрисовка. Очередь заодно работает регулятором — за кадр
 * достаётся не больше, чем помещается на экран, остальное подождёт следующего.
 *
 * <p>Ванильный {@code ToastManager} не подошёл: он рисует в правом верхнем углу
 * ванильными текстурами, а просили угол нижний и вид Steam.
 */
public final class PortholeToasts {

    private static final int MARGIN = 8;
    private static final int HEIGHT = 32;
    /** Просвет между плашками в стопке. */
    private static final int GAP = 4;
    private static final int PAD = 4;
    private static final int AVATAR = 24;
    /** Цветная полоса у левого края: вход и выход различаются до чтения текста. */
    private static final int STRIPE = 2;
    private static final int WIDTH_MIN = 140;
    private static final int WIDTH_MAX = 200;
    private static final int MAX_VISIBLE = 3;

    private static final long SLIDE_MILLIS = 220;
    private static final long HOLD_MILLIS = 4000;
    private static final long LIFETIME = SLIDE_MILLIS * 2 + HOLD_MILLIS;

    private static final int BACKGROUND = 0xFF16233F;
    private static final int BORDER = 0xFF3C6FE0;
    private static final int NAME = 0xFFFFFF;
    private static final int JOINED = 0x55FF55;
    private static final int LEFT = 0xFF8A8A;
    private static final int PLACEHOLDER = 0x30FFFFFF;

    private static final List<Toast> visible = new ArrayList<>();

    private record Toast(long steamId, boolean joined, long shownAt) {
    }

    private PortholeToasts() {
    }

    /** Вызывается каждый кадр из HUD. */
    public static void render(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options.hudHidden) {
            return;
        }

        long now = Util.getMeasuringTimeMs();
        visible.removeIf(t -> now - t.shownAt() >= LIFETIME);
        while (visible.size() < MAX_VISIBLE) {
            PortholeLobby.Event event = PortholeLobby.pollEvent();
            if (event == null) {
                break;
            }
            visible.add(new Toast(event.steamId(), event.joined(), now));
        }
        if (visible.isEmpty()) {
            return;
        }

        int right = context.getScaledWindowWidth() - MARGIN;
        int floor = context.getScaledWindowHeight() - MARGIN;
        for (int i = 0; i < visible.size(); i++) {
            // Свежая плашка — в самом углу, прежние уезжают вверх.
            int bottom = floor - (visible.size() - 1 - i) * (HEIGHT + GAP);
            draw(context, client.textRenderer, visible.get(i), right, bottom - HEIGHT, now);
        }
    }

    private static void draw(DrawContext context, TextRenderer text, Toast toast,
                             int right, int top, long now) {
        SteamProfile.Profile profile = SteamProfile.get(toast.steamId());
        // Пока профиль едет, показываем Steam ID: плашка не должна ждать сеть.
        String name = profile != null && profile.personaName() != null
                ? profile.personaName()
                : Long.toString(toast.steamId());
        Text status = Text.translatable(toast.joined()
                ? "portholeomnis.toast.joined"
                : "portholeomnis.toast.left");

        int textLeft = PAD + STRIPE + PAD + AVATAR + 5;
        int width = Math.min(WIDTH_MAX, Math.max(WIDTH_MIN,
                textLeft + Math.max(text.getWidth(name), text.getWidth(status)) + PAD));
        int left = right - width;
        int bottom = top + HEIGHT;

        context.getMatrices().push();
        context.getMatrices().translate(slide(toast, width, now), 0f, 0f);

        context.fill(left, top, right, bottom, BACKGROUND);
        context.fill(left, top, right, top + 1, BORDER);
        context.fill(left, bottom - 1, right, bottom, BORDER);
        context.fill(left, top, left + 1, bottom, BORDER);
        context.fill(right - 1, top, right, bottom, BORDER);

        int accent = toast.joined() ? JOINED : LEFT;
        context.fill(left + 1, top + 1, left + 1 + STRIPE, bottom - 1, 0xFF000000 | accent);

        int avatarLeft = left + PAD + STRIPE + PAD;
        int avatarTop = top + (HEIGHT - AVATAR) / 2;
        if (profile != null && profile.avatar() != null && profile.avatarSize() > 0) {
            int size = profile.avatarSize();
            context.drawTexture(profile.avatar(), avatarLeft, avatarTop, AVATAR, AVATAR,
                    0f, 0f, size, size, size, size);
        } else {
            context.fill(avatarLeft, avatarTop, avatarLeft + AVATAR, avatarTop + AVATAR,
                    PLACEHOLDER);
        }

        int room = width - textLeft - PAD;
        context.drawTextWithShadow(text, Text.literal(text.trimToWidth(name, room)),
                left + textLeft, top + 7, NAME);
        context.drawTextWithShadow(text, status, left + textLeft, top + 18, accent);

        context.getMatrices().pop();
    }

    /**
     * Сдвиг по горизонтали: плашка выезжает справа и туда же уходит.
     *
     * @return насколько пикселей увести плашку вправо от её места
     */
    private static float slide(Toast toast, int width, long now) {
        long age = now - toast.shownAt();
        long left = LIFETIME - age;
        float progress = age < SLIDE_MILLIS ? age / (float) SLIDE_MILLIS
                : left < SLIDE_MILLIS ? left / (float) SLIDE_MILLIS
                : 1f;
        if (progress >= 1f) {
            return 0f;
        }
        // Замедление к концу: равномерный выезд выглядит механическим.
        float eased = 1f - (1f - progress) * (1f - progress) * (1f - progress);
        return (1f - eased) * (width + MARGIN);
    }
}

package org.genius8loci.portholeomnis;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;

import java.util.List;

/**
 * Список участников целиком, с прокруткой. Открывается кнопкой «Ещё…», когда
 * участники не влезают в панель меню паузы, и вместо панели, когда окно слишком
 * узкое.
 *
 * <p>Прокрутка своя, на ножницах и смещении, а не на {@code EntryListWidget}:
 * строке нужны только аватарка, ник и пинг, ради этого тащить список с выделением
 * и навигацией незачем.
 */
public class PortholeLobbyScreen extends Screen {

    private static final int WIDTH = 240;
    private static final int TOP = 40;
    private static final int BOTTOM_MARGIN = 40;
    private static final int SCROLLBAR = 4;

    private final Screen parent;
    private double scroll;

    public PortholeLobbyScreen(Screen parent) {
        super(Text.translatable("portholeomnis.lobby.title"));
        this.parent = parent;
    }

    private int left() {
        return this.width / 2 - WIDTH / 2;
    }

    private int listTop() {
        return TOP + PortholeLobbyPanel.PAD + PortholeLobbyPanel.HEADER;
    }

    private int listBottom() {
        return this.height - BOTTOM_MARGIN;
    }

    /** На сколько пикселей содержимое выше окна списка. */
    private int overflow() {
        int content = PortholeLobby.peers().size() * PortholeLobbyPanel.ROW;
        return Math.max(content - (listBottom() - listTop()), 0);
    }

    @Override
    protected void init() {
        int contentLeft = left() + PortholeLobbyPanel.PAD;
        int contentWidth = WIDTH - PortholeLobbyPanel.PAD * 2;

        this.addDrawableChild(new PortholeCopyButton(contentLeft,
                TOP + PortholeLobbyPanel.PAD + PortholeLobbyPanel.COPY_BUTTON_Y,
                contentWidth, PortholeLobbyPanel.BUTTON_HEIGHT))
                .setTooltip(Tooltip.of(Text.translatable("portholeomnis.chat.copy")));

        this.addDrawableChild(new PortholeFlatButton(this.width / 2 - 100, this.height - 28,
                200, 20, ScreenTexts.DONE, b -> this.close()));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        int max = overflow();
        if (max > 0) {
            this.scroll = Math.max(0, Math.min(max, this.scroll - amount * PortholeLobbyPanel.ROW));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context);

        List<Long> peers = PortholeLobby.peers();
        int left = left();
        int right = left + WIDTH;
        // Клампим здесь тоже: список мог укоротиться, пока экран открыт.
        this.scroll = Math.min(this.scroll, overflow());

        PortholeLobbyPanel.drawFrame(context, left, TOP, right, listBottom() + PortholeLobbyPanel.PAD);
        PortholeLobbyPanel.drawHeader(context, this.textRenderer,
                left + PortholeLobbyPanel.PAD, TOP + PortholeLobbyPanel.PAD,
                WIDTH - PortholeLobbyPanel.PAD * 2, peers.size());

        int top = listTop();
        int bottom = listBottom();
        // Ножницы: строки, уехавшие за край окна списка, рисоваться не должны.
        context.enableScissor(left + 1, top, right - 1, bottom);
        int first = (int) (this.scroll / PortholeLobbyPanel.ROW);
        int visible = (bottom - top) / PortholeLobbyPanel.ROW + 2;
        PortholeLobbyPanel.drawRows(context, this.textRenderer,
                left + PortholeLobbyPanel.PAD,
                top - (int) (this.scroll % PortholeLobbyPanel.ROW),
                WIDTH - PortholeLobbyPanel.PAD * 2 - SCROLLBAR - 2,
                peers, first, visible);
        context.disableScissor();

        drawScrollbar(context, right, top, bottom, peers.size());
        super.render(context, mouseX, mouseY, delta);
    }

    private void drawScrollbar(DrawContext context, int right, int top, int bottom, int peers) {
        int max = overflow();
        if (max <= 0) {
            return;
        }
        int track = bottom - top;
        int content = peers * PortholeLobbyPanel.ROW;
        int thumb = Math.max(track * track / content, 16);
        int y = top + (int) ((track - thumb) * (this.scroll / max));
        int x = right - PortholeLobbyPanel.PAD - SCROLLBAR;
        context.fill(x, top, x + SCROLLBAR, bottom, 0x40000000);
        context.fill(x, y, x + SCROLLBAR, y + thumb, PortholeLobbyPanel.BORDER);
    }

    @Override
    public void close() {
        this.client.setScreen(this.parent);
    }
}

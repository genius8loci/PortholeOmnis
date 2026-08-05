package org.genius8loci.portholeomnis.mixin;

import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.text.Text;
import org.genius8loci.portholeomnis.PortholeCopyButton;
import org.genius8loci.portholeomnis.PortholeFlatButton;
import org.genius8loci.portholeomnis.PortholeLobby;
import org.genius8loci.portholeomnis.PortholeLobbyPanel;
import org.genius8loci.portholeomnis.PortholeLobbyScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Сводка по лобби в меню паузы, вровень с колонкой кнопок.
 *
 * <p>Границы колонки берутся не из констант, а из самих виджетов на момент конца
 * {@code init}: так панель встаёт ровно по кнопкам и переживает моды, которые в это
 * меню что-то добавляют. Свои виджеты добавляются уже после замера, чтобы не мерить
 * самого себя.
 *
 * <p>Если участников больше, чем влезает в высоту колонки, последняя строка уступает
 * место кнопке «Ещё…», открывающей {@link PortholeLobbyScreen} со списком целиком.
 *
 * <p>Сама панель рисуется не в {@code render}, а отдельным {@link Drawable},
 * добавленным до кнопок: экран отрисовывает виджеты в порядке добавления, а любая
 * дорисовка в хвосте {@code render} закрасила бы кнопки заливкой панели.
 */
@Mixin(GameMenuScreen.class)
public abstract class GameMenuScreenMixin extends Screen {

    private static final int MARGIN = 8;

    @Unique
    private int portholeomnis$left;
    @Unique
    private int portholeomnis$top;
    @Unique
    private int portholeomnis$width;
    @Unique
    private int portholeomnis$height;
    @Unique
    private int portholeomnis$rows;

    protected GameMenuScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void portholeomnis$addLobbyWidgets(CallbackInfo ci) {
        this.portholeomnis$width = 0;
        if (!PortholeLobby.isActive()) {
            return;
        }

        int[] column = portholeomnis$columnBounds();
        int available = column[2] - MARGIN * 2;
        if (available < PortholeLobbyPanel.WIDTH_MIN) {
            // Места слева нет — весь список уходит на отдельный экран.
            this.addDrawableChild(new PortholeFlatButton(MARGIN, MARGIN, 100, 20,
                    Text.translatable("portholeomnis.lobby.open"),
                    b -> this.client.setScreen(new PortholeLobbyScreen(this))));
            return;
        }

        this.portholeomnis$width = Math.min(PortholeLobbyPanel.WIDTH_MAX, available);
        this.portholeomnis$top = column[0];
        this.portholeomnis$height = column[1] - column[0];
        this.portholeomnis$left = column[2] - MARGIN - this.portholeomnis$width;

        int contentLeft = this.portholeomnis$left + PortholeLobbyPanel.PAD;
        int contentTop = this.portholeomnis$top + PortholeLobbyPanel.PAD;
        int contentWidth = this.portholeomnis$width - PortholeLobbyPanel.PAD * 2;

        // Фон и текст панели — раньше кнопок, иначе заливка ляжет поверх них.
        Drawable panel = (context, mouseX, mouseY, delta) -> portholeomnis$drawPanel(context);
        this.addDrawable(panel);

        this.addDrawableChild(new PortholeCopyButton(contentLeft,
                contentTop + PortholeLobbyPanel.COPY_BUTTON_Y,
                contentWidth, PortholeLobbyPanel.BUTTON_HEIGHT))
                .setTooltip(Tooltip.of(Text.translatable("portholeomnis.chat.copy")));

        int fits = PortholeLobbyPanel.rowsThatFit(this.portholeomnis$height);
        int peers = PortholeLobby.peers().size();
        this.portholeomnis$rows = peers <= fits ? peers : Math.max(fits - 1, 0);

        if (peers > fits) {
            // Последняя строка уступает место кнопке — иначе список обрывался бы молча.
            int y = contentTop + PortholeLobbyPanel.HEADER
                    + this.portholeomnis$rows * PortholeLobbyPanel.ROW;
            this.addDrawableChild(new PortholeFlatButton(contentLeft, y,
                    contentWidth, PortholeLobbyPanel.BUTTON_HEIGHT,
                    Text.translatable("portholeomnis.lobby.more", peers - this.portholeomnis$rows),
                    b -> this.client.setScreen(new PortholeLobbyScreen(this))));
        }
    }

    /**
     * @return {верх, низ, левый край} колонки кнопок паузы
     *
     * <p>Считаются только нажимаемые виджеты: заголовок «Меню» — тоже ClickableWidget,
     * и по нему панель выровнялась бы выше кнопок.
     */
    @Unique
    private int[] portholeomnis$columnBounds() {
        int top = Integer.MAX_VALUE;
        int bottom = Integer.MIN_VALUE;
        int left = Integer.MAX_VALUE;
        for (Element e : this.children()) {
            if (e instanceof PressableWidget w && w.visible) {
                top = Math.min(top, w.getY());
                bottom = Math.max(bottom, w.getY() + w.getHeight());
                left = Math.min(left, w.getX());
            }
        }
        // Кнопок не нашлось — экран переделан другим модом; берём ванильные пропорции.
        if (top > bottom) {
            return new int[]{this.height / 4, this.height * 3 / 4, this.width / 2 - 102};
        }
        return new int[]{top, bottom, left};
    }

    @Unique
    private void portholeomnis$drawPanel(DrawContext context) {
        if (this.portholeomnis$width == 0 || !PortholeLobby.isActive()) {
            return;
        }
        int left = this.portholeomnis$left;
        int top = this.portholeomnis$top;

        PortholeLobbyPanel.drawFrame(context, left, top,
                left + this.portholeomnis$width, top + this.portholeomnis$height);

        int contentLeft = left + PortholeLobbyPanel.PAD;
        int contentTop = top + PortholeLobbyPanel.PAD;
        int contentWidth = this.portholeomnis$width - PortholeLobbyPanel.PAD * 2;
        List<Long> peers = PortholeLobby.peers();

        PortholeLobbyPanel.drawHeader(context, this.textRenderer,
                contentLeft, contentTop, contentWidth, peers.size());
        PortholeLobbyPanel.drawRows(context, this.textRenderer,
                contentLeft, contentTop + PortholeLobbyPanel.HEADER, contentWidth,
                peers, 0, this.portholeomnis$rows);
    }
}

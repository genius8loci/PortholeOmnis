package org.genius8loci.portholeomnis;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

/**
 * Кнопка «Скопировать код» с подтверждением: после нажатия на три секунды
 * превращается в зелёное «Код скопирован!» и возвращается обратно.
 *
 * <p>Подтверждение нужно именно на кнопке: буфер обмена — операция без видимого
 * следа, и без ответа непонятно, сработало нажатие или нет.
 *
 * <p>Возврат надписи делается в отрисовке, а не по тику: кнопка живёт ровно столько,
 * сколько открыт экран, и своего тика у неё нет.
 */
public class PortholeCopyButton extends PortholeFlatButton {

    private static final long FEEDBACK_MILLIS = 3000;
    private static final int COPIED_COLOR = 0x55FF55;

    private static final Text IDLE = Text.translatable("portholeomnis.lobby.copy");
    private static final Text COPIED = Text.translatable("portholeomnis.lobby.copied");

    /** Момент, когда надпись пора вернуть; 0 — подтверждение не показывается. */
    private long until;

    public PortholeCopyButton(int x, int y, int width, int height) {
        // Действие пустое: копирование сидит в onPress, чтобы не тащить сюда экран.
        super(x, y, width, height, IDLE, b -> {
        });
    }

    @Override
    public void onPress() {
        String code = PortholeLobby.shareCode();
        if (code == null) {
            return;
        }
        MinecraftClient.getInstance().keyboard.setClipboard(code);
        this.until = Util.getMeasuringTimeMs() + FEEDBACK_MILLIS;
        this.setMessage(COPIED);
    }

    @Override
    protected int textColor() {
        return this.until != 0 ? COPIED_COLOR : super.textColor();
    }

    @Override
    protected void renderButton(DrawContext context, int mouseX, int mouseY, float delta) {
        if (this.until != 0 && Util.getMeasuringTimeMs() >= this.until) {
            this.until = 0;
            this.setMessage(IDLE);
        }
        super.renderButton(context, mouseX, mouseY, delta);
    }
}

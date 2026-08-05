package org.genius8loci.portholeomnis;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * Ванильная кнопка с синеватой заливкой поверх — чтобы Porthole выделялся в ряду
 * одинаковых кнопок экрана сетевой игры.
 *
 * <p>Заливка вместо подмены цвета шейдера намеренно: {@code PressableWidget.renderButton}
 * сам выставляет цвет шейдера перед отрисовкой текстуры, так что заданный снаружи оттенок
 * до неё не доживает.
 */
public class PortholeButton extends ButtonWidget {

    /** ARGB. Альфа низкая: сквозь заливку должны читаться рамка кнопки и её состояние. */
    private static final int TINT = 0x503C6FE0;
    private static final int TINT_HOVERED = 0x704E86FF;

    public PortholeButton(int x, int y, int width, int height, Text message, PressAction onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION_SUPPLIER);
    }

    @Override
    protected void renderButton(DrawContext context, int mouseX, int mouseY, float delta) {
        super.renderButton(context, mouseX, mouseY, delta);
        if (!this.active) {
            // Неактивная кнопка остаётся серой: синий цвет не должен спорить с «нельзя нажать».
            return;
        }
        context.fill(this.getX(), this.getY(),
                this.getX() + this.getWidth(), this.getY() + this.getHeight(),
                this.isSelected() ? TINT_HOVERED : TINT);
    }
}

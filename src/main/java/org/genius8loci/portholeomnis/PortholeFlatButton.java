package org.genius8loci.portholeomnis;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

/**
 * Кнопка панели Porthole, нарисованная целиком своими руками: ванильная текстура
 * не трогается вовсе.
 *
 * <p>{@link PortholeButton} для этого не годится: он кладёт синеватую заливку поверх
 * ванильной кнопки и потому обязан выглядеть как ванильная. На собственной панели
 * это смотрится чужеродно, а серые градиенты текстуры спорят с ровной заливкой фона.
 *
 * <p>Состояний четыре — обычное, под курсором, нажатое и выключенное; у каждого своя
 * заливка, свой контур и свой цвет текста, чтобы «нельзя нажать» читалось без
 * подсказок. Под курсором добавляется светлый блик по верхней кромке: одного
 * изменения оттенка на мелкой кнопке почти не видно. Нажатие, наоборот, гасит блик,
 * притемняет верх и роняет надпись на пиксель вниз — кнопка «проваливается».
 *
 * <p>Углы не закрашиваются вовсе: четыре пропущенных пикселя дают фаску, из-за
 * которой кнопка не выглядит наклейкой поверх панели.
 */
public class PortholeFlatButton extends ButtonWidget {

    private static final int FILL = 0xFF22406F;
    private static final int FILL_HOVERED = 0xFF31599F;
    private static final int FILL_PRESSED = 0xFF16305A;
    private static final int FILL_DISABLED = 0xFF1B2438;

    private static final int BORDER = 0xFF3C6FE0;
    private static final int BORDER_HOVERED = 0xFF8FB8FF;
    private static final int BORDER_DISABLED = 0xFF39445C;

    /** Блик под курсором и тень при нажатии — обе по верхней кромке. */
    private static final int HIGHLIGHT = 0x40FFFFFF;
    private static final int SHADOW = 0x40000000;

    protected static final int TEXT = 0xFFFFFF;
    protected static final int TEXT_DISABLED = 0x7A8296;

    /** Кнопку держат нажатой: ванильные виджеты этого состояния не хранят. */
    private boolean pressed;

    public PortholeFlatButton(int x, int y, int width, int height,
                              Text message, PressAction onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION_SUPPLIER);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        this.pressed = true;
        super.onClick(mouseX, mouseY);
    }

    @Override
    public void onRelease(double mouseX, double mouseY) {
        this.pressed = false;
        super.onRelease(mouseX, mouseY);
    }

    /** Цвет надписи: наследники подменяют его, не переписывая всю отрисовку. */
    protected int textColor() {
        return this.active ? TEXT : TEXT_DISABLED;
    }

    @Override
    protected void renderButton(DrawContext context, int mouseX, int mouseY, float delta) {
        boolean hovered = this.active && (this.isHovered() || this.isFocused());
        boolean down = this.active && this.pressed;

        int fill = !this.active ? FILL_DISABLED
                : down ? FILL_PRESSED
                : hovered ? FILL_HOVERED : FILL;
        int border = !this.active ? BORDER_DISABLED : hovered ? BORDER_HOVERED : BORDER;

        int left = this.getX();
        int top = this.getY();
        int right = left + this.getWidth();
        int bottom = top + this.getHeight();

        // Заливка без угловых пикселей — те остаются прозрачными и дают фаску.
        context.fill(left + 1, top, right - 1, bottom, fill);
        context.fill(left, top + 1, left + 1, bottom - 1, fill);
        context.fill(right - 1, top + 1, right, bottom - 1, fill);

        context.fill(left + 1, top, right - 1, top + 1, border);
        context.fill(left + 1, bottom - 1, right - 1, bottom, border);
        context.fill(left, top + 1, left + 1, bottom - 1, border);
        context.fill(right - 1, top + 1, right, bottom - 1, border);

        if (down) {
            context.fill(left + 1, top + 1, right - 1, top + 2, SHADOW);
        } else if (hovered) {
            context.fill(left + 1, top + 1, right - 1, top + 2, HIGHLIGHT);
        }

        MinecraftClient client = MinecraftClient.getInstance();
        // Альфа виджета уважается: экраны умеют проявлять содержимое.
        int alpha = MathHelper.ceil(this.alpha * 255f) << 24;
        int color = this.textColor() & 0x00FFFFFF | alpha;

        context.getMatrices().push();
        if (down) {
            context.getMatrices().translate(0f, 1f, 0f);
        }
        this.drawScrollableText(context, client.textRenderer, 2, color);
        context.getMatrices().pop();
    }
}

package org.genius8loci.portholeomnis.mixin;

import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import org.genius8loci.portholeomnis.PortholeButton;
import org.genius8loci.portholeomnis.PortholeConnectScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Кнопка «Porthole» в верхнем ряду экрана сетевой игры, сразу за «Прямым подключением».
 *
 * <p>Свободного места в ряду нет, поэтому ряд перекладывается целиком: ширина делится
 * поровну между всеми кнопками, которые в нём оказались. Так вставка переживает и
 * другие моды, добавившие туда свои кнопки.
 */
@Mixin(MultiplayerScreen.class)
public abstract class MultiplayerScreenMixin extends Screen {

    /** Ванильный ряд: слева width/2-154, ширина 308 (три кнопки по 100 с зазором 4). */
    private static final int ROW_LEFT_OFFSET = -154;
    private static final int ROW_WIDTH = 308;
    private static final int GAP = 4;

    protected MultiplayerScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void portholeomnis$addPortholeButton(CallbackInfo ci) {
        MultiplayerScreen self = (MultiplayerScreen) (Object) this;
        ButtonWidget porthole = new PortholeButton(6, 6, 74, 20,
                Text.translatable("portholeomnis.connect.button"),
                b -> this.client.setScreen(new PortholeConnectScreen(self)));
        this.addDrawableChild(porthole);
        portholeomnis$layoutRow(porthole);
    }

    /**
     * Ряд ищем по кнопке «Прямое подключение». Если её нет — экран переделан другим
     * модом, и кнопка остаётся в левом верхнем углу: некрасиво, но ничего не перекрывает.
     */
    private void portholeomnis$layoutRow(ButtonWidget porthole) {
        ClickableWidget direct = null;
        for (Element e : this.children()) {
            if (e instanceof ClickableWidget w
                    && portholeomnis$isKey(w.getMessage(), "selectServer.direct")) {
                direct = w;
                break;
            }
        }
        if (direct == null) {
            return;
        }

        int rowY = direct.getY();
        List<ClickableWidget> row = new ArrayList<>();
        for (Element e : this.children()) {
            if (e instanceof ClickableWidget w && w != porthole && w.getY() == rowY) {
                row.add(w);
            }
        }
        row.sort(Comparator.comparingInt(ClickableWidget::getX));
        row.add(row.indexOf(direct) + 1, porthole);

        int n = row.size();
        int width = (ROW_WIDTH - GAP * (n - 1)) / n;
        int left = this.width / 2 + ROW_LEFT_OFFSET;
        for (int i = 0; i < n; i++) {
            ClickableWidget w = row.get(i);
            w.setX(left + i * (width + GAP));
            w.setY(rowY);
            w.setWidth(width);
        }
    }

    private static boolean portholeomnis$isKey(Text text, String key) {
        return text.getContent() instanceof TranslatableTextContent content
                && key.equals(content.getKey());
    }
}

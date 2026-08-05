package org.genius8loci.portholeomnis.mixin;

import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.OpenToLanScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.text.Text;
import org.genius8loci.portholeomnis.PortholeOmnis;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Тумблеры мода на ванильном экране «Открыть для сети».
 *
 * <p>Ванильная раскладка 1.20.1, от которой всё считается: заголовок y=50, подпись
 * «настройки для других игроков» y=82, ряд «режим игры / читы» y=100 (две кнопки по
 * 150 слева от width/2-155 и width/2+5), подпись «номер порта» y=142, поле порта
 * y=160, кнопки «Начать / Отмена» на height-28.
 *
 * <p>Свободной полосы под три кнопки там нет: 120..142 — это 22 пикселя, а подпись
 * «номер порта» рисует сама ванильная render(), сдвинуть её мимо мода нельзя.
 * Поэтому ряд «режим игры / читы» уплотняется на 6 пикселей вверх, мод получает
 * свой ряд ровно по ванильной сетке 150+10+150, а онлайн-режим уезжает под поле
 * порта отдельной строкой во всю ширину — он один меняет не удобство, а то, кто
 * вообще сможет зайти.
 */
@Mixin(OpenToLanScreen.class)
public abstract class OpenToLanScreenMixin extends Screen {

    /** Половина ширины ванильной колонки контента: width/2-155 … width/2+155. */
    private static final int COLUMN = 155;
    /** Ванильная половинная кнопка и зазор между половинками. */
    private static final int HALF = 150;
    private static final int GAP = 10;
    private static final int ROW_HEIGHT = 20;

    /** Ванильный ряд «режим игры / читы» и он же после уплотнения. */
    private static final int VANILLA_MODE_ROW_Y = 100;
    private static final int MODE_ROW_Y = 94;

    /** Ряд мода: 118..138, дальше 4 пикселя до ванильной подписи «номер порта» на 142. */
    private static final int TUNNEL_ROW_Y = 118;
    /**
     * Если ванильный ряд не нашёлся (экран переделан другим модом), уплотнить нечего.
     * Тогда встаём впритык: 121..141 — ниже ванильных кнопок и всё ещё выше подписи.
     */
    private static final int TUNNEL_ROW_FALLBACK_Y = 121;

    /**
     * Онлайн-режим: отдельной строкой под полем порта (оно занимает 160..180),
     * во всю ширину колонки. Ширина и отступ и делают из него отдельный блок —
     * разделительной линии тут не место, на неё остаётся 6 пикселей до кнопки.
     */
    private static final int ONLINE_ROW_Y = 186;

    /** Без туннеля релей ничего не значит — гасим, а не молча игнорируем. */
    @Unique
    private ClickableWidget portholeomnis$relayButton;

    protected OpenToLanScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void portholeomnis$addToggles(CallbackInfo ci) {
        int left = this.width / 2 - COLUMN;
        int right = left + HALF + GAP;
        int tunnelY = portholeomnis$compactModeRow() ? TUNNEL_ROW_Y : TUNNEL_ROW_FALLBACK_Y;

        this.addDrawableChild(CyclingButtonWidget
                .onOffBuilder(PortholeOmnis.usePorthole)
                .tooltip(value -> Tooltip.of(Text.translatable(value
                        ? "portholeomnis.use_porthole.tooltip.on"
                        : "portholeomnis.use_porthole.tooltip.off")))
                .build(left, tunnelY, HALF, ROW_HEIGHT,
                        Text.translatable("portholeomnis.use_porthole"),
                        (button, value) -> {
                            PortholeOmnis.usePorthole = value;
                            portholeomnis$syncRelay();
                        }));

        this.portholeomnis$relayButton = this.addDrawableChild(CyclingButtonWidget
                .onOffBuilder(PortholeOmnis.forceRelay)
                .tooltip(value -> Tooltip.of(Text.translatable("portholeomnis.force_relay.tooltip")))
                .build(right, tunnelY, HALF, ROW_HEIGHT,
                        Text.translatable("portholeomnis.force_relay"),
                        (button, value) -> PortholeOmnis.forceRelay = value));

        // Тултип обязателен и зависит от положения: выключенный онлайн-режим — это
        // вход из интернета под любым ником, а из названия тумблера это не следует.
        this.addDrawableChild(CyclingButtonWidget
                .onOffBuilder(PortholeOmnis.onlineMode)
                .tooltip(value -> Tooltip.of(Text.translatable(value
                        ? "portholeomnis.online_mode.tooltip.on"
                        : "portholeomnis.online_mode.tooltip.off")))
                .build(left, ONLINE_ROW_Y, COLUMN * 2, ROW_HEIGHT,
                        Text.translatable("portholeomnis.online_mode"),
                        (button, value) -> PortholeOmnis.onlineMode = value));

        portholeomnis$syncRelay();
    }

    /**
     * Поднимает ванильный ряд «режим игры / читы» на 6 пикселей, освобождая полосу
     * под ряд мода. Ряд опознаётся по y, а не по тексту: подпись у кнопки режима
     * меняется вместе с выбранным режимом.
     *
     * @return удалось ли уплотнить
     */
    @Unique
    private boolean portholeomnis$compactModeRow() {
        boolean moved = false;
        for (Element e : this.children()) {
            if (e instanceof ClickableWidget w && w.getY() == VANILLA_MODE_ROW_Y) {
                w.setY(MODE_ROW_Y);
                moved = true;
            }
        }
        return moved;
    }

    @Unique
    private void portholeomnis$syncRelay() {
        if (this.portholeomnis$relayButton != null) {
            this.portholeomnis$relayButton.active = PortholeOmnis.usePorthole;
        }
    }
}

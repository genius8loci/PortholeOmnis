package org.genius8loci.portholeomnis.mixin;

import net.minecraft.client.gui.screen.OpenToLanScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.text.Text;
import org.genius8loci.portholeomnis.PortholeOmnis;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ванильный экран (class_436) уже содержит поля port/portField начиная с 1.20.
 * Добавляем только два тумблера; сами настройки применяются в IntegratedServerMixin.
 */
@Mixin(OpenToLanScreen.class)
public abstract class OpenToLanScreenMixin extends Screen {

    protected OpenToLanScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void portholeomnis$addToggles(CallbackInfo ci) {
        // y = 124: свободная полоса между кнопками режима (y=100) и полем порта (y=160).
        // Три кнопки по 100 с зазором 5 укладываются в ванильные 310 пикселей ряда.
        this.addDrawableChild(CyclingButtonWidget
                .onOffBuilder(PortholeOmnis.onlineMode)
                .build(this.width / 2 - 155, 124, 100, 20,
                        Text.translatable("portholeomnis.online_mode"),
                        (button, value) -> PortholeOmnis.onlineMode = value));

        this.addDrawableChild(CyclingButtonWidget
                .onOffBuilder(PortholeOmnis.usePorthole)
                .build(this.width / 2 - 50, 124, 100, 20,
                        Text.translatable("portholeomnis.use_porthole"),
                        (button, value) -> PortholeOmnis.usePorthole = value));

        this.addDrawableChild(CyclingButtonWidget
                .onOffBuilder(PortholeOmnis.forceRelay)
                .tooltip(value -> Tooltip.of(Text.translatable("portholeomnis.force_relay.tooltip")))
                .build(this.width / 2 + 55, 124, 100, 20,
                        Text.translatable("portholeomnis.force_relay"),
                        (button, value) -> PortholeOmnis.forceRelay = value));
    }
}

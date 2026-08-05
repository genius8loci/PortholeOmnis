package org.genius8loci.portholeomnis.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameMode;
import org.genius8loci.portholeomnis.PortholeLauncher;
import org.genius8loci.portholeomnis.PortholeOmnis;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * openToLan объявлен в MinecraftServer (method_3763), переопределён в IntegratedServer.
 * Инжектим в переопределение: к моменту RETURN сервер уже опубликован и порт известен.
 */
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerMixin {

    @Inject(method = "openToLan", at = @At("RETURN"))
    private void portholeomnis$afterOpenToLan(GameMode gameMode, boolean cheatsAllowed,
                                              int port, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()) {
            return;
        }
        MinecraftServer server = (MinecraftServer) (Object) this;

        // setOnlineMode публичный — миксин на проверку аутентификации не требуется.
        server.setOnlineMode(PortholeOmnis.onlineMode);

        int actualPort = server.getServerPort();
        PortholeOmnis.LOGGER.info(
                "openToLan: port={} online-mode requested={} actual={} porthole={} relay={}",
                actualPort, PortholeOmnis.onlineMode, server.isOnlineMode(),
                PortholeOmnis.usePorthole, PortholeOmnis.forceRelay);

        if (!PortholeOmnis.usePorthole) {
            return;
        }
        PortholeLauncher.start(actualPort, PortholeOmnis.forceRelay,
                code -> chat(Text.translatable("portholeomnis.chat.code", clickable(code))),
                key -> chat(problem(stripArg(key))));
    }

    /** К сообщению «Porthole не найден» цепляем ссылку на магазин. */
    private static Text problem(String id) {
        MutableText text = Text.translatable("portholeomnis." + id).formatted(Formatting.RED);
        if ("porthole.not_found".equals(id)) {
            text.append(ScreenTexts.SPACE).append(storeLink());
        }
        return text;
    }

    private static Text storeLink() {
        return Text.translatable("portholeomnis.porthole.store").setStyle(Style.EMPTY
                .withColor(Formatting.AQUA)
                .withUnderline(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL,
                        PortholeOmnis.STORE_WEB_URL))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Text.literal(PortholeOmnis.STORE_WEB_URL))));
    }

    private static Text clickable(String code) {
        return Text.literal(code).setStyle(Style.EMPTY
                .withColor(Formatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, code))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Text.translatable("portholeomnis.chat.copy"))));
    }

    /** Ключи вида "porthole.launch_failed:<текст>" — в чат уходит только ключ. */
    private static String stripArg(String key) {
        int i = key.indexOf(':');
        return i < 0 ? key : key.substring(0, i);
    }

    private static void chat(Text text) {
        MinecraftClient client = MinecraftClient.getInstance();
        // Событие приходит из потока сервера — в чат пишем только из клиентского.
        client.execute(() -> client.inGameHud.getChatHud().addMessage(text));
    }
}

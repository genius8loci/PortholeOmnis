package org.genius8loci.portholeomnis;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.gui.screen.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PortholeOmnis implements ClientModInitializer {

    public static final String MOD_ID = "portholeomnis";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /**
     * Порт, под которым мир виден гостю. Minecraft отдаёт LAN-миру случайный
     * свободный порт; публикуем всегда 25565, чтобы гостевая сторона не зависела
     * от того, что выпало хосту.
     */
    public static final int ADVERTISED_PORT = 25565;

    /** Страница Porthole в клиенте Steam. */
    public static final String STORE_STEAM_URI = "steam://store/4963920";
    /**
     * Та же страница по http. Нужна для чата: {@code Screen.handleTextClick}
     * пропускает в ClickEvent только http и https, steam:// он отвергает.
     */
    public static final String STORE_WEB_URL =
            "https://store.steampowered.com/app/4963920/Porthole__Local_Port_Sharing/";

    /**
     * Состояние тумблеров экрана "Открыть для сети". Живёт между открытиями экрана.
     *
     * <p>volatile обязателен: пишет клиентский поток (нажатие тумблера), читает серверный
     * ({@code IntegratedServerMixin} в момент openToLan).
     */
    public static volatile boolean onlineMode = true;
    public static volatile boolean usePorthole = true;
    /** По умолчанию включён: при прямом P2P хост и гость обмениваются IP. */
    public static volatile boolean forceRelay = true;

    /**
     * То же для гостя. Отдельный флаг, а не общий с хостовым: тумблер хоста живёт на
     * экране «Открыть для сети», куда гость не заходит, и без своего флага он всегда
     * оставался бы на умолчании без права выбора.
     */
    public static volatile boolean guestForceRelay = true;

    /** Полный ключ перевода из короткого идентификатора причины. */
    public static String key(String id) {
        return MOD_ID + "." + id;
    }

    @Override
    public void onInitializeClient() {
        // Прошлый запуск мог умереть так, что shutdown hook не отработал.
        PortholeWatchdog.killLeftovers();

        // Плашки о входе и выходе гостей.
        HudRenderCallback.EVENT.register((context, tickDelta) -> PortholeToasts.render(context));

        // Мир закрыт — туннель обязан свернуться вместе с ним.
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> PortholeLauncher.stop());

        // Гость вышел с сервера — его половина туннеля больше не нужна.
        // У хоста своего процесса connect нет, так что событие для него безвредно.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> PortholeConnector.stop());

        // Штатное закрытие игры: не полагаемся на один только shutdown hook.
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            PortholeLauncher.stop();
            PortholeConnector.stop();
        });

        // DISCONNECT не приходит, если до игровой фазы дело так и не дошло: неверный код,
        // хост offline, отказ в лобби. Без этой проверки процесс гостя жил бы до конца сессии.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!PortholeConnector.isRunning() || client.world != null) {
                return;
            }
            Screen screen = client.currentScreen;
            if (screen instanceof ConnectScreen || screen instanceof PortholeConnectScreen) {
                return;
            }
            LOGGER.info("[porthole] подключение не состоялось — сворачиваем туннель гостя");
            PortholeConnector.stop();
        });
    }
}

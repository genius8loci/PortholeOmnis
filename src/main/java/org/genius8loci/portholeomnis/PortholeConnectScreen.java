package org.genius8loci.portholeomnis;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

import java.util.Objects;

/** Экран гостя: ввод share-кода, запуск туннеля и переход в игру. */
public class PortholeConnectScreen extends Screen {

    private final Screen parent;

    /** Как часто перепроверяем окружение: тиков в секунде ровно двадцать. */
    private static final int RECHECK_TICKS = 20;

    private TextFieldWidget codeField;
    private ButtonWidget connectButton;
    private ClickableWidget relayButton;
    private ButtonWidget storeButton;
    private Text status = Text.empty();

    /** Переживает init(): при изменении размера окна виджеты создаются заново. */
    private String code = "";

    /** Короткий идентификатор причины, по которой подключаться нельзя (нет Porthole, нет Steam). */
    private String blockedId;
    private int ticks;
    private boolean busy;
    /** Управление передано ConnectScreen — процесс закрывать нельзя. */
    private boolean handedOff;

    public PortholeConnectScreen(Screen parent) {
        super(Text.translatable("portholeomnis.connect.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;

        this.codeField = new TextFieldWidget(this.textRenderer, cx - 100, 90, 200, 20,
                Text.translatable("portholeomnis.connect.code"));
        this.codeField.setMaxLength(64);
        this.codeField.setText(this.code);
        this.codeField.setChangedListener(s -> {
            this.code = s;
            updateWidgets();
        });
        // Окно могли растянуть уже после нажатия «Подключиться».
        this.codeField.setEditable(!this.busy);
        this.addDrawableChild(this.codeField);
        this.setInitialFocus(this.codeField);

        // Свой тумблер, а не хостовой PortholeOmnis.forceRelay: экран «Открыть для сети»
        // гость не открывает и до того флага дотянуться не может.
        this.relayButton = this.addDrawableChild(CyclingButtonWidget
                .onOffBuilder(PortholeOmnis.guestForceRelay)
                .tooltip(value -> Tooltip.of(Text.translatable("portholeomnis.force_relay.tooltip")))
                .build(cx - 100, 114, 200, 20,
                        Text.translatable("portholeomnis.force_relay"),
                        (button, value) -> PortholeOmnis.guestForceRelay = value));

        this.connectButton = this.addDrawableChild(ButtonWidget
                .builder(Text.translatable("portholeomnis.connect.join"), b -> onConnect())
                .dimensions(cx - 100, 140, 98, 20)
                .build());
        this.addDrawableChild(ButtonWidget
                .builder(ScreenTexts.CANCEL, b -> this.close())
                .dimensions(cx + 2, 140, 98, 20)
                .build());

        // Кнопка магазина живёт всегда и только прячется: так её показ и скрытие
        // обходятся без пересборки экрана из tick().
        // Здесь, в отличие от чата, ограничения на схему нет — открываем сам Steam.
        this.storeButton = this.addDrawableChild(ButtonWidget
                .builder(Text.translatable("portholeomnis.porthole.store"),
                        b -> SteamClient.openStorePage())
                .dimensions(cx - 100, 190, 200, 20)
                .tooltip(Tooltip.of(Text.literal(PortholeOmnis.STORE_STEAM_URI)))
                .build());

        // Проверки окружения до того, как пользователь начал что-то набирать.
        // При busy сюда попадают только из-за смены размера окна — идёт попытка,
        // и её статус затирать нечем.
        if (!this.busy) {
            this.blockedId = detectBlock();
            if (this.blockedId != null) {
                this.status = Text.translatable(PortholeOmnis.key(this.blockedId))
                        .formatted(Formatting.RED);
            }
        }
        updateWidgets();
    }

    /** @return идентификатор причины, по которой подключаться нельзя, либо null. */
    private String detectBlock() {
        String unavailable = PortholeLocator.unavailableId();
        if (unavailable != null) {
            return unavailable;
        }
        return SteamClient.isRunning() ? null : "porthole.steam_not_running";
    }

    /**
     * Porthole могли поставить, а Steam запустить, не закрывая этот экран. Раз в секунду
     * переспрашиваем, чтобы человеку не приходилось выходить и заходить заново.
     */
    @Override
    public void tick() {
        super.tick();
        // Во время попытки окружение уже не важно, а вот затереть её статус — легко.
        if (this.busy || this.handedOff || ++this.ticks < RECHECK_TICKS) {
            return;
        }
        this.ticks = 0;

        String now = detectBlock();
        if (Objects.equals(this.blockedId, now)) {
            return;
        }
        boolean wasBlocked = this.blockedId != null;
        this.blockedId = now;
        if (now != null) {
            this.status = Text.translatable(PortholeOmnis.key(now)).formatted(Formatting.RED);
        } else if (wasBlocked) {
            // Гасим именно сообщение о блокировке; сообщение об отказе связи не наше.
            this.status = Text.empty();
        }
        updateWidgets();
    }

    private void updateWidgets() {
        // storeButton создаётся последним: пока он null, виджетов ещё нет.
        if (this.storeButton == null) {
            return;
        }
        this.connectButton.active = !this.busy
                && this.blockedId == null
                && !this.codeField.getText().trim().isEmpty();
        this.relayButton.active = !this.busy;
        this.storeButton.visible = "porthole.not_found".equals(this.blockedId);
    }

    private void onConnect() {
        String code = this.codeField.getText().trim();
        if (code.isEmpty() || this.busy || this.blockedId != null) {
            return;
        }
        this.busy = true;
        this.codeField.setEditable(false);
        this.status = Text.translatable("portholeomnis.connect.status.starting")
                .formatted(Formatting.GRAY);
        updateWidgets();

        PortholeConnector.connect(code, PortholeOmnis.guestForceRelay,
                key -> onClient(() -> this.status =
                        Text.translatable(key).formatted(Formatting.GRAY)),
                port -> onClient(() -> join(port)),
                key -> onClient(() -> fail(key)));
    }

    /** Колбэки приходят из фонового потока; экран трогаем только из клиентского. */
    private void onClient(Runnable action) {
        MinecraftClient c = this.client;
        if (c == null) {
            return;
        }
        c.execute(() -> {
            // Экран мог быть закрыт, пока Porthole поднимался — тогда туннель уже свёрнут.
            if (c.currentScreen == this) {
                action.run();
            }
        });
    }

    private void join(int port) {
        this.handedOff = true;
        String address = "127.0.0.1:" + port;
        ServerInfo info = new ServerInfo(
                Text.translatable("portholeomnis.connect.server_name").getString(),
                address, false);
        // На ошибку подключения ConnectScreen вернёт пользователя в parent, а не сюда.
        ConnectScreen.connect(this.parent, this.client, ServerAddress.parse(address), info, false);
    }

    private void fail(String key) {
        this.busy = false;
        this.codeField.setEditable(true);
        this.status = Text.translatable(key).formatted(Formatting.RED);
        // Причина отказа могла быть в самом окружении — пусть tick() переспросит сразу.
        this.ticks = RECHECK_TICKS;
        updateWidgets();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
                && this.connectButton != null && this.connectButton.active) {
            onConnect();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title,
                this.width / 2, 40, 0xFFFFFF);
        context.drawTextWithShadow(this.textRenderer,
                Text.translatable("portholeomnis.connect.code"),
                this.width / 2 - 100, 77, 0xA0A0A0);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.status,
                this.width / 2, 172, 0xFFFFFF);
    }

    @Override
    public void close() {
        if (this.busy && !this.handedOff) {
            PortholeConnector.stop();
        }
        this.client.setScreen(this.parent);
    }
}

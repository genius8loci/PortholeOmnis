package org.genius8loci.portholeomnis;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

/** Экран гостя: ввод share-кода, запуск туннеля и переход в игру. */
public class PortholeConnectScreen extends Screen {

    private final Screen parent;

    private TextFieldWidget codeField;
    private ButtonWidget connectButton;
    private Text status = Text.empty();

    /** Переживает init(): при изменении размера окна виджеты создаются заново. */
    private String code = "";

    /** Ключ причины, по которой подключаться нельзя в принципе (нет Porthole, нет Steam). */
    private String blockedKey;
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
            updateButton();
        });
        // Окно могли растянуть уже после нажатия «Подключиться».
        this.codeField.setEditable(!this.busy);
        this.addDrawableChild(this.codeField);
        this.setInitialFocus(this.codeField);

        this.connectButton = this.addDrawableChild(ButtonWidget
                .builder(Text.translatable("portholeomnis.connect.join"), b -> onConnect())
                .dimensions(cx - 100, 140, 98, 20)
                .build());
        this.addDrawableChild(ButtonWidget
                .builder(ScreenTexts.CANCEL, b -> this.close())
                .dimensions(cx + 2, 140, 98, 20)
                .build());

        // Проверки окружения до того, как пользователь начал что-то набирать.
        if (PortholeLocator.locate() == null) {
            block("portholeomnis.porthole.not_found");
            // Здесь, в отличие от чата, ограничения на схему нет — открываем сам Steam.
            this.addDrawableChild(ButtonWidget
                    .builder(Text.translatable("portholeomnis.porthole.store"),
                            b -> SteamClient.openStorePage())
                    .dimensions(cx - 100, 190, 200, 20)
                    .tooltip(Tooltip.of(Text.literal(PortholeOmnis.STORE_STEAM_URI)))
                    .build());
        } else if (!SteamClient.isRunning()) {
            block("portholeomnis.porthole.steam_not_running");
        }
        updateButton();
    }

    private void block(String key) {
        this.blockedKey = key;
        this.status = Text.translatable(key).formatted(Formatting.RED);
    }

    private void updateButton() {
        if (this.connectButton == null) {
            return;
        }
        this.connectButton.active = !this.busy
                && this.blockedKey == null
                && !this.codeField.getText().trim().isEmpty();
    }

    private void onConnect() {
        String code = this.codeField.getText().trim();
        if (code.isEmpty() || this.busy || this.blockedKey != null) {
            return;
        }
        this.busy = true;
        this.codeField.setEditable(false);
        this.status = Text.translatable("portholeomnis.connect.status.starting")
                .formatted(Formatting.GRAY);
        updateButton();

        PortholeConnector.connect(code, PortholeOmnis.forceRelay,
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
        updateButton();
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

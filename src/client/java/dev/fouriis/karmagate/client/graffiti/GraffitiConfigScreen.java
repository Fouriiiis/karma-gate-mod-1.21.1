package dev.fouriis.karmagate.client.graffiti;

import dev.fouriis.karmagate.entity.GraffitiEntity;
import dev.fouriis.karmagate.network.UpdateGraffitiPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

public class GraffitiConfigScreen extends Screen {
    private static final String[] CORNER_LABELS = {"Bottom Left", "Bottom Right", "Top Right", "Top Left"};

    private final GraffitiEntity graffiti;
    private final String initialTexture;
    private String currentTexture;
    private final float[] initialOpacity = new float[4];
    private final float[] initialMelt = new float[4];

    public GraffitiConfigScreen(GraffitiEntity graffiti) {
        super(Text.literal("Graffiti Config"));
        this.graffiti = graffiti;
        this.initialTexture = graffiti.getTexturePath();
        this.currentTexture = graffiti.getTexturePath();
        for (int i = 0; i < 4; i++) {
            initialOpacity[i] = graffiti.getCornerOpacity(i);
            initialMelt[i] = graffiti.getCornerMelt(i);
        }
    }

    @Override
    protected void init() {
        super.init();

        int leftX = this.width / 2 - 170;
        int rightX = this.width / 2 + 10;
        int startY = 60;
        int rowH = 24;
        int sliderW = 160;
        int sliderH = 20;

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Change Sprite"), button -> {
            MinecraftClient.getInstance().setScreen(new GraffitiPickerScreen(texture -> {
                applyTexture(texture);
                MinecraftClient.getInstance().setScreen(new GraffitiConfigScreen(graffiti));
            }));
        }).dimensions(this.width / 2 - 60, 30, 120, 20).build());

        for (int i = 0; i < 4; i++) {
            int cornerIndex = i;
            int y = startY + i * rowH;
            this.addDrawableChild(new CornerSlider(leftX, y, sliderW, sliderH,
                Text.literal("Opacity " + CORNER_LABELS[i]), graffiti.getCornerOpacity(i),
                value -> setCornerOpacity(cornerIndex, value)));
        }

        for (int i = 0; i < 4; i++) {
            int cornerIndex = i;
            int y = startY + i * rowH;
            this.addDrawableChild(new CornerSlider(rightX, y, sliderW, sliderH,
                Text.literal("Melt " + CORNER_LABELS[i]), graffiti.getCornerMelt(i),
                value -> setCornerMelt(cornerIndex, value)));
        }

        int buttonY = startY + 4 * rowH + 12;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> {
            sendUpdate();
            this.close();
        }).dimensions(this.width / 2 - 110, buttonY, 100, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), button -> {
            restoreInitial();
            this.close();
        }).dimensions(this.width / 2 + 10, buttonY, 100, 20).build());
    }

    @Override
    public void tick() {
        if (!graffiti.isAlive()) {
            this.close();
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 10, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer,
            Text.literal("Sprite: " + currentTexture), this.width / 2, 52, 0xCCCCCC);

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // Fully transparent background to keep the world visible.
    }

    private void applyTexture(String textureName) {
        if (textureName == null || textureName.isEmpty()) return;
        currentTexture = textureName;
        graffiti.setTexturePath(textureName);
    }

    private void setCornerOpacity(int corner, double value) {
        graffiti.setCornerOpacity(corner, (float) value);
    }

    private void setCornerMelt(int corner, double value) {
        graffiti.setCornerMelt(corner, (float) value);
    }

    private void restoreInitial() {
        graffiti.setTexturePath(initialTexture);
        currentTexture = initialTexture;
        for (int i = 0; i < 4; i++) {
            graffiti.setCornerOpacity(i, initialOpacity[i]);
            graffiti.setCornerMelt(i, initialMelt[i]);
        }
    }

    private void sendUpdate() {
        float[] opacities = new float[4];
        float[] melts = new float[4];
        float[] cornerH = new float[4];
        float[] cornerV = new float[4];
        for (int i = 0; i < 4; i++) {
            opacities[i] = graffiti.getCornerOpacity(i);
            melts[i] = graffiti.getCornerMelt(i);
            cornerH[i] = graffiti.getCornerH(i);
            cornerV[i] = graffiti.getCornerV(i);
        }

        ClientPlayNetworking.send(new UpdateGraffitiPayload(
            graffiti.getId(),
            currentTexture,
            opacities,
            melts,
            cornerH,
            cornerV
        ));
    }

    private static final class CornerSlider extends SliderWidget {
        private final Text baseLabel;
        private final java.util.function.DoubleConsumer onChange;

        private CornerSlider(int x, int y, int width, int height, Text label, double value,
                             java.util.function.DoubleConsumer onChange) {
            super(x, y, width, height, label, clamp(value));
            this.baseLabel = label;
            this.onChange = onChange;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int pct = (int) Math.round(this.value * 100.0);
            setMessage(Text.literal(baseLabel.getString() + ": " + pct + "%"));
        }

        @Override
        protected void applyValue() {
            onChange.accept(this.value);
        }

        private static double clamp(double v) {
            return Math.max(0.0, Math.min(1.0, v));
        }
    }
}

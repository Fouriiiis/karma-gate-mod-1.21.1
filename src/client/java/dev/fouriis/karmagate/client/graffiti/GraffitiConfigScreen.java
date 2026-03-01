package dev.fouriis.karmagate.client.graffiti;

import dev.fouriis.karmagate.entity.GraffitiEntity;
import dev.fouriis.karmagate.network.DeleteGraffitiPayload;
import dev.fouriis.karmagate.network.UpdateGraffitiPayload;
import net.brickcraftdream.librainworldmc.client.gui.widgets.core.WidgetOrientation;
import net.brickcraftdream.librainworldmc.client.gui.widgets.labeled.LabeledButtonWidget;
import net.brickcraftdream.librainworldmc.client.gui.widgets.labeled.LabeledConfirmButtonWidget;
import net.brickcraftdream.librainworldmc.client.gui.widgets.labeled.LabeledFormWidget;
import net.brickcraftdream.librainworldmc.client.gui.widgets.labeled.LabeledSliderWidget;
import net.brickcraftdream.librainworldmc.client.gui.widgets.labeled.display.LabeledDividerWidget;
import net.brickcraftdream.librainworldmc.client.gui.widgets.labeled.display.LabeledSubcategoryDividerWidget;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public class GraffitiConfigScreen extends Screen {
    private static final String[] CORNER_LABELS = {"Bottom Left", "Bottom Right", "Top Right", "Top Left"};

    private final GraffitiEntity graffiti;
    private final String initialTexture;
    private String currentTexture;
    private final float[] initialOpacity = new float[4];
    private final float[] initialMelt = new float[4];

    private LabeledConfirmButtonWidget deleteButton;
    private LabeledFormWidget form;
    private final List<LabeledSliderWidget> sliders = new ArrayList<>();
    private LabeledSliderWidget activeDragSlider = null;

    public GraffitiConfigScreen(GraffitiEntity graffiti) {
        super(Text.literal("Graffiti Config"));
        this.graffiti = graffiti;
        this.initialTexture = graffiti.getTexturePath();
        this.currentTexture = graffiti.getTexturePath();
        for (int i = 0; i < 4; i++) {
            initialOpacity[i] = graffiti.getCornerOpacity(i);
            initialMelt[i]    = graffiti.getCornerMelt(i);
        }
    }

    @Override
    protected void init() {
        super.init();
        sliders.clear();
        activeDragSlider = null;

        int formWidth = Math.min(360, this.width - 40);
        int formHeight = this.height - 60;
        int formX = (this.width - formWidth) / 2;
        int formY = 30;
        int dividerWidth = formWidth - 8;

        form = new LabeledFormWidget(formX, formY, formWidth, formHeight, Text.empty());

        form.addRow(new LabeledButtonWidget(0, 0, formWidth, 20,
            Text.literal("Sprite"), Text.literal(currentTexture),
            btn -> MinecraftClient.getInstance().setScreen(new GraffitiPickerScreen(tex -> {
                applyTexture(tex);
                MinecraftClient.getInstance().setScreen(new GraffitiConfigScreen(graffiti));
            })),
            List.of(), null));

        form.addRow(new LabeledDividerWidget(dividerWidth));

        for (int i = 0; i < 4; i++) {
            final int corner = i;
            LabeledSliderWidget s = new LabeledSliderWidget(0, 0, formWidth, 20,
                Text.literal("Opacity – " + CORNER_LABELS[i]),
                0.0, 1.0, graffiti.getCornerOpacity(i),
                WidgetOrientation.HORIZONTAL, List.of(),
                (w, val) -> setCornerOpacity(corner, val));
            sliders.add(s);
            form.addRow(s);
        }

        form.addRow(new LabeledDividerWidget(dividerWidth));

        for (int i = 0; i < 4; i++) {
            final int corner = i;
            LabeledSliderWidget s = new LabeledSliderWidget(0, 0, formWidth, 20,
                Text.literal("Melt – " + CORNER_LABELS[i]),
                0.0, 1.0, graffiti.getCornerMelt(i),
                WidgetOrientation.HORIZONTAL, List.of(),
                (w, val) -> setCornerMelt(corner, val));
            sliders.add(s);
            form.addRow(s);
        }

        form.addRow(new LabeledDividerWidget(dividerWidth));

        form.addRow(new LabeledButtonWidget(0, 0, formWidth, 20,
            Text.empty(), Text.literal("Done"),
            btn -> { sendUpdate(); this.close(); },
            List.of(), null));

        form.addRow(new LabeledButtonWidget(0, 0, formWidth, 20,
            Text.empty(), Text.literal("Cancel"),
            btn -> { restoreInitial(); this.close(); },
            List.of(), null));

        deleteButton = new LabeledConfirmButtonWidget(0, 0, formWidth, 20,
            Text.empty(),
            Text.literal("Delete Graffiti"),
            Text.literal("Confirm Delete"),
            60,
            () -> {
                ClientPlayNetworking.send(new DeleteGraffitiPayload(graffiti.getId()));
                this.close();
            },
            List.of(),
            null);
        form.addRow(deleteButton);

        form.positionRows();
        this.addDrawableChild(form);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        activeDragSlider = null;
        for (LabeledSliderWidget s : sliders) {
            if (s.isMouseOver(mouseX, mouseY)) {
                s.mouseClicked(mouseX, mouseY, button);
                activeDragSlider = s;
                return super.mouseClicked(mouseX, mouseY, button);
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (activeDragSlider != null) {
            activeDragSlider.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (activeDragSlider != null) {
            activeDragSlider.mouseReleased(mouseX, mouseY, button);
            activeDragSlider = null;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void tick() {
        if (!graffiti.isAlive()) { this.close(); return; }
        if (deleteButton != null) deleteButton.tick();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 10, 0xFFFFFF);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(form.getX() - 4, form.getY() - 4, form.getX() + form.getWidth() + 4, form.getHeight() + 34, 0xBB101010);
    }

    @Override
    public boolean shouldPause() { return false; }

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
        float[] melts     = new float[4];
        float[] cornerH   = new float[4];
        float[] cornerV   = new float[4];
        for (int i = 0; i < 4; i++) {
            opacities[i] = graffiti.getCornerOpacity(i);
            melts[i]     = graffiti.getCornerMelt(i);
            cornerH[i]   = graffiti.getCornerH(i);
            cornerV[i]   = graffiti.getCornerV(i);
        }
        ClientPlayNetworking.send(new UpdateGraffitiPayload(
            graffiti.getId(), currentTexture,
            opacities, melts, cornerH, cornerV
        ));
    }
}

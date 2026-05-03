package dev.fouriis.karmagate.client.graffiti;

import dev.fouriis.karmagate.entity.GraffitiEntity;
import dev.fouriis.karmagate.network.DeleteGraffitiPayload;
import dev.fouriis.karmagate.network.UpdateGraffitiPayload;
import net.brickcraftdream.librainworldmc.client.gui.widgets.container.ScreenLayoutContainerWidget;
import net.brickcraftdream.librainworldmc.client.gui.widgets.core.WidgetOrientation;
import net.brickcraftdream.librainworldmc.client.gui.widgets.labeled.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.List;

public class GraffitiConfigScreen extends Screen {
    private static final String[] CORNER_LABELS = {"Bottom Left", "Bottom Right", "Top Right", "Top Left"};

    private final GraffitiEntity graffiti;
    private final String initialTexture;
    private String currentTexture;
    private final float[] initialOpacity = new float[4];
    private final float[] initialMelt = new float[4];

    private ScreenLayoutContainerWidget layoutContainer;

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

        int formWidth = Math.min(360, this.width - 40);
        int formHeight = 304;
        int formX = (this.width - formWidth) / 2;
        int formY = this.height / 2 - formHeight / 2;

        layoutContainer = ScreenLayoutContainerWidget.builder(formX, formY, formWidth, formHeight)
                .root()
                .splitVertical()
                .areaHolderOnly()

                .addArea("sprite", 42)
                .addWidget(new LabeledButtonWidget(0, 0, formWidth, 20,
                                Text.literal("Sprite"), Text.literal(currentTexture),
                                btn -> MinecraftClient.getInstance().setScreen(new GraffitiPickerScreen(tex -> {
                                    applyTexture(tex);
                                    MinecraftClient.getInstance().setScreen(new GraffitiConfigScreen(graffiti));
                                })),
                                List.of(), null),
                        (area, widget) -> {
                            widget.setX(area.x());
                            widget.setY(area.y() + 12);
                            widget.setWidth(area.width());
                        })
                .autoMinSizeFromWidgets()
                .end()

                .addArea("opacity", 84)
                .addWidget(new LabeledProgressSliderWidget(0, 0, formWidth, 20,
                                Text.literal("Opacity – " + CORNER_LABELS[3]),
                                0.0, 1.0, graffiti.getCornerOpacity(3),
                                WidgetOrientation.HORIZONTAL, List.of(),
                                (w, val) -> setCornerOpacity(3, val)),
                        (area, widget) -> {
                            widget.setX(area.x());
                            widget.setY(area.y() + 12);
                            widget.setWidth(area.width() / 2 - 2);
                        })
                .addWidget(new LabeledProgressSliderWidget(0, 0, formWidth, 20,
                                Text.literal("Opacity – " + CORNER_LABELS[2]),
                                0.0, 1.0, graffiti.getCornerOpacity(2),
                                WidgetOrientation.HORIZONTAL, List.of(),
                                (w, val) -> setCornerOpacity(2, val)),
                        (area, widget) -> {
                            widget.setX(area.x() + area.width() / 2 + 2);
                            widget.setY(area.y() + 12);
                            widget.setWidth(area.width() / 2 - 2);
                        })
                .addWidget(new LabeledProgressSliderWidget(0, 0, formWidth, 20,
                                Text.literal("Opacity – " + CORNER_LABELS[0]),
                                0.0, 1.0, graffiti.getCornerOpacity(0),
                                WidgetOrientation.HORIZONTAL, List.of(),
                                (w, val) -> setCornerOpacity(0, val)),
                        (area, widget) -> {
                            widget.setX(area.x());
                            widget.setY(area.y() + 42 + 8);
                            widget.setWidth(area.width() / 2 - 2);
                        })
                .addWidget(new LabeledProgressSliderWidget(0, 0, formWidth, 20,
                                Text.literal("Opacity – " + CORNER_LABELS[1]),
                                0.0, 1.0, graffiti.getCornerOpacity(1),
                                WidgetOrientation.HORIZONTAL, List.of(),
                                (w, val) -> setCornerOpacity(1, val)),
                        (area, widget) -> {
                            widget.setX(area.x() + area.width() / 2 + 2);
                            widget.setY(area.y() + 42 + 8);
                            widget.setWidth(area.width() / 2 - 2);
                        })
                .autoMinSizeFromWidgets()
                .end()

                .addArea("melt", 84)
                .addWidget(new LabeledProgressSliderWidget(0, 0, formWidth, 20,
                                Text.literal("Melt – " + CORNER_LABELS[3]),
                                0.0, 1.0, graffiti.getCornerMelt(3),
                                WidgetOrientation.HORIZONTAL, List.of(),
                                (w, val) -> setCornerMelt(3, val)),
                        (area, widget) -> {
                            widget.setX(area.x());
                            widget.setY(area.y() + 12);
                            widget.setWidth(area.width() / 2 - 2);
                        })
                .addWidget(new LabeledProgressSliderWidget(0, 0, formWidth, 20,
                                Text.literal("Melt – " + CORNER_LABELS[2]),
                                0.0, 1.0, graffiti.getCornerMelt(2),
                                WidgetOrientation.HORIZONTAL, List.of(),
                                (w, val) -> setCornerMelt(2, val)),
                        (area, widget) -> {
                            widget.setX(area.x() + area.width() / 2 + 2);
                            widget.setY(area.y() + 12);
                            widget.setWidth(area.width() / 2 - 2);
                        })
                .addWidget(new LabeledProgressSliderWidget(0, 0, formWidth, 20,
                                Text.literal("Melt – " + CORNER_LABELS[0]),
                                0.0, 1.0, graffiti.getCornerMelt(0),
                                WidgetOrientation.HORIZONTAL, List.of(),
                                (w, val) -> setCornerMelt(0, val)),
                        (area, widget) -> {
                            widget.setX(area.x());
                            widget.setY(area.y() + 42 + 8);
                            widget.setWidth(area.width() / 2 - 2);
                        })
                .addWidget(new LabeledProgressSliderWidget(0, 0, formWidth, 20,
                                Text.literal("Melt – " + CORNER_LABELS[1]),
                                0.0, 1.0, graffiti.getCornerMelt(1),
                                WidgetOrientation.HORIZONTAL, List.of(),
                                (w, val) -> setCornerMelt(1, val)),
                        (area, widget) -> {
                            widget.setX(area.x() + area.width() / 2 + 2);
                            widget.setY(area.y() + 42 + 8);
                            widget.setWidth(area.width() / 2 - 2);
                        })
                .autoMinSizeFromWidgets()
                .end()

                .addArea("actions", 24)
                .addWidget(new LabeledButtonWidget(0, 0, formWidth, 20,
                                Text.empty(), Text.literal("Done"),
                                btn -> { sendUpdate(); this.close(); },
                                List.of(), null),
                        (area, widget) -> {
                            widget.setX(area.x());
                            widget.setY(area.y());
                            widget.setWidth(area.width() / 3 - 3);
                        })
                .addWidget(new LabeledConfirmButtonWidget(0, 0, formWidth, 20,
                                Text.empty(),
                                Text.literal("Delete Graffiti"),
                                Text.literal("Confirm Delete"),
                                60,
                                () -> {
                                    ClientPlayNetworking.send(new DeleteGraffitiPayload(graffiti.getId()));
                                    this.close();
                                },
                                List.of(),
                                null),
                        (area, widget) -> {
                            widget.setX(area.x() + area.width() / 3 + 1);
                            widget.setY(area.y());
                            widget.setWidth(area.width() / 3 - 3);
                        })
                .addWidget(new LabeledButtonWidget(0, 0, formWidth, 20,
                                Text.empty(), Text.literal("Cancel"),
                                btn -> { restoreInitial(); this.close(); },
                                List.of(), null),
                        (area, widget) -> {
                            widget.setX(area.x() + 2 * area.width() / 3 + 3);
                            widget.setY(area.y());
                            widget.setWidth(area.width() / 3 - 3);
                        })
                .autoMinSizeFromWidgets()
                .end()

                .endRoot()
                .build();

        this.addDrawableChild(layoutContainer);
        this.addSelectableChild(layoutContainer);
    }

    @Override
    public void tick() {
        if (!graffiti.isAlive()) { this.close(); return; }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, this.height / 2 - 168, 0xFFFFFF);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {}

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

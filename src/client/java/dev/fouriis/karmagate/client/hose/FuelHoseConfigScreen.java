package dev.fouriis.karmagate.client.hose;

import dev.fouriis.karmagate.network.CreateFuelHosePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

public class FuelHoseConfigScreen extends Screen {
    private final Screen parent;
    private final String dimensionId;
    private final BlockPos startPos;
    private final BlockPos endPos;

    private TextFieldWidget segmentField;
    private TextFieldWidget tickField;
    private TextFieldWidget gravityField;

    public FuelHoseConfigScreen(Screen parent, String dimensionId, BlockPos startPos, BlockPos endPos) {
        super(Text.literal("Fuel Hose Tool"));
        this.parent = parent;
        this.dimensionId = dimensionId;
        this.startPos = startPos;
        this.endPos = endPos;
    }

    @Override
    protected void init() {
        int panelWidth = 260;
        int panelLeft = (this.width - panelWidth) / 2;
        int y = 48;

        segmentField = new TextFieldWidget(textRenderer, panelLeft, y, panelWidth, 20, Text.literal("Segments"));
        segmentField.setText("12");
        addDrawableChild(segmentField);
        y += 28;

        tickField = new TextFieldWidget(textRenderer, panelLeft, y, panelWidth, 20, Text.literal("Simulation Ticks"));
        tickField.setText("40");
        addDrawableChild(tickField);
        y += 28;

        gravityField = new TextFieldWidget(textRenderer, panelLeft, y, panelWidth, 20, Text.literal("Gravity"));
        gravityField.setText("0.045");
        addDrawableChild(gravityField);
        y += 34;

        addDrawableChild(ButtonWidget.builder(Text.literal("Confirm"), button -> {
            int segmentCount = parseInt(segmentField.getText(), 12);
            int simulationTicks = parseInt(tickField.getText(), 40);
            float gravity = parseFloat(gravityField.getText(), 0.045f);
            ClientPlayNetworking.send(new CreateFuelHosePayload(
                    startPos.getX(), startPos.getY(), startPos.getZ(),
                    endPos.getX(), endPos.getY(), endPos.getZ(),
                    segmentCount, simulationTicks, gravity
            ));
            close();
        }).dimensions(panelLeft, y, panelWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), button -> close())
                .dimensions(panelLeft, y + 24, panelWidth, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xCC101010);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 18, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.literal("Start: " + startPos.getX() + ", " + startPos.getY() + ", " + startPos.getZ()), (width - 260) / 2, 30, 0xD0D0D0);
        context.drawTextWithShadow(textRenderer, Text.literal("End: " + endPos.getX() + ", " + endPos.getY() + ", " + endPos.getZ()), (width - 260) / 2, 38, 0xD0D0D0);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(text.trim()));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static float parseFloat(String text, float fallback) {
        try {
            return Math.max(0.0f, Float.parseFloat(text.trim()));
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
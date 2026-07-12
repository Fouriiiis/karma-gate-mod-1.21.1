package dev.fouriis.karmagate.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public class AtcCloudConfigScreen extends Screen {
    private static final int COLUMN_WIDTH = 310;
    private static final int ROW_HEIGHT = 24;
    private static final int FIELD_WIDTH = 74;
    private static final int RESET_WIDTH = 46;

    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();

    public AtcCloudConfigScreen(Screen parent) {
        super(Text.literal("Above Clouds Tuning"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        rows.clear();
        clearChildren();

        List<AtcCloudVolumeRenderer.TuningValue> values = AtcCloudVolumeRenderer.tuningValues();
        int columns = this.width >= 660 ? 2 : 1;
        int rowsPerColumn = (int) Math.ceil(values.size() / (float) columns);
        int totalWidth = columns * COLUMN_WIDTH + (columns - 1) * 14;
        int startX = (this.width - totalWidth) / 2;
        int startY = 42;

        for (int i = 0; i < values.size(); i++) {
            AtcCloudVolumeRenderer.TuningValue value = values.get(i);
            int column = i / rowsPerColumn;
            int row = i % rowsPerColumn;
            int x = startX + column * (COLUMN_WIDTH + 14);
            int y = startY + row * ROW_HEIGHT;

            TextFieldWidget field = new TextFieldWidget(
                    textRenderer,
                    x + COLUMN_WIDTH - FIELD_WIDTH - RESET_WIDTH - 6,
                    y,
                    FIELD_WIDTH,
                    18,
                    Text.literal(value.label())
            );
            field.setMaxLength(20);
            field.setText(value.formattedValue());
            field.setChangedListener(text -> applyField(value, field, text));
            addDrawableChild(field);

            ButtonWidget reset = ButtonWidget.builder(Text.literal("Reset"), button -> {
                        value.reset();
                        field.setText(value.formattedValue());
                        field.setEditableColor(0xE0E0E0);
                    })
                    .dimensions(x + COLUMN_WIDTH - RESET_WIDTH, y, RESET_WIDTH, 18)
                    .build();
            addDrawableChild(reset);
            rows.add(new Row(value, field, reset, x, y));
        }

        int bottomY = Math.min(this.height - 28, startY + rowsPerColumn * ROW_HEIGHT + 14);
        addDrawableChild(ButtonWidget.builder(Text.literal("Reset All"), button -> {
                    for (Row row : rows) {
                        row.value.reset();
                        row.field.setText(row.value.formattedValue());
                        row.field.setEditableColor(0xE0E0E0);
                    }
                })
                .dimensions(this.width / 2 - 106, bottomY, 100, 20)
                .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> close())
                .dimensions(this.width / 2 + 6, bottomY, 100, 20)
                .build());
    }

    private static void applyField(AtcCloudVolumeRenderer.TuningValue value, TextFieldWidget field, String text) {
        try {
            if (text.isBlank() || text.equals("-") || text.equals(".") || text.equals("-.")) {
                field.setEditableColor(0xFF7777);
                return;
            }
            value.set(Float.parseFloat(text.trim()));
            field.setEditableColor(0xE0E0E0);
        } catch (NumberFormatException ignored) {
            field.setEditableColor(0xFF7777);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 14, 0xFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.literal("Values apply live. Each reset restores the current hardcoded default."),
                width / 2,
                26,
                0xB8C8D8);

        for (Row row : rows) {
            int color = row.field.getText().equals(row.value.formattedValue()) ? 0xD8E0E8 : 0xA8D8FF;
            context.drawTextWithShadow(textRenderer, row.value.label(), row.x, row.y + 5, color);
            context.drawTextWithShadow(textRenderer,
                    Text.literal("default " + row.value.formattedDefault()),
                    row.x + 118,
                    row.y + 5,
                    0x8795A3);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xD010141A);
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private record Row(AtcCloudVolumeRenderer.TuningValue value,
                       TextFieldWidget field,
                       ButtonWidget reset,
                       int x,
                       int y) {
    }
}

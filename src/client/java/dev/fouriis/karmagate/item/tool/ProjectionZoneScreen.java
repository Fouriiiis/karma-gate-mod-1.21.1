package dev.fouriis.karmagate.item.tool;

import dev.fouriis.karmagate.network.CreateProjectionZonePayload;
import dev.fouriis.karmagate.network.DeleteProjectionZonePayload;
import net.brickcraftdream.librainworldmc.client.gui.widgets.labeled.LabeledButtonWidget;
import net.brickcraftdream.librainworldmc.client.gui.widgets.labeled.LabeledCheckboxWidget;
import net.brickcraftdream.librainworldmc.client.gui.widgets.labeled.LabeledIntFieldWidget;
import net.brickcraftdream.librainworldmc.client.gui.widgets.labeled.LabeledTextFieldWidget;
import net.brickcraftdream.librainworldmc.client.gui.widgets.labeled.LabeledFormWidget;
import net.brickcraftdream.librainworldmc.client.network.ClientNetworkActions;
import net.brickcraftdream.librainworldmc.tool.area.BoxPrimitive;
import net.brickcraftdream.librainworldmc.tool.area.ToolArea;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Objects;

/**
 * Settings screen for the Projection Zone selection tool.
 */
public class ProjectionZoneScreen extends Screen {

    private static final int WIDGET_HEIGHT = 20;
    private static final int PADDING = 8;

    private final Screen parent;
    private final ToolArea area;

    private LabeledTextFieldWidget nameField;
    private LabeledIntFieldWidget swarmerCountField;
    private LabeledCheckboxWidget drawCirclesCheckbox;
    private LabeledCheckboxWidget drawGridCheckbox;
    private LabeledFormWidget form;

    private String oldName = "";

    public ProjectionZoneScreen(Screen parent, ToolArea area) {
        super(Text.literal("Projection Zone Tool"));
        this.parent = parent;
        this.area = area;
    }

    @Override
    protected void init() {
        super.init();

        ProjectionZoneProperties props = area.ensureProperties(ProjectionZoneProperties.class);

        int formWidth = Math.min(360, this.width - 40);
        int formHeight = this.height - 60;
        int formX = (this.width - formWidth) / 2;
        int formY = 30;

        oldName = props.zoneName;

        form = new LabeledFormWidget(formX, formY, formWidth, formHeight, Text.empty());

        nameField = new LabeledTextFieldWidget(
                0, 0, formWidth, WIDGET_HEIGHT,
                Text.literal("Name"),
                Text.literal("zone_name"),
                List.of(Text.literal("Name to register this projection zone under")),
                (widget, value) -> props.zoneName = value
        );
        nameField.getDelegate().setText(props.zoneName);
        form.addRow(nameField);

        swarmerCountField = new LabeledIntFieldWidget(
                0, 0, formWidth, WIDGET_HEIGHT,
                Text.literal("Swarmer Count"),
                props.swarmerCount,
                List.of(Text.literal("Number of swarmers to spawn in this zone")),
                (widget, value) -> {
                    if (value != null) props.swarmerCount = value;
                }
        );
        form.addRow(swarmerCountField);

        int halfWidth = (formWidth - PADDING) / 2;

        drawCirclesCheckbox = new LabeledCheckboxWidget(
                0, 0, halfWidth, WIDGET_HEIGHT,
                Text.literal("Draw Circles"),
                Text.literal("Enabled"),
                props.drawCircles,
                List.of(Text.literal("Whether to draw glyph circles")),
                (widget, value) -> props.drawCircles = value
        );
        form.addRow(drawCirclesCheckbox);

        drawGridCheckbox = new LabeledCheckboxWidget(
                0, 0, halfWidth, WIDGET_HEIGHT,
                Text.literal("Draw Grid"),
                Text.literal("Enabled"),
                props.drawGrid,
                List.of(Text.literal("Whether to draw the projection grid")),
                (widget, value) -> props.drawGrid = value
        );
        form.addRow(drawGridCheckbox);

        LabeledButtonWidget confirmButton = new LabeledButtonWidget(
                0, 0, halfWidth, WIDGET_HEIGHT,
                Text.literal(""),
                Text.literal("Confirm"),
                btn -> {
                    String name = nameField.getText().trim();
                    if (name.isEmpty()) name = area.getId();
                    if(!Objects.equals(oldName, name)) {
                        if (!oldName.isEmpty()) {
                            ClientPlayNetworking.send(new DeleteProjectionZonePayload(oldName));
                        }
                    }
                    props.zoneName = name;
                    props.drawCircles = drawCirclesCheckbox.isChecked();
                    props.drawGrid = drawGridCheckbox.isChecked();
                    Integer sc = swarmerCountField.getValue();
                    if (sc != null) props.swarmerCount = sc;

                    area.setDisplayName(name);

                    BoxPrimitive first = area.getBoxes().stream().findFirst().orElse(null);
                    if (first == null) return;

                    ClientPlayNetworking.send(new CreateProjectionZonePayload(
                            name,
                            first.getMinX(), first.getMinY(), first.getMinZ(),
                            first.getMaxX(), first.getMaxY(), first.getMaxZ(),
                            props.swarmerCount,
                            props.drawCircles,
                            props.drawGrid
                    ));

                    ClientNetworkActions.saveArea(area);
                    close();
                }
        );
        form.addRow(confirmButton);

        LabeledButtonWidget deleteButton = new LabeledButtonWidget(
                0, 0, halfWidth, WIDGET_HEIGHT,
                Text.literal(""),
                Text.literal("Delete"),
                btn -> {
                    String name = props.zoneName.trim();
                    if (name.isEmpty()) name = nameField.getText().trim();
                    if (!name.isEmpty()) {
                        ClientPlayNetworking.send(new DeleteProjectionZonePayload(name));
                    }
                    String toolId = area.getToolId();
                    String areaId = area.getId();
                    if (toolId != null && areaId != null && !areaId.isEmpty()) {
                        ClientNetworkActions.deleteArea(toolId, areaId);
                    }
                    close();
                }
        );
        form.addRow(deleteButton);

        form.positionRows();
        this.addDrawableChild(form);
        this.addSelectableChild(form);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        nameField.getDelegate().setFocused(nameField.getDelegate().mouseClicked(mouseX, mouseY, button));
        if (form != null && form.mouseClicked(mouseX, mouseY, button)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (form != null && form.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)) return true;
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (form != null && form.mouseReleased(mouseX, mouseY, button)) return true;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (form != null && form.keyPressed(keyCode, scanCode, modifiers)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (form != null && form.keyReleased(keyCode, scanCode, modifiers)) return true;
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (form != null && form.charTyped(chr, modifiers)) return true;
        return super.charTyped(chr, modifiers);
    }

    @Override
    public void tick() {
        super.tick();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xFFFFFF);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        if (form != null) context.fill(form.getX() - 4, form.getY() - 4, form.getX() + form.getWidth() + 4, form.getHeight() + 34, 0xBB101010);
        else super.renderBackground(context, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}

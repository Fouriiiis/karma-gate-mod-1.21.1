package dev.fouriis.karmagate.item.tool;

import dev.fouriis.karmagate.network.CreateCoralNeuronPayload;
import dev.fouriis.karmagate.network.DeleteCoralNeuronPayload;
import net.brickcraftdream.librainworldmc.client.gui.widgets.labeled.LabeledButtonWidget;
import net.brickcraftdream.librainworldmc.client.gui.widgets.labeled.LabeledCheckboxWidget;
import net.brickcraftdream.librainworldmc.client.gui.widgets.labeled.LabeledNumberFieldWidget;
import net.brickcraftdream.librainworldmc.client.gui.widgets.labeled.LabeledTextFieldWidget;
import net.brickcraftdream.librainworldmc.client.gui.widgets.labeled.LabeledFormWidget;
import net.brickcraftdream.librainworldmc.client.tool.ClientNetworkActions;
import net.brickcraftdream.librainworldmc.tool.area.ToolArea;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.List;

/**
 * Settings screen for the Coral Neuron selection tool.
 *
 * <p>Reads from and writes to the area's {@link CoralNeuronProperties} so that
 * all values survive screen open/close cycles. Anchor coordinates are initialized
 * from the first box when a new area is created, and can be freely edited here.</p>
 *
 * <p>Clicking <em>Confirm</em> sends a {@link CreateCoralNeuronPayload} to the
 * server and persists all values back into the properties.
 * Clicking <em>Delete</em> sends a {@link DeleteCoralNeuronPayload}.</p>
 */
public class CoralNeuronScreen extends Screen {

    private static final int WIDGET_HEIGHT = 20;

    private final Screen parent;
    private final ToolArea area;

    private LabeledTextFieldWidget nameField;
    private LabeledNumberFieldWidget anchorAxField;
    private LabeledNumberFieldWidget anchorAyField;
    private LabeledNumberFieldWidget anchorAzField;
    private LabeledCheckboxWidget anchoredACheckbox;
    private LabeledNumberFieldWidget anchorBxField;
    private LabeledNumberFieldWidget anchorByField;
    private LabeledNumberFieldWidget anchorBzField;
    private LabeledCheckboxWidget anchoredBCheckbox;

    private LabeledFormWidget form;

    public CoralNeuronScreen(Screen parent, ToolArea area) {
        super(Text.literal("Coral Neuron Tool"));
        this.parent = parent;
        this.area = area;
    }

    @Override
    protected void init() {
        super.init();

        CoralNeuronProperties props = area.ensureProperties(CoralNeuronProperties.class);

        int formWidth = Math.min(360, this.width - 40);
        int formHeight = this.height - 60;
        int formX = (this.width - formWidth) / 2;
        int formY = 30;

        form = new LabeledFormWidget(formX, formY, formWidth, formHeight, Text.empty());

        nameField = new LabeledTextFieldWidget(
                0, 0, formWidth, WIDGET_HEIGHT,
                Text.literal("Name"),
                Text.literal("neuron_name"),
                List.of(Text.literal("Name to register this coral neuron under")),
                (widget, value) -> props.neuronName = value
        );
        nameField.getDelegate().setText(props.neuronName);
        form.addRow(nameField);

        anchorAxField = new LabeledNumberFieldWidget(
                0, 0, formWidth, WIDGET_HEIGHT,
                Text.literal("Anchor A  X"), -30000, 30000, props.anchorAx, 1, 1,
                (widget, v) -> props.anchorAx = v
        );
        anchorAxField.setValue(props.anchorAx);
        form.addRow(anchorAxField);

        anchorAyField = new LabeledNumberFieldWidget(
                0, 0, formWidth, WIDGET_HEIGHT,
                Text.literal("Y"), -512, 512, props.anchorAy, 1, 1,
                (widget, v) -> props.anchorAy = v
        );
        anchorAyField.setValue(props.anchorAy);
        form.addRow(anchorAyField);

        anchorAzField = new LabeledNumberFieldWidget(
                0, 0, formWidth, WIDGET_HEIGHT,
                Text.literal("Z"), -30000, 30000, props.anchorAz, 1, 1,
                (widget, v) -> props.anchorAz = v
        );
        anchorAzField.setValue(props.anchorAz);
        form.addRow(anchorAzField);

        anchoredACheckbox = new LabeledCheckboxWidget(
                0, 0, formWidth, WIDGET_HEIGHT,
                Text.literal("Anchor A Fixed"),
                Text.literal("Fixed to wall"),
                props.anchoredA,
                List.of(Text.literal("Whether anchor point A is pinned to the wall")),
                (widget, value) -> props.anchoredA = value
        );
        anchoredACheckbox.setChecked(props.anchoredA);
        form.addRow(anchoredACheckbox);

        anchorBxField = new LabeledNumberFieldWidget(
                0, 0, formWidth, WIDGET_HEIGHT,
                Text.literal("Anchor B  X"), -30000, 30000, props.anchorBx, 1, 1,
                (widget, v) -> props.anchorBx = v
        );
        anchorBxField.setValue(props.anchorBx);
        form.addRow(anchorBxField);

        anchorByField = new LabeledNumberFieldWidget(
                0, 0, formWidth, WIDGET_HEIGHT,
                Text.literal("Y"), -512, 512, props.anchorBy, 1, 1,
                (widget, v) -> props.anchorBy = v
        );
        anchorByField.setValue(props.anchorBy);
        form.addRow(anchorByField);

        anchorBzField = new LabeledNumberFieldWidget(
                0, 0, formWidth, WIDGET_HEIGHT,
                Text.literal("Z"), -30000, 30000, props.anchorBz, 1, 1,
                (widget, v) -> props.anchorBz = v
        );
        anchorBzField.setValue(props.anchorBz);
        form.addRow(anchorBzField);

        anchoredBCheckbox = new LabeledCheckboxWidget(
                0, 0, formWidth, WIDGET_HEIGHT,
                Text.literal("Anchor B Fixed"),
                Text.literal("Fixed to wall"),
                props.anchoredB,
                List.of(Text.literal("Whether anchor point B is pinned to the wall")),
                (widget, value) -> props.anchoredB = value
        );
        anchoredBCheckbox.setChecked(props.anchoredB);
        form.addRow(anchoredBCheckbox);

        LabeledButtonWidget confirmButton = new LabeledButtonWidget(
                0, 0, formWidth, WIDGET_HEIGHT,
                Text.literal(""),
                Text.literal("Confirm"),
                btn -> {
                    String name = nameField.getText().trim();
                    if (name.isEmpty()) name = area.getId();
                    props.neuronName = name;
                    props.anchoredA = anchoredACheckbox.isChecked();
                    props.anchoredB = anchoredBCheckbox.isChecked();
                    props.anchorAx = anchorAxField.getValue();
                    props.anchorAy = anchorAyField.getValue();
                    props.anchorAz = anchorAzField.getValue();
                    props.anchorBx = anchorBxField.getValue();
                    props.anchorBy = anchorByField.getValue();
                    props.anchorBz = anchorBzField.getValue();
                    ClientPlayNetworking.send(new CreateCoralNeuronPayload(
                            name,
                            props.anchorAx, props.anchorAy, props.anchorAz,
                            props.anchorBx, props.anchorBy, props.anchorBz,
                            props.anchoredA, props.anchoredB
                    ));
                    ClientNetworkActions.saveArea(area);
                    close();
                }
        );
        form.addRow(confirmButton);

        LabeledButtonWidget deleteButton = new LabeledButtonWidget(
                0, 0, formWidth, WIDGET_HEIGHT,
                Text.literal(""),
                Text.literal("Delete"),
                btn -> {
                    String name = props.neuronName.trim();
                    if (name.isEmpty()) name = nameField.getText().trim();
                    if (!name.isEmpty()) {
                        ClientPlayNetworking.send(new DeleteCoralNeuronPayload(name));
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
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 8, 0xFFFFFF);
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

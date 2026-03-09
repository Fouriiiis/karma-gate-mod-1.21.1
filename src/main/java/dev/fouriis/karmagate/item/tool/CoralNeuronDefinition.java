package dev.fouriis.karmagate.item.tool;

import net.brickcraftdream.librainworldmc.tool.api.SelectionToolDefinition;
import net.brickcraftdream.librainworldmc.tool.area.AreaProperties;
import net.brickcraftdream.librainworldmc.tool.area.BoxPrimitive;
import net.brickcraftdream.librainworldmc.tool.area.ToolArea;
import net.minecraft.text.Text;

import java.util.List;

import static dev.fouriis.karmagate.KarmaGateMod.MOD_ID;

/**
 * Server-side definition for the Coral Neuron selection tool.
 * Each area corresponds to exactly one CoralNeuron entity.
 * The first (and typically only) box in the area defines the two anchor endpoints:
 * anchor A = min corner of the box, anchor B = max corner of the box.
 */
public class CoralNeuronDefinition implements SelectionToolDefinition {

    public static final CoralNeuronDefinition INSTANCE = new CoralNeuronDefinition();

    @Override
    public String getToolId() {
        return "coral_neuron_tool";
    }

    @Override
    public String getNamespace() {
        return MOD_ID;
    }

    @Override
    public Text getDisplayName() {
        return Text.literal("Coral Neuron Tool");
    }

    @Override
    public List<Text> getTooltipLines() {
        return List.of(Text.literal("Tool for placing coral neurons"));
    }

    @Override
    public AreaProperties createDefaultProperties() {
        return new CoralNeuronProperties();
    }

    @Override
    public Class<? extends AreaProperties> getAreaPropertiesClass() {
        return CoralNeuronProperties.class;
    }

    @Override
    public void onBoxAdded(ToolArea area, BoxPrimitive box) {
        List<BoxPrimitive> boxes = area.getBoxes();
        if (boxes.isEmpty()) return;
        CoralNeuronProperties props = area.ensureProperties(CoralNeuronProperties.class);
        BoxPrimitive first = boxes.get(0);
        props.anchorAx = first.getMinX() + 0.5;
        props.anchorAy = first.getMinY() + 0.5;
        props.anchorAz = first.getMinZ() + 0.5;
        props.anchorBx = first.getMaxX() + 0.5;
        props.anchorBy = first.getMaxY() + 0.5;
        props.anchorBz = first.getMaxZ() + 0.5;
    }
}

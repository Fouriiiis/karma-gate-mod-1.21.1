package dev.fouriis.karmagate.item.tool;

import net.brickcraftdream.librainworldmc.tool.api.SelectionToolDefinition;
import net.brickcraftdream.librainworldmc.tool.area.AreaProperties;
import net.minecraft.text.Text;

import java.util.List;

import static dev.fouriis.karmagate.KarmaGateMod.MOD_ID;

public class ProjectionZoneDefinition implements SelectionToolDefinition {

    public static final ProjectionZoneDefinition INSTANCE = new ProjectionZoneDefinition();

    @Override
    public String getToolId() {
        return "projection_zone_tool";
    }

    @Override
    public String getNamespace() {
        return MOD_ID;
    }

    @Override
    public Text getDisplayName() {
        return Text.literal("Projection Zone Tool");
    }

    @Override
    public List<Text> getTooltipLines() {
        return List.of(Text.literal("Tool for placing projection zones"));
    }

    @Override
    public AreaProperties createDefaultProperties() {
        return new ProjectionZoneProperties();
    }

    @Override
    public Class<? extends AreaProperties> getAreaPropertiesClass() {
        return ProjectionZoneProperties.class;
    }
}

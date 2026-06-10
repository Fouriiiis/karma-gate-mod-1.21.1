package dev.fouriis.karmagate.item.tool;

import net.brickcraftdream.librainworldmc.client.tool.api.AreaSettingsScreenProvider;
import net.brickcraftdream.librainworldmc.tool.area.ToolArea;
import net.minecraft.client.gui.screen.Screen;

/**
 * Client-side definition for the Projection Zone selection tool.
 * Provides the settings screen for configuring projection zones.
 */
public class ProjectionZoneClientDefinition extends ProjectionZoneDefinition implements AreaSettingsScreenProvider {

     public static final ProjectionZoneClientDefinition INSTANCE = new ProjectionZoneClientDefinition();

    @Override
    public Screen createAreaSettingsScreen(Screen parent, ToolArea area) {
        return new ProjectionZoneScreen(parent, area);
    }

    @Override
    public void onOpenAreaSettings(ToolArea area) {
        openAreaSettings(area);
    }
}

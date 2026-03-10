package dev.fouriis.karmagate.item.tool;

import net.brickcraftdream.librainworldmc.client.api.AreaSettingsScreenProvider;
import net.brickcraftdream.librainworldmc.tool.area.ToolArea;
import net.minecraft.client.gui.screen.Screen;

/**
 * Client-side definition for the Coral Neuron selection tool.
 * Provides the settings screen for configuring coral neurons.
 */
public class CoralNeuronClientDefinition extends CoralNeuronDefinition implements AreaSettingsScreenProvider {

    public static final CoralNeuronClientDefinition INSTANCE = new CoralNeuronClientDefinition();

    @Override
    public Screen createAreaSettingsScreen(Screen parent, ToolArea area) {
        return new CoralNeuronScreen(parent, area);
    }

    @Override
    public void onOpenAreaSettings(ToolArea area) {
        openAreaSettings(area);
    }
}

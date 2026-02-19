package dev.fouriis.karmagate.item;

import net.minecraft.util.math.Direction;

import java.util.function.Consumer;

/**
 * Helper class to bridge between server-side item code and client-side picker screen.
 * The client initializer sets the opener function.
 */
public class GraffitiPickerOpener {
    
    /**
     * Record holding spawn data for the picker screen.
     */
    public record SpawnData(double x, double y, double z, Direction facing) {}
    
    /**
     * The opener function, set by the client initializer.
     */
    private static Consumer<SpawnData> pickerOpener = null;
    
    /**
     * Sets the picker opener function. Called from client initialization.
     */
    public static void setOpener(Consumer<SpawnData> opener) {
        pickerOpener = opener;
    }
    
    /**
     * Opens the graffiti picker screen with the given spawn data.
     * Only works on the client side when the opener has been set.
     */
    public static void openPicker(double x, double y, double z, Direction facing) {
        if (pickerOpener != null) {
            pickerOpener.accept(new SpawnData(x, y, z, facing));
        }
    }
}

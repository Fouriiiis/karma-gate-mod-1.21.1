package dev.fouriis.karmagate.item.tool;

import net.brickcraftdream.librainworldmc.tool.area.AreaProperties;

/**
 * Per-area properties for the Coral Neuron selection tool.
 */
public class CoralNeuronProperties extends AreaProperties {

    /** Name to register this coral neuron under. */
    public String neuronName = "";

    /** World-space X of anchor point A. */
    public double anchorAx = 0;
    /** World-space Y of anchor point A. */
    public double anchorAy = 64;
    /** World-space Z of anchor point A. */
    public double anchorAz = 0;

    /** World-space X of anchor point B. */
    public double anchorBx = 0;
    /** World-space Y of anchor point B. */
    public double anchorBy = 64;
    /** World-space Z of anchor point B. */
    public double anchorBz = 10;

    /** Whether anchor point A is fixed (wall-pinned). */
    public boolean anchoredA = true;

    /** Whether anchor point B is fixed (wall-pinned). */
    public boolean anchoredB = true;
}

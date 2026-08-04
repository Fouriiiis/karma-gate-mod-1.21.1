package dev.fouriis.karmagate.item.tool;

import dev.fouriis.karmagate.gridproject.ProjectionZoneData;
import net.brickcraftdream.librainworldmc.tool.area.AreaProperties;

/**
 * Per-area properties for the Projection Zone selection tool.
 */
public class ProjectionZoneProperties extends AreaProperties {

    /** Name to register this projection zone under. */
    public String zoneName = "";

    /** Number of swarmers to spawn across all boxes of this zone. */
    public int swarmerCount = ProjectionZoneData.DEFAULT_SWARMER_COUNT;

    /** Whether to draw glyph circles in this zone. */
    public boolean drawCircles = true;

    /** Whether to draw the projection grid in this zone. */
    public boolean drawGrid = true;

    public boolean drawStarMatrix = true;
}

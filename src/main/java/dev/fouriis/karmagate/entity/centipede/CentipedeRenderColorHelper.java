package dev.fouriis.karmagate.entity.centipede;

import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.ColorHelper.Argb;

final class CentipedeRenderColorHelper {
    private static final int JEB_CYCLE_LENGTH = 25;
    private static final int[] RAINBOW_COLORS = new int[] {
            0xFFFF2D2D,
            0xFFFF8A1C,
            0xFFFFE83B,
            0xFF3DFF57,
            0xFF34C3FF,
            0xFF5B7CFF,
            0xFFB84DFF
    };

    private CentipedeRenderColorHelper() {
    }

    static int getRenderColor(CentipedeSegmentEntity segment, float partialTick) {
        CentipedeController parent = segment.getParentCentipede();
        int shellColor = parent != null ? parent.getShellColorRGB() : 0xFFFFFF;

        Text customName = getSharedCustomName(segment, parent);
        if (customName != null && "jeb_".equals(customName.getString())) {
            return getRainbowColor(segment, parent, partialTick, shellColor);
        }

        return shellColor;
    }

    private static Text getSharedCustomName(CentipedeSegmentEntity segment, CentipedeController parent) {
        if (segment.hasCustomName()) {
            return segment.getCustomName();
        }

        if (parent instanceof Entity parentEntity && parentEntity.hasCustomName()) {
            return parentEntity.getCustomName();
        }

        return null;
    }

    private static int getRainbowColor(CentipedeSegmentEntity segment, CentipedeController parent, float partialTick, int fallbackColor) {
        int age = parent instanceof Entity parentEntity ? parentEntity.age : segment.age;
        int segmentIndex = Math.max(0, segment.getSegmentIndex());
        int colorCount = RAINBOW_COLORS.length;

        int cycle = age / JEB_CYCLE_LENGTH + segmentIndex;
        int current = Math.floorMod(cycle, colorCount);
        int next = Math.floorMod(cycle + 1, colorCount);
        float blend = ((float) (age % JEB_CYCLE_LENGTH) + partialTick) / (float) JEB_CYCLE_LENGTH;

        int currentColor = RAINBOW_COLORS[current];
        int nextColor = RAINBOW_COLORS[next];
        int rainbowColor = Argb.lerp(blend, currentColor, nextColor);

        return rainbowColor != 0 ? rainbowColor : fallbackColor;
    }
}
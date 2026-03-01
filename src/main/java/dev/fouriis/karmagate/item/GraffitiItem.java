package dev.fouriis.karmagate.item;

import net.minecraft.item.Item;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public class GraffitiItem extends Item {
    public GraffitiItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        World world = context.getWorld();
        Direction side = context.getSide();

        if (side.getAxis().isVertical()) {
            return ActionResult.PASS;
        }

        if (world.isClient) {
            Vec3d hitPos = context.getHitPos();
            double spawnX = hitPos.x + side.getOffsetX() * 0.01;
            double spawnY = hitPos.y;
            double spawnZ = hitPos.z + side.getOffsetZ() * 0.01;
            GraffitiPickerOpener.openPicker(spawnX, spawnY, spawnZ, side.getOpposite());
        }

        return ActionResult.SUCCESS;
    }
}

package dev.fouriis.karmagate.item;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
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

        // Only place on vertical surfaces (walls)
        if (side.getAxis().isVertical()) {
            return ActionResult.PASS;
        }

        if (world.isClient) {
            // Client side: open the picker screen
            Vec3d hitPos = context.getHitPos();
            
            // Offset slightly towards the player so projection goes INTO the wall
            double offsetX = side.getOffsetX() * 0.01;
            double offsetZ = side.getOffsetZ() * 0.01;
            
            double spawnX = hitPos.x + offsetX;
            double spawnY = hitPos.y;
            double spawnZ = hitPos.z + offsetZ;
            Direction facing = side.getOpposite(); // Face INTO the wall
            
            // Schedule opening the picker screen on the client
            GraffitiPickerOpener.openPicker(spawnX, spawnY, spawnZ, facing);
            
            return ActionResult.SUCCESS;
        }

        // Server side: do nothing - wait for network packet from picker
        return ActionResult.CONSUME;
    }
}

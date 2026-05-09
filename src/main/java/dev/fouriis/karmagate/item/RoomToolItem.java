package dev.fouriis.karmagate.item;

import dev.fouriis.karmagate.network.ModNetworking;
import dev.fouriis.karmagate.room.RoomSelection;
import dev.fouriis.karmagate.room.RoomSelectionManager;
import net.minecraft.item.Item;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;

public class RoomToolItem extends Item {
    public RoomToolItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        if (context.getWorld().isClient) {
            return ActionResult.SUCCESS;
        }

        if (!(context.getPlayer() instanceof ServerPlayerEntity player)) {
            return ActionResult.PASS;
        }

        BlockPos pos = context.getBlockPos();
        RoomSelection selection = RoomSelectionManager.setCorner2(player, pos);
        ModNetworking.syncRoomSelectionToPlayer(player, selection);
        player.sendMessage(Text.literal("Room corner B set to " + formatPos(pos)), true);

        return ActionResult.SUCCESS;
    }

    private static String formatPos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
}

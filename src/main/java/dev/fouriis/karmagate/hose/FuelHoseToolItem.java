package dev.fouriis.karmagate.hose;

import dev.fouriis.karmagate.network.OpenFuelHoseConfigPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.Item;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;

public class FuelHoseToolItem extends Item {
    public FuelHoseToolItem(Settings settings) {
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

        var session = FuelHoseSessionManager.get(player);
        if (session.isEmpty()) {
            player.sendMessage(Text.literal("Left click a block first to set the hose start."), false);
            return ActionResult.SUCCESS;
        }

        FuelHoseSessionManager.Selection selection = session.get();
        if (!selection.dimension().equals(player.getWorld().getRegistryKey())) {
            player.sendMessage(Text.literal("Fuel hose endpoints must be selected in the same dimension."), false);
            FuelHoseSessionManager.clear(player);
            return ActionResult.SUCCESS;
        }

        BlockPos endPos = context.getBlockPos();
        ServerPlayNetworking.send(player, new OpenFuelHoseConfigPayload(
                selection.dimension().getValue().toString(),
                selection.start().getX(), selection.start().getY(), selection.start().getZ(),
                endPos.getX(), endPos.getY(), endPos.getZ()
        ));
        return ActionResult.SUCCESS;
    }
}
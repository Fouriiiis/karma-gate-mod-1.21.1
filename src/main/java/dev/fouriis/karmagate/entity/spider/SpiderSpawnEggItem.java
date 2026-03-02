package dev.fouriis.karmagate.entity.spider;

import dev.fouriis.karmagate.KarmaGateMod;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Custom spawn egg that spawns a whole flock of spiders instead of just one.
 * When used, creates a single "leader" spider plus GROUP_MIN-GROUP_MAX additional spiders
 * scattered in a zone around the placement point.
 */
public class SpiderSpawnEggItem extends SpawnEggItem {

    public SpiderSpawnEggItem(EntityType<SpiderEntity> type, int primaryColor, int secondaryColor, Settings settings) {
        super(type, primaryColor, secondaryColor, settings);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        World world = context.getWorld();
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }

        BlockPos pos = context.getBlockPos().offset(context.getSide());
        ServerWorld serverWorld = (ServerWorld) world;

        // Spawn the leader spider
        SpiderEntity leader = KarmaGateMod.SPIDER_ENTITY_TYPE.create(serverWorld);
        if (leader != null) {
            leader.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                    world.random.nextFloat() * 360f, 0f);
            leader.setSizeFactor(0.5f + world.random.nextFloat() * 0.5f);
            leader.connectDistance = net.minecraft.util.math.MathHelper.lerp(
                    leader.getSizeFactor(), 6f, 12f) * SpiderEntity.PX;
            serverWorld.spawnEntity(leader);

            // Spawn the rest of the flock
            leader.spawnFlock();
        }

        // Consume the egg in survival
        if (context.getPlayer() != null && !context.getPlayer().isCreative()) {
            context.getStack().decrement(1);
        }

        return ActionResult.CONSUME;
    }
}

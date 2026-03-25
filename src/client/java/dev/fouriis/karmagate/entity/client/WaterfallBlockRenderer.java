package dev.fouriis.karmagate.entity.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.fouriis.karmagate.block.karmagate.HeatCoilBlock;
import dev.fouriis.karmagate.entity.karmagate.HeatCoilBlockEntity;
import dev.fouriis.karmagate.entity.karmagate.WaterfallBlockEntity;
import dev.fouriis.karmagate.particle.ModParticles;
import dev.fouriis.karmagate.sound.SteamAudioController;
import net.brickcraftdream.librainworldmc.client.render.RenderUtils;
import net.brickcraftdream.librainworldmc.client.render.shader.CoreShaderRenderer;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.fluid.FluidState;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;

public class WaterfallBlockRenderer<T extends WaterfallBlockEntity> implements BlockEntityRenderer<T> {

    private static final int MAX_BLOCKS_DOWN = 128;

    private static final Identifier LEVEL_TEXTURE =
            Identifier.of("librainworldmc", "grabtex");

    private static final Identifier NOISE_TEXTURE =
            Identifier.of("librainworldmc", "textures/rainworld/palettes/noise-hq.png");

    private static final Identifier MINECRAFT_WATER_FLOW =
            Identifier.of("minecraft", "textures/block/water_flow.png");

    public WaterfallBlockRenderer(BlockEntityRendererFactory.Context ctx) {
    }

    @Override
    public void render(
            T be,
            float tickDelta,
            MatrixStack matrices,
            VertexConsumerProvider vertexConsumers,
            int light,
            int overlay
    ) {
        World world = be.getWorld();
        if (world == null) return;

        BlockPos pos = be.getPos();

        if (world.getBlockState(pos.up()).isOf(be.getCachedState().getBlock())) {
            return;
        }

        float blocksDown = findWaterfallLength(world, pos);
        if (blocksDown <= 0.01f) return;

        handleParticles(be, tickDelta, blocksDown);

        double clientTime = world.getTime() + tickDelta;
        float flow = sampleAverageFlow(be, clientTime, blocksDown);
        if (flow <= 0.001f) return;

        boolean drewShader = renderWaterfallBillboards(be, tickDelta, flow, blocksDown, light);
        if (!drewShader) {
            System.err.println("[Karmagate/Waterfall] Waterfall shader path returned false at " + pos
                    + " flow=" + flow + " blocksDown=" + blocksDown
                    + " levelTexture=" + LEVEL_TEXTURE
                    + " noiseTexture=" + NOISE_TEXTURE
                    + " palTexture=" + MINECRAFT_WATER_FLOW);
        }
    }

    private boolean renderWaterfallBillboards(
            WaterfallBlockEntity be,
            float tickDelta,
            float flow,
            float blocksDown,
            int packedLight
    ) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.gameRenderer == null || mc.getCameraEntity() == null) {
            System.err.println("[Karmagate/Waterfall] Minecraft render context unavailable");
            return false;
        }

        Vec3d camPos = mc.gameRenderer.getCamera().getPos();
        Vec3d baseCenter = Vec3d.ofCenter(be.getPos());

        int segments = Math.max(2, Math.min(10, MathHelper.ceil(blocksDown * 1.5f)));
        double segmentHeight = blocksDown / segments;

        boolean renderedAny = false;

        for (int i = 0; i < segments; i++) {
            try {
                double segCenterY = be.getPos().getY() + 0.5 - (i + 0.5) * segmentHeight;
                Vec3d segCenter = new Vec3d(baseCenter.x, segCenterY, baseCenter.z);

                Vec3d toCamera = camPos.subtract(segCenter);
                Vec3d towardCamera = toCamera.lengthSquared() > 1.0e-6 ? toCamera.normalize() : Vec3d.ZERO;

                Vec3d drawCenter = segCenter.add(towardCamera.multiply(-0.35));

                double boxHeight = Math.max(0.75, segmentHeight + 0.25);
                double boxWidth = Math.max(1.25, 1.45 + flow * 0.75);

                Box box = Box.of(drawCenter, boxWidth, boxHeight, boxWidth);
                float boxHalf = (float) Math.max(boxWidth * 0.5, boxHeight * 0.5);

                float density = MathHelper.clamp(MathHelper.lerp(flow, 0.08f, 0.45f), 0f, 1f);
                float alpha = MathHelper.clamp(0.20f + flow * 0.55f, 0f, 1f);

                float[] spriteRect = new float[]{0f, 0f, 1f, 1f};

                float finalAlpha = alpha;
                RenderUtils.drawCameraFacingBillboardFitBoxNoScaleLargest(
                        () -> {
                            CoreShaderRenderer.bindShader$WaterFall(
                                    spriteRect,
                                    LEVEL_TEXTURE,
                                    NOISE_TEXTURE,
                                    MINECRAFT_WATER_FLOW,
                                    null,
                                    null,
                                    false
                            );
                            RenderSystem.setShaderColor(density, 0.02f, 0.02f, finalAlpha);
                        },
                        drawCenter.x, drawCenter.y, drawCenter.z,
                        box, boxHalf, boxHalf,
                        0, 0, 0,
                        1, 1, 1, finalAlpha, packedLight
                );

                renderedAny = true;
            } catch (Throwable t) {
                System.err.println("[Karmagate/Waterfall] Exception while rendering billboard segment "
                        + i + " at " + be.getPos()
                        + " flow=" + flow
                        + " blocksDown=" + blocksDown
                        + " levelTexture=" + LEVEL_TEXTURE
                        + " noiseTexture=" + NOISE_TEXTURE
                        + " palTexture=" + MINECRAFT_WATER_FLOW);
                t.printStackTrace();
            }
        }

        return renderedAny;
    }

    private static float sampleAverageFlow(WaterfallBlockEntity be, double clientTime, float blocksDown) {
        float f0 = be.getEffectiveFlow(clientTime, 0.25);
        float f1 = be.getEffectiveFlow(clientTime, Math.max(0.25, blocksDown * 0.33f));
        float f2 = be.getEffectiveFlow(clientTime, Math.max(0.25, blocksDown * 0.66f));
        float f3 = be.getEffectiveFlow(clientTime, Math.max(0.25f, blocksDown - 0.25f));
        return MathHelper.clamp((f0 + f1 + f2 + f3) * 0.25f, 0.0f, 1.0f);
    }

    @Override
    public boolean rendersOutsideBoundingBox(T blockEntity) {
        return true;
    }

    @Override
    public int getRenderDistance() {
        return 256;
    }

    private static float findWaterfallLength(World world, BlockPos origin) {
        int bottomY = world.getBottomY();
        int y = origin.getY() - 1;
        int blocks = 0;

        BlockPos.Mutable p = new BlockPos.Mutable();
        p.setX(origin.getX());
        p.setZ(origin.getZ());

        while (y >= bottomY && blocks < MAX_BLOCKS_DOWN) {
            p.setY(y);
            BlockState state = world.getBlockState(p);
            FluidState fluid = world.getFluidState(p);

            if (!fluid.isEmpty()) {
                float fluidHeight = fluid.getHeight(world, p);
                return blocks + (1.0f - fluidHeight);
            }

            if (state.isOpaqueFullCube(world, p)) {
                VoxelShape shape = state.getCollisionShape(world, p);
                double maxY = shape.isEmpty() ? 0.0 : shape.getMax(Direction.Axis.Y);
                return blocks + (1.0f - (float) maxY);
            }

            blocks++;
            y--;
        }

        return blocks;
    }

    private void handleParticles(T be, float tickDelta, float blocksDown) {
        World world = be.getWorld();
        if (world == null) return;

        BlockPos pos = be.getPos();
        double clientTime = world.getTime() + tickDelta;
        int maxIndex = (int) blocksDown + 1;

        for (int i = 1; i <= maxIndex; i++) {
            BlockPos hitPos = pos.down(i);
            BlockState hitState = world.getBlockState(hitPos);

            if (hitState.getBlock() instanceof HeatCoilBlock) {
                BlockEntity hitBe = world.getBlockEntity(hitPos);
                if (hitBe instanceof HeatCoilBlockEntity coil) {
                    float heat = coil.getHeat();
                    if (heat <= 0.01f) continue;

                    float flow = be.getEffectiveFlow(clientTime, i - 0.5f);

                    if (flow > 0.05f) {
                        float intensity = heat * flow;
                        SteamAudioController.get().onSteamBurst(hitPos, intensity);

                        if (world.random.nextFloat() < intensity * 0.8f) {
                            double px = hitPos.getX() + 0.5 + (world.random.nextDouble() - 0.5) * 0.8;
                            double py = hitPos.getY() + 1.0;
                            double pz = hitPos.getZ() + 0.5 + (world.random.nextDouble() - 0.5) * 0.8;

                            world.addParticle(ModParticles.STEAM, px, py, pz, 0, intensity, 0);
                            coil.clientPulseCool(0.15f * flow, 5);
                        }
                    }
                }
            } else if (!hitState.isAir() && i <= blocksDown) {
                float flow = be.getEffectiveFlow(clientTime, i - 0.5f);

                if (flow > 0.05f) {
                    float chance = flow * 0.5f;
                    if (world.random.nextFloat() < chance) {
                        double px = hitPos.getX() + 0.5 + (world.random.nextDouble() - 0.5) * 0.5;
                        double py = hitPos.getY() + 1.0;
                        double pz = hitPos.getZ() + 0.5 + (world.random.nextDouble() - 0.5) * 0.5;
                        world.addParticle(ParticleTypes.SPLASH, px, py, pz, 0, 0, 0);
                    }
                }
            }
        }

        float flowAtBottom = be.getEffectiveFlow(clientTime, blocksDown);
        if (flowAtBottom > 0.05f) {
            double impactX = pos.getX() + 0.5;
            double impactY = pos.getY() - blocksDown;
            double impactZ = pos.getZ() + 0.5;

            BlockPos impactBlockPos = BlockPos.ofFloored(impactX, impactY - 0.05, impactZ);
            FluidState fluidState = world.getFluidState(impactBlockPos);
            boolean isWater = !fluidState.isEmpty();

            float chance = flowAtBottom * 0.8f;
            int count = (int) chance;
            if (world.random.nextFloat() < (chance - count)) {
                count++;
            }

            for (int k = 0; k < count; k++) {
                double ox = (world.random.nextDouble() - 0.5) * 0.8;
                double oz = (world.random.nextDouble() - 0.5) * 0.8;

                world.addParticle(
                        ParticleTypes.SPLASH,
                        impactX + ox, impactY + 0.05, impactZ + oz,
                        0, 0, 0
                );

                if (isWater) {
                    world.addParticle(
                            ParticleTypes.BUBBLE,
                            impactX + ox, impactY - 0.1, impactZ + oz,
                            0, -0.3, 0
                    );
                }
            }
        }
    }
}
package dev.fouriis.karmagate.entity.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.fouriis.karmagate.block.karmagate.HeatCoilBlock;
import dev.fouriis.karmagate.entity.karmagate.HeatCoilBlockEntity;
import dev.fouriis.karmagate.entity.karmagate.WaterfallBlockEntity;
import dev.fouriis.karmagate.particle.ModParticles;
import dev.fouriis.karmagate.sound.SteamAudioController;
import net.brickcraftdream.librainworldmc.client.LibrainworldmcClient;
import net.brickcraftdream.librainworldmc.client.atlas.FAtlasElement;
import net.brickcraftdream.librainworldmc.client.atlas.FAtlasManager;
import net.brickcraftdream.librainworldmc.client.render.shader.CoreShaderRenderer;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.fluid.FluidState;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public class WaterfallBlockRenderer<T extends WaterfallBlockEntity> implements BlockEntityRenderer<T> {

    private static final int MAX_BLOCKS_DOWN = 128;
    private static final float PLANE_HALF_WIDTH = 0.70710678f;
    private static final float DEPTH_NUDGE = 0.0015f;
    private static final float V_TILES_PER_BLOCK = 1.0f;

    private static final Identifier MINECRAFT_WATER_FLOW =
            Identifier.of("minecraft", "textures/block/water_flow.png");

    private Identifier levelTexture;
    private Identifier noiseTexture;

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

        ensureTexturesResolved();

        float d = 0.70710678f;
        boolean drewShader = false;

        if (levelTexture != null && noiseTexture != null) {
            drewShader |= tryRenderShaderSheet(
                    be, tickDelta, matrices, vertexConsumers, light,
                    new Vector3f(d, 0f, d),
                    new Vector3f(-d, 0f, d),
                    blocksDown, flow
            );

            drewShader |= tryRenderShaderSheet(
                    be, tickDelta, matrices, vertexConsumers, light,
                    new Vector3f(d, 0f, -d),
                    new Vector3f(d, 0f, d),
                    blocksDown, flow
            );
        }

        // Only draw fallback if shader path did NOT render.
        if (!drewShader) {
            renderPlainSheet(
                    be, tickDelta, matrices, vertexConsumers, light,
                    new Vector3f(d, 0f, d),
                    new Vector3f(-d, 0f, d),
                    blocksDown,
                    220
            );

            renderPlainSheet(
                    be, tickDelta, matrices, vertexConsumers, light,
                    new Vector3f(d, 0f, -d),
                    new Vector3f(d, 0f, d),
                    blocksDown,
                    220
            );
        }
    }

    private void ensureTexturesResolved() {
        FAtlasManager atlasManager = LibrainworldmcClient.getAtlasManager();
        if (atlasManager == null) return;

        if (levelTexture == null) {
            FAtlasElement e = findAtlasElement(
                    atlasManager,
                    "grabtex"
            );
            if (e != null) levelTexture = e.textureIdentifier;
        }

        if (noiseTexture == null) {
            FAtlasElement e = findAtlasElement(
                    atlasManager,
                    "noise-hq.png",
                    "noise-hq",
                    "NoiseTex",
                    "noise"
            );
            if (e != null) noiseTexture = e.textureIdentifier;
        }
    }

    private boolean tryRenderShaderSheet(
            WaterfallBlockEntity be,
            float tickDelta,
            MatrixStack matrices,
            VertexConsumerProvider vertexConsumers,
            int light,
            Vector3f right,
            Vector3f normal,
            float blocksDown,
            float flow
    ) {
        try {
            renderShaderSheet(be, tickDelta, matrices, vertexConsumers, light, right, normal, blocksDown, flow);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void renderShaderSheet(
            WaterfallBlockEntity be,
            float tickDelta,
            MatrixStack matrices,
            VertexConsumerProvider vertexConsumers,
            int light,
            Vector3f right,
            Vector3f normal,
            float blocksDown,
            float flow
    ) {
        World world = be.getWorld();
        if (world == null) return;
        if (levelTexture == null || noiseTexture == null) return;

        double clientTime = world.getTime() + tickDelta;
        float vScroll = frac((float) (-clientTime * 0.06));

        /*
         * RW WaterFall shader uses vertex color:
         * R = density
         * G = top falloff
         * B = bottom falloff
         *
         * Lower density = more holes.
         */
        float density = MathHelper.clamp(MathHelper.lerp(flow, 0.06f, 0.42f), 0f, 1f);

        // Use small edge parameters like the original RW code.
        float topFalloff = 0.02f;
        float bottomFalloff = 0.02f;

        int pr = MathHelper.clamp((int) (density * 255.0f), 0, 255);
        int pg = MathHelper.clamp((int) (topFalloff * 255.0f), 0, 255);
        int pb = MathHelper.clamp((int) (bottomFalloff * 255.0f), 0, 255);
        int pa = 255;

        float[] spriteRect = new float[]{0f, 0f, 1f, 1f};

        CoreShaderRenderer.bindShader$WaterFall(
                spriteRect,
                levelTexture,
                noiseTexture,
                MINECRAFT_WATER_FLOW,
                null,
                null,
                false
        );

        RenderSystem.setShaderTexture(0, MINECRAFT_WATER_FLOW);
        VertexConsumer vc = vertexConsumers.getBuffer(RenderLayer.getEntityTranslucent(MINECRAFT_WATER_FLOW));

        matrices.push();
        matrices.translate(0.5, 0.0, 0.5);
        matrices.translate(normal.x() * DEPTH_NUDGE, 0.0, normal.z() * DEPTH_NUDGE);

        Matrix4f m = matrices.peek().getPositionMatrix();

        float rx = right.x();
        float rz = right.z();
        float nx = normal.x();
        float nz = normal.z();

        float xA = -PLANE_HALF_WIDTH * rx;
        float zA = -PLANE_HALF_WIDTH * rz;
        float xB =  PLANE_HALF_WIDTH * rx;
        float zB =  PLANE_HALF_WIDTH * rz;

        float yTop = 1.0f;
        float yBottom = -blocksDown;

        float u0 = 0.0f;
        float u1 = 1.0f;
        float v0 = vScroll;
        float v1 = blocksDown * V_TILES_PER_BLOCK + vScroll;

        vc.vertex(m, xA, yTop, zA).color(pr, pg, pb, pa).texture(u0, v0)
                .overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, 0.0f, nz);
        vc.vertex(m, xB, yTop, zB).color(pr, pg, pb, pa).texture(u1, v0)
                .overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, 0.0f, nz);
        vc.vertex(m, xB, yBottom, zB).color(pr, pg, pb, pa).texture(u1, v1)
                .overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, 0.0f, nz);
        vc.vertex(m, xA, yBottom, zA).color(pr, pg, pb, pa).texture(u0, v1)
                .overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, 0.0f, nz);

        matrices.pop();
    }

    private void renderPlainSheet(
            WaterfallBlockEntity be,
            float tickDelta,
            MatrixStack matrices,
            VertexConsumerProvider vertexConsumers,
            int light,
            Vector3f right,
            Vector3f normal,
            float blocksDown,
            int alpha
    ) {
        World world = be.getWorld();
        if (world == null) return;

        double clientTime = world.getTime() + tickDelta;
        float vScroll = frac((float) (-clientTime * 0.06));

        VertexConsumer vc = vertexConsumers.getBuffer(RenderLayer.getEntityTranslucent(MINECRAFT_WATER_FLOW));

        matrices.push();
        matrices.translate(0.5, 0.0, 0.5);
        matrices.translate(normal.x() * DEPTH_NUDGE, 0.0, normal.z() * DEPTH_NUDGE);

        Matrix4f m = matrices.peek().getPositionMatrix();

        float rx = right.x();
        float rz = right.z();
        float nx = normal.x();
        float nz = normal.z();

        float xA = -PLANE_HALF_WIDTH * rx;
        float zA = -PLANE_HALF_WIDTH * rz;
        float xB =  PLANE_HALF_WIDTH * rx;
        float zB =  PLANE_HALF_WIDTH * rz;

        float yTop = 1.0f;
        float yBottom = -blocksDown;

        float u0 = 0.0f;
        float u1 = 1.0f;
        float v0 = vScroll;
        float v1 = blocksDown * V_TILES_PER_BLOCK + vScroll;

        vc.vertex(m, xA, yTop, zA).color(255, 255, 255, alpha).texture(u0, v0)
                .overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, 0.0f, nz);
        vc.vertex(m, xB, yTop, zB).color(255, 255, 255, alpha).texture(u1, v0)
                .overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, 0.0f, nz);
        vc.vertex(m, xB, yBottom, zB).color(255, 255, 255, alpha).texture(u1, v1)
                .overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, 0.0f, nz);
        vc.vertex(m, xA, yBottom, zA).color(255, 255, 255, alpha).texture(u0, v1)
                .overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nx, 0.0f, nz);

        matrices.pop();
    }

    private static FAtlasElement findAtlasElement(FAtlasManager atlasManager, String... candidates) {
        if (atlasManager == null) return null;

        for (String candidate : candidates) {
            FAtlasElement element = atlasManager.getElementWithName(candidate);
            if (element != null && element.textureIdentifier != null) {
                return element;
            }
        }
        return null;
    }

    private static float frac(float x) {
        return x - (float) Math.floor(x);
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
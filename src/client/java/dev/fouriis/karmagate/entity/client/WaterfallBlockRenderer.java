package dev.fouriis.karmagate.entity.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.fouriis.karmagate.block.karmagate.HeatCoilBlock;
import dev.fouriis.karmagate.entity.karmagate.HeatCoilBlockEntity;
import dev.fouriis.karmagate.entity.karmagate.WaterfallBlockEntity;
import dev.fouriis.karmagate.particle.ModParticles;
import dev.fouriis.karmagate.sound.SteamAudioController;
import net.brickcraftdream.librainworldmc.client.render.shader.CoreShaderRenderer;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.fluid.FluidState;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.joml.Matrix4f;

public class WaterfallBlockRenderer<T extends WaterfallBlockEntity> implements BlockEntityRenderer<T> {
    private static final float HALF_EXTENT = 0.5f;
    private static final float DEPTH_NUDGE = 0.002f;
    private static final float WATERFALL_SURFACE_SCALE = 1.0f;
    private static final float VISUAL_DENSITY_THRESHOLD = 0.02f;

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
        if (world == null) {
            return;
        }

        BlockPos pos = be.getPos();

        if (world.getBlockEntity(pos.up()) instanceof WaterfallBlockEntity) {
            return;
        }

        float blocksDown = WaterfallBlockEntity.measureFallDistance(world, pos, WaterfallBlockEntity.MAX_BLOCKS_DOWN);
        float impactY = -blocksDown;
        be.ensureClientVisualState(impactY);

        handleParticles(be, tickDelta, blocksDown);

        float topY = be.getInterpolatedTopLocalY(tickDelta);
        float bottomY = be.getInterpolatedBottomLocalY(tickDelta);
        float visualDensity = MathHelper.clamp(be.getVisualDensity(tickDelta), 0.0f, 1.0f);
        if (visualDensity <= VISUAL_DENSITY_THRESHOLD || bottomY >= topY - 0.001f) {
            return;
        }

        renderWaterfallCrossedStreamsImmediate(
                be,
                tickDelta,
                matrices,
                light,
                topY,
                bottomY,
                visualDensity
        );
    }

    private void renderWaterfallCrossedStreamsImmediate(
            WaterfallBlockEntity be,
            float tickDelta,
            MatrixStack matrices,
            int packedLight,
            float topY,
            float bottomY,
            float visualDensity
    ) {
        try {
            CoreShaderRenderer.bindShader$WaterFall(
                    WATERFALL_SURFACE_SCALE,
                    LEVEL_TEXTURE,
                    NOISE_TEXTURE,
                    Identifier.ofVanilla("textures/misc/underwater.png"),
                    null,
                    null,
                    false,
                    false
            );

            float impactY = Math.min(bottomY, -0.001f);
            float topEdge = edgeValue(1.0f, impactY, topY);
            float bottomEdge = edgeValue(impactY, 1.0f, bottomY);

            int r = MathHelper.clamp((int) (visualDensity * 255.0f), 0, 255);
            int g = MathHelper.clamp((int) (topEdge * 255.0f), 0, 255);
            int b = MathHelper.clamp((int) (bottomEdge * 255.0f), 0, 255);
            int a = 255;

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.depthMask(false);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            RenderSystem.setShaderTexture(0, MINECRAFT_WATER_FLOW);

            matrices.push();
            matrices.translate(0.5, 0.0, 0.5);
            Matrix4f m = matrices.peek().getPositionMatrix();

            Tessellator tessellator = Tessellator.getInstance();
            BufferBuilder buffer = tessellator.begin(
                    VertexFormat.DrawMode.QUADS,
                    VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL
            );

            // \ plane
            emitPlane(
                    buffer, m,
                    -HALF_EXTENT, -HALF_EXTENT,
                     HALF_EXTENT,  HALF_EXTENT,
                    topY, bottomY, packedLight,
                    r, g, b, a,
                    -0.70710677f, 0.0f, 0.70710677f
            );

            // / plane
            emitPlane(
                    buffer, m,
                    -HALF_EXTENT,  HALF_EXTENT,
                     HALF_EXTENT, -HALF_EXTENT,
                    topY, bottomY, packedLight,
                    r, g, b, a,
                     0.70710677f, 0.0f, 0.70710677f
            );

            BufferRenderer.drawWithGlobalProgram(buffer.end());
            matrices.pop();

            RenderSystem.depthMask(true);
        } catch (Throwable t) {
            System.err.println("[Karmagate/Waterfall] Exception while rendering crossed streams at "
                    + be.getPos()
                    + " topY=" + topY
                    + " bottomY=" + bottomY
                    + " visualDensity=" + visualDensity
                    + " surfaceScale=" + WATERFALL_SURFACE_SCALE);
            t.printStackTrace();
            RenderSystem.depthMask(true);
        }
    }

    private void emitPlane(
            BufferBuilder buffer,
            Matrix4f m,
            float xA, float zA,
            float xB, float zB,
            float topY,
            float bottomY,
            int light,
            int r, int g, int b, int a,
            float nx, float ny, float nz
    ) {
        float u0 = 0.0f;
        float u1 = 1.0f;
        float v0 = 0.0f;
        float v1 = 1.0f;

        float offX = nx * DEPTH_NUDGE;
        float offZ = nz * DEPTH_NUDGE;

        buffer.vertex(m, xA + offX, topY, zA + offZ)
                .color(r, g, b, a)
                .texture(u0, v0)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(light)
                .normal(nx, ny, nz);

        buffer.vertex(m, xB + offX, topY, zB + offZ)
                .color(r, g, b, a)
                .texture(u1, v0)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(light)
                .normal(nx, ny, nz);

        buffer.vertex(m, xB + offX, bottomY, zB + offZ)
                .color(r, g, b, a)
                .texture(u1, v1)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(light)
                .normal(nx, ny, nz);

        buffer.vertex(m, xA + offX, bottomY, zA + offZ)
                .color(r, g, b, a)
                .texture(u0, v1)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(light)
                .normal(nx, ny, nz);
    }

    @Override
    public boolean rendersOutsideBoundingBox(T blockEntity) {
        return true;
    }

    @Override
    public int getRenderDistance() {
        return 256;
    }

    private static float edgeValue(float start, float end, float value) {
        float progress = inverseLerpClamped(start, end, value);
        return 1.0f / MathHelper.lerp(progress, 100.0f, 2.0f);
    }

    private static float inverseLerpClamped(float a, float b, float value) {
        if (Math.abs(b - a) <= 1.0e-5f) {
            return 0.0f;
        }

        return MathHelper.clamp((value - a) / (b - a), 0.0f, 1.0f);
    }

    private void handleParticles(T be, float tickDelta, float blocksDown) {
        World world = be.getWorld();
        if (world == null) {
            return;
        }

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
                    if (heat <= 0.01f) {
                        continue;
                    }

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
                    float chance = flow * 0.35f;
                    if (world.random.nextFloat() < chance) {
                        spawnTerrainDrip(world, pos, hitPos, flow);
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

    private static void spawnTerrainDrip(World world, BlockPos sourcePos, BlockPos hitPos, float flow) {
        double px = hitPos.getX() + 0.5 + (world.random.nextDouble() - 0.5) * 0.6;
        double py = hitPos.getY() + world.random.nextDouble();
        double pz = hitPos.getZ() + 0.5 + (world.random.nextDouble() - 0.5) * 0.6;

        double vx = (world.random.nextDouble() - 0.5) * 0.1;
        double vz = (world.random.nextDouble() - 0.5) * 0.1;
        double vy = -0.04 - flow * 0.08;

        if (Math.abs(sourcePos.getX() - hitPos.getX()) > Math.abs(sourcePos.getZ() - hitPos.getZ())) {
            vx += Math.signum(hitPos.getX() - sourcePos.getX()) * (0.04 + flow * 0.05);
        } else if (sourcePos.getZ() != hitPos.getZ()) {
            vz += Math.signum(hitPos.getZ() - sourcePos.getZ()) * (0.04 + flow * 0.05);
        }

        world.addParticle(ParticleTypes.FALLING_WATER, px, py, pz, vx, vy, vz);
        if (world.random.nextFloat() < flow * 0.4f) {
            world.addParticle(ParticleTypes.SPLASH, px, py, pz, vx * 0.5, 0.02, vz * 0.5);
        }
    }
}

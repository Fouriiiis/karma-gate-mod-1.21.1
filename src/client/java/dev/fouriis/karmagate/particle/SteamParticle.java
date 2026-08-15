package dev.fouriis.karmagate.particle;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleFactory;
import net.minecraft.client.particle.ParticleTextureSheet;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.SimpleParticleType;

/**
 * One-tick adapter from Minecraft's particle-spawn API into the Rain World
 * style steam system. The visible smoke is simulated and rendered by
 * {@link SteamSmokeSystem}; it is deliberately not a set of Minecraft
 * billboard particles.
 */
@Environment(EnvType.CLIENT)
public final class SteamParticle extends Particle {
    private SteamParticle(
            ClientWorld world,
            double x,
            double y,
            double z,
            double sourceOffsetX,
            double intensity,
            double sourceOffsetZ
    ) {
        super(world, x, y, z);
        SteamSmokeSystem.emit(
                world,
                x,
                y,
                z,
                sourceOffsetX,
                intensity,
                sourceOffsetZ
        );
        this.maxAge = 1;
    }

    @Override
    public void tick() {
        this.markDead();
    }

    @Override
    public void buildGeometry(VertexConsumer vertices, Camera camera, float tickDelta) {
        // SteamSmokeSystem owns all visible cap and bridge geometry.
    }

    @Override
    public ParticleTextureSheet getType() {
        return ParticleTextureSheet.NO_RENDER;
    }

    @Environment(EnvType.CLIENT)
    public static final class Factory implements ParticleFactory<SimpleParticleType> {
        @Override
        public Particle createParticle(
                SimpleParticleType type,
                ClientWorld world,
                double x,
                double y,
                double z,
                double sourceOffsetX,
                double intensity,
                double sourceOffsetZ
        ) {
            return new SteamParticle(
                    world,
                    x,
                    y,
                    z,
                    sourceOffsetX,
                    intensity,
                    sourceOffsetZ
            );
        }
    }
}

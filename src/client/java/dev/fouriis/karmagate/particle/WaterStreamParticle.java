package dev.fouriis.karmagate.particle;

import dev.fouriis.karmagate.block.karmagate.HeatCoilBlock;
import dev.fouriis.karmagate.entity.karmagate.HeatCoilBlockEntity;
import dev.fouriis.karmagate.sound.SteamAudioController;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.BlockState;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleFactory;
import net.minecraft.client.particle.ParticleTextureSheet;
import net.minecraft.client.particle.SpriteBillboardParticle;
import net.minecraft.client.particle.SpriteProvider;
import net.minecraft.client.color.world.BiomeColors;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.SimpleParticleType;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

@Environment(EnvType.CLIENT)
public class WaterStreamParticle extends SpriteBillboardParticle {
    private final SpriteProvider sprites;

    private boolean emittedSteam = false;
    // Tracks which solid blocks we've already splashed in to avoid duplicate spawns per block
    private final java.util.HashSet<BlockPos> splashedBlocks = new java.util.HashSet<>();

    // world dimensions
    private final float widthW;   // 5 px = 5/16 block
    private final float heightW;  // 20–40 px = 20/16..40/16 block

    // cache for biome tint update (recalculate only when entering a new block pos)
    private int lastColorX = Integer.MIN_VALUE;
    private int lastColorZ = Integer.MIN_VALUE;

    protected WaterStreamParticle(ClientWorld world, double x, double y, double z,
                                  double vx, double vy, double vz,
                                  SpriteProvider sprites) {
        super(world, x, y, z, vx, vy, vz);
        this.sprites = sprites;

    // Expand initial spawn area by ±0.5 blocks on X/Z
    this.x += (this.random.nextDouble() - 0.5) * 1.0;
    this.z += (this.random.nextDouble() - 0.5) * 1.0;
    this.prevPosX = this.x;
    this.prevPosY = this.y;
    this.prevPosZ = this.z;

    // Pick initial sprite (and keep syncing per age)
        this.setSpriteForAge(sprites);

    // Increase initial velocities: make the stream move faster
    this.velocityX = vx * 1.5;
    this.velocityY = vy * 2.0;
    this.velocityZ = vz * 1.5;

        // Fixed strip dimensions
        this.widthW  = 5f / 16f;
    // Triple the strip length: previously 20..40 px => now ~60..120 px
    this.heightW = (3f * (40 + this.random.nextInt(21))) / 16f; // ~60..120 px

    // Triple lifetime: previously 20..39 ticks => now ~60..117 ticks
    this.maxAge = 3 * (20 + this.random.nextInt(20));
    // Even faster falling speed (gravity): previously 0.06 * 3 => 0.18, now 0.27
    this.gravityStrength = 0.5f;
    // Initial tint from biome water color
    updateBiomeTintIfNeeded();
        this.alpha = 0.9f;

        this.collidesWithWorld = false; // we handle special interaction ourselves
    }

    @Override
    public void tick() {
        super.tick();

        // Advance animated sprite frames if present
        this.setSpriteForAge(this.sprites);

        // Update tint if we moved into a new block (biome water color may change)
        updateBiomeTintIfNeeded();

        // gentle fade
        this.alpha *= 0.985f;

        // Detect entering solid blocks along movement path and spawn a one-time splash per block
        spawnSplashOnNewSolidBlocksAlongPath();

        // Heat coil contact -> spawn steam once with intensity = coil heat
        if (!this.emittedSteam) {
            BlockPos p = BlockPos.ofFloored(this.x, this.y, this.z);
            BlockState state = this.world.getBlockState(p);

            if (state.getBlock() instanceof HeatCoilBlock) {
                var be = this.world.getBlockEntity(p);
                if (be instanceof HeatCoilBlockEntity coil) {
                    float heat = MathHelper.clamp(coil.getHeat(), 0f, 1f);
                    if (heat > 0.01f) {
                        double offsetX = (this.random.nextDouble() - 0.5) * 0.08;
                        double offsetZ = (this.random.nextDouble() - 0.5) * 0.08;
                        this.world.addParticle(
                                ModParticles.STEAM,
                                this.x + offsetX, this.y + 0.05, this.z + offsetZ,
                                0.0, heat, 0.0 // encode intensity in vy
                        );
                        SteamAudioController.get().onSteamBurst(BlockPos.ofFloored(this.x, this.y, this.z), heat);
                        coil.drainHeat(0.02f * heat);
                        // Client-side visual flicker: sharp, randomized dip, then fast recovery
                        // Peak dip up to ~0.35 at high heat with slight randomness
                        float rand = (float)this.random.nextDouble();
                        float peakDip = 0.10f + 0.20f * heat + 0.05f * rand; // ~0.10..0.35
                        int duration = 8 + (int)(6 * heat) + (int)(2 * rand);  // ~8..16
                        coil.clientPulseCool(peakDip, duration);
                    }
                    this.emittedSteam = true;
                }
            }
        }

        if (this.onGround) this.markDead();
    }

    private void spawnSplashOnNewSolidBlocksAlongPath() {
        // Segment from previous to current position
        double dx = this.x - this.prevPosX;
        double dy = this.y - this.prevPosY;
        double dz = this.z - this.prevPosZ;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = Math.max(1, (int)Math.ceil(dist / 0.3)); // sample at ~0.3 block increments

        BlockPos lastChecked = null;
        for (int i = 0; i <= steps; i++) {
            double t = (steps == 0) ? 1.0 : (i / (double)steps);
            double sx = this.prevPosX + dx * t;
            double sy = this.prevPosY + dy * t;
            double sz = this.prevPosZ + dz * t;
            BlockPos bp = BlockPos.ofFloored(sx, sy, sz);
            if (lastChecked != null && lastChecked.equals(bp)) continue; // skip duplicate samples in same block
            lastChecked = bp;

            BlockState s = this.world.getBlockState(bp);
            if (s.isAir()) continue;
            // Require non-empty collision shape so we only splash on solid-ish blocks
            if (s.getCollisionShape(this.world, bp).isEmpty()) continue;
            if (splashedBlocks.add(bp)) {
                spawnSplashParticles(sx, sy + 0.05, sz);
            }
        }
    }

    private void spawnSplashParticles(double x, double y, double z) {
        // Splash particles inherit the stream's current downward velocity (Y),
        // with a bit of lateral carryover and jitter so they feel connected to the stream.
        final double baseVx = this.velocityX * 0.25; // small horizontal carryover
        final double baseVy = this.velocityY;        // typically negative (falling)
        final double baseVz = this.velocityZ * 0.25;

        for (int i = 0; i < 6; i++) {
            double jx = (this.random.nextDouble() - 0.5) * 0.10;
            double jz = (this.random.nextDouble() - 0.5) * 0.10;
            double scale = 0.55 + this.random.nextDouble() * 0.25; // 0.55..0.80 of current downward speed
            double rvy = baseVy * scale;
            // Ensure it's still downward but not excessively fast
            rvy = MathHelper.clamp(rvy, -0.40, -0.02);

            double rvx = baseVx + jx;
            double rvz = baseVz + jz;

            this.world.addParticle(ParticleTypes.SPLASH, x, y, z, rvx, rvy, rvz);
        }
    }

    private void updateBiomeTintIfNeeded() {
        int cx = MathHelper.floor(this.x);
        int cz = MathHelper.floor(this.z);
        if (cx != lastColorX || cz != lastColorZ) {
            BlockPos p = BlockPos.ofFloored(this.x, this.y, this.z);
            int color = BiomeColors.getWaterColor(this.world, p);
            setColorFromInt(color);
            lastColorX = cx;
            lastColorZ = cz;
        }
    }

    private void setColorFromInt(int rgb) {
        float r = ((rgb >> 16) & 0xFF) / 255.0f;
        float g = ((rgb >> 8)  & 0xFF) / 255.0f;
        float b = ( rgb        & 0xFF) / 255.0f;
        this.red = r;
        this.green = g;
        this.blue = b;
    }

    /**
     * Render as a vertical strip that always faces the camera (yaw-only billboard).
     */
    @Override
    public void buildGeometry(VertexConsumer vc, Camera camera, float tickDelta) {
        // Camera-relative position
        double camX = camera.getPos().x;
        double camY = camera.getPos().y;
        double camZ = camera.getPos().z;

        float px = (float)(MathHelper.lerp(tickDelta, this.prevPosX, this.x) - camX);
        float py = (float)(MathHelper.lerp(tickDelta, this.prevPosY, this.y) - camY);
        float pz = (float)(MathHelper.lerp(tickDelta, this.prevPosZ, this.z) - camZ);

        float hw = this.widthW * 0.5f;
        float h  = this.heightW;

        // Horizontal forward (particle -> camera) projected to XZ
        float fx = -px;           // camera at origin in view-space
        float fz = -pz;
        float fl = MathHelper.sqrt(fx * fx + fz * fz);
        if (fl < 1.0e-4f) {
            // Degenerate (camera very close) — pick an arbitrary facing
            fx = 0f; fz = 1f; fl = 1f;
        }
        fx /= fl;
        fz /= fl;

        // Right vector = up(0,1,0) x forward = (fz, 0, -fx)
        float rx = fz;
        float rz = -fx;

        // Current sprite UVs
        float u0 = this.getMinU();
        float u1 = this.getMaxU();
        float v0 = this.getMinV();
        float v1 = this.getMaxV();

        int light   = this.getBrightness(tickDelta);
        int overlay = 0;

        // Pack ARGB
        int a = (int)(this.alpha * 255.0f);
        int r = (int)(this.red   * 255.0f);
        int g = (int)(this.green * 255.0f);
        int b = (int)(this.blue  * 255.0f);
        int color = (a << 24) | (r << 16) | (g << 8) | b;

        // Normal: face the camera horizontally
        float nx = fx;
        float ny = 0f;
        float nz = fz;

        // Build vertical quad using right vector horizontally, world-up vertically
        vc.vertex(px - hw * rx, py,       pz - hw * rz, color, u0, v1, overlay, light, nx, ny, nz);
        vc.vertex(px + hw * rx, py,       pz + hw * rz, color, u1, v1, overlay, light, nx, ny, nz);
        vc.vertex(px + hw * rx, py + h,   pz + hw * rz, color, u1, v0, overlay, light, nx, ny, nz);
        vc.vertex(px - hw * rx, py + h,   pz - hw * rz, color, u0, v0, overlay, light, nx, ny, nz);
    }

    @Override
    public ParticleTextureSheet getType() {
        return ParticleTextureSheet.PARTICLE_SHEET_TRANSLUCENT;
    }

    @Environment(EnvType.CLIENT)
    public static class Factory implements ParticleFactory<SimpleParticleType> {
        private final SpriteProvider sprites;
        public Factory(SpriteProvider sprites) { this.sprites = sprites; }
        @Override
        public Particle createParticle(SimpleParticleType type, ClientWorld world,
                                       double x, double y, double z,
                                       double vx, double vy, double vz) {
            return new WaterStreamParticle(world, x, y, z, vx, vy, vz, sprites);
        }
    }
}

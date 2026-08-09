// dev/fouriis/karmagate/client/DistantStructuresRenderer.java
package dev.fouriis.karmagate.client;

import com.google.gson.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.*;
import net.minecraft.client.render.RenderPhase.Texture;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static net.minecraft.client.render.RenderPhase.*;

/**
 * Renders distant billboard sprites AFTER Iris has done its fog/composite pass.
 * Call {@link #renderLate(float, Camera)} from a mixin injected at the end of WorldRenderer.render().
 */
public final class DistantStructuresRenderer {

    private static final Identifier CONFIG_ID = Identifier.of("karma-gate-mod", "structures/distant_structures.json");
    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static boolean loaded = false;

    // Emissive overlay textures for the “lightning” glow
    private static final Identifier LIGHT1 = Identifier.of("karma-gate-mod", "structures/atc_light1.png");
    private static final Identifier LIGHT2 = Identifier.of("karma-gate-mod", "structures/atc_light2.png");
    private static final Identifier LIGHT3 = Identifier.of("karma-gate-mod", "structures/atc_light3.png");
    private static final Identifier LIGHTP = Identifier.of("karma-gate-mod", "structures/atc_fivepebbleslight.png");
    private static final Identifier CLOUD_EDGE = Identifier.of("karma-gate-mod", "clouds/distantclouds.png");
    private static final int CLOUD_EDGE_SEGMENTS = 28;
    private static final long CLOUD_EDGE_TIME_WRAP_TICKS = 24_000L;
    private static final int FULL_BRIGHT = LightmapTextureManager.pack(15, 15);
    // The previous renderer's 42,000-block full-fog distance corresponds to
    // AboveCloudsView's 600-depth endpoint, giving 70 world blocks per C#
    // background-scene depth unit.
    private static final float CSHARP_DEPTH_WORLD_SCALE = 70.0f;
    private static final float CSHARP_ATMOSPHERE_END_DEPTH = 600.0f;

    // Per-entry lightning state (RW-like)
    private static final Map<Entry, Lightning> LIGHTNING = new ConcurrentHashMap<>();

    // Cache source image sizes to preserve overlay/base size ratio
    private static final Map<Identifier, int[]> TEX_SIZE = new ConcurrentHashMap<>();

    // Keep the coplanar light illustration just in front of the depth-writing
    // base. A negative bias allowed the base to reject much of its own glow.
    private static final float GLOW_Z_PUSH = 0.05f;
    private static NativeImage cloudEdgeImage;
    private static int cloudEdgeW;
    private static int cloudEdgeH;
    private static boolean triedCloudEdgeLoad;

    /** Billboard entry in world space (units = blocks). */
    public record Entry(
            Identifier texture,
            double x, double y, double z,
            float width, float height,
            boolean emissive,     // ignored for base
            boolean alwaysVisible // when true, clamp to far sphere but keep world anchoring
    ) {}

    private DistantStructuresRenderer() {}

    /* ----------------------------------------------------------------------
       PUBLIC: late render entry (call from WorldRenderer mixin @At("RETURN"))
       ---------------------------------------------------------------------- */
    public static void renderLate(float tickDelta, Camera camera) {
        renderLate(tickDelta, camera, true, false);
    }

    public static void renderLightningLate(float tickDelta, Camera camera) {
        renderLate(tickDelta, camera, false, true);
    }

    private static void renderLate(float tickDelta, Camera camera, boolean renderBase, boolean renderGlow) {
        ensureLoaded();
        if (ENTRIES.isEmpty() || camera == null) return;

        MinecraftClient mc = MinecraftClient.getInstance();

        // DistantLightning.Update runs once per game tick and DrawSprites calls
        // LightIntensity once per rendered frame, even when its altitude gate
        // makes the sprite invisible. Preserve both timelines independently.
        if (renderGlow && mc.world != null) {
            prepareLightningFrame(mc.world.getTime(), tickDelta);
        }

        // --- Height-based visibility (camera Y) ---
        float camY = (float) camera.getPos().y;
        float heightVis = AtcCloudVolumeRenderer.aboveCloudsVisibility(camY);
        if (heightVis <= 0.001f) return; // entirely hidden
        AtcSkyRenderer.CloudPalette cloudPalette = AtcSkyRenderer.cloudPalette(tickDelta);
        Vector3f atmosphere = cloudPalette.atmosphere();
        Vector3f multiplyColor = cloudPalette.multiply();

        // Build VIEW = R^{-1} * T(-camPos)
        Vec3d camPos = camera.getPos();
        Matrix4f view = new Matrix4f()
                .rotation(camera.getRotation())
                .transpose()
                .translate((float) -camPos.x, (float) -camPos.y, (float) -camPos.z);

        // Apply vanilla bobbing BEFORE the view
        MatrixStack matrices = new MatrixStack();
        if (mc.options.getBobView().getValue()) {
            ((dev.fouriis.karmagate.mixin.client.GameRendererAccessor) mc.gameRenderer)
                    .karmaGate$invokeBobView(matrices, tickDelta);
        }
        matrices.peek().getPositionMatrix().mul(view);

        // Dynamic FOV (exact) + extended far plane
        double dynFovDeg = ((dev.fouriis.karmagate.mixin.client.GameRendererAccessor) mc.gameRenderer)
                .karmaGate$invokeGetFov(camera, tickDelta, true);
        float fovRad = (float) Math.toRadians(dynFovDeg);

        float aspect = (float) mc.getWindow().getFramebufferWidth() / Math.max(1, mc.getWindow().getFramebufferHeight());
        Matrix4f extendedProj = AtcCloudVolumeRenderer.cloudProjection(
                mc,
                fovRad,
                aspect
        );
        float far = AtcCloudVolumeRenderer.cloudProjectionFar(mc);

        Matrix4f savedProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        Matrix4fStack mvStack = RenderSystem.getModelViewStack();
        mvStack.pushMatrix();
        Matrix4f savedModelView = new Matrix4f(mvStack);
        mvStack.identity();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(extendedProj, VertexSorter.BY_DISTANCE);

        VertexConsumerProvider.Immediate immediate = mc.getBufferBuilders().getEntityVertexConsumers();

        // Only Five Pebbles uses an external intensity multiplier in C#: it
        // rises during the dusk-to-night stage. Ordinary structure lightning
        // remains at multiplier 1 throughout the cycle.
        float nightFactor = AtcSkyRenderer.nightWeight(tickDelta);

        if (renderBase) {
            bindStructurePalette(atmosphere, multiplyColor);
        }

        // Back-to-front sort for translucency
        List<Entry> sorted = new ArrayList<>(ENTRIES);
        sorted.sort((a, b) -> Double.compare(
                camPos.squaredDistanceTo(b.x, b.y, b.z),
                camPos.squaredDistanceTo(a.x, a.y, a.z)
        ));

        for (Entry e : sorted) {
            Vec3d target = new Vec3d(e.x, e.y, e.z);

            // relative vector + optional far clamp
            Vec3d rel = target.subtract(camPos);
            Vec3d place = target;
            if (e.alwaysVisible) {
                double dist = rel.length();
                double maxDist = Math.max(1.0, far * 0.98);
                if (dist > maxDist) {
                    rel = rel.normalize().multiply(maxDist);
                    place = camPos.add(rel);
                }
            }

            // Billboard yaw
            float yawRad = (float) Math.atan2(-rel.x, -rel.z);
            if (Float.isNaN(yawRad)) yawRad = 0f;

            float dist = (float) rel.length();
            int atmosphereDepth = colorByte(csharpAtmosphereDepth(dist, e.texture()));

            matrices.push();
            matrices.translate((float) place.x, (float) place.y, (float) place.z);
            matrices.multiply(RotationAxis.POSITIVE_Y.rotation(yawRad));

            float cloudEdgePhase = edgePhase(mc.world != null ? mc.world.getTime() : 0L, tickDelta, e);
            float structureWidth = structureWorldWidth(e);
            if (renderBase) {
                // ---- Base, non-emissive pass ----
                matrices.push();
                matrices.scale(structureWidth, e.height, 1f);
                VertexConsumer vcBase = immediate.getBuffer(baseLayer(e.texture()));
                int baseA = MathHelper.clamp((int)(255f * heightVis), 0, 255);
                float cutY = (AtcCloudVolumeRenderer.distantStructureCloudCutY() - (float) place.y) / e.height;
                float cloudFadeHeight = cloudEdgeFadeHeight(e.height);
                // DistantBkgObject reads atmospheric depth from vertex red;
                // green and blue are intentionally unused by the shader.
                renderQuadAbove(vcBase, matrices, 1f, 1f, cutY, FULL_BRIGHT,
                        atmosphereDepth, 0, 0, baseA, cloudEdgePhase, cloudFadeHeight);
                matrices.pop(); // base scale
            }

            // ---- Emissive lightning overlay ----
            Identifier lightTex = overlayFor(e.texture());
            if (renderGlow && lightTex != null && mc.world != null) {
                Lightning L = LIGHTNING.computeIfAbsent(e, DistantStructuresRenderer::makeLightning);
                // Preserve the original renderer's day-to-night brightness
                // range, but lower its final sprite-alpha scale by 20% so the
                // flashes retain their presence without washing out the art.
                L.intensityMultiplier = MathHelper.lerp(nightFactor, 0.375f, 1.5f);
                float positionVisibility = heightVis;

                float alpha = L.frameIntensity * L.intensityMultiplier * 0.40f * positionVisibility;
                int ia = MathHelper.clamp((int) (alpha * 255.0f), 0, 255);
                if (ia > 0) {
                    float[] ratio = overlayScaleRatio(e.texture(), lightTex);
                    float sx = ratio[0];
                    float sy = ratio[1];

                    matrices.push();
                    float yOffset = lightningYOffset(e);
                    matrices.translate(0f, yOffset, +GLOW_Z_PUSH);
                    // Use the base texture's height-derived pixel scale for
                    // both lightning axes. This keeps its larger source canvas
                    // aligned without reintroducing the configured width.
                    matrices.scale(structureWidth * sx, e.height * sy, 1f);

                    VertexConsumer vcGlow = immediate.getBuffer(glowLayer(lightTex));
                    renderQuad(vcGlow, matrices, 1f, 1f, FULL_BRIGHT, 255, 255, 255, ia);
                    matrices.pop();
                }
            }

            matrices.pop(); // base transform (pos + yaw)
        }

        immediate.draw();
        mvStack.set(savedModelView);
        mvStack.popMatrix();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(savedProj, VertexSorter.BY_DISTANCE);
    }

    /* ----------------------------------------------------------------------
       Texture ratio helpers
       ---------------------------------------------------------------------- */

    /**
     * Width is derived exclusively from the configured height and the source
     * image aspect ratio. Entry.width remains readable for config compatibility
     * but does not affect either the base or lightning visuals.
     */
    private static float structureWorldWidth(Entry entry) {
        int[] size = getTextureSize(entry.texture());
        if (size[0] <= 0 || size[1] <= 0) {
            return entry.height();
        }
        return entry.height() * size[0] / (float) size[1];
    }

    private static float[] overlayScaleRatio(Identifier baseTex, Identifier overlayTex) {
        int[] base = getTextureSize(baseTex);     // [w,h]
        int[] over = getTextureSize(overlayTex);  // [w,h]
        if (base[0] <= 0 || base[1] <= 0 || over[0] <= 0 || over[1] <= 0) {
            return new float[]{1f, 1f};
        }
        float sx = (float) over[0] / (float) base[0];
        float sy = (float) over[1] / (float) base[1];
        return new float[]{sx, sy};
    }

    private static int[] getTextureSize(Identifier id) {
        return TEX_SIZE.computeIfAbsent(id, tex -> {
            try {
                ResourceManager rm = MinecraftClient.getInstance().getResourceManager();
                var opt = rm.getResource(tex);
                if (opt.isEmpty()) return new int[]{0, 0};
                Resource res = opt.get();
                try (InputStream in = res.getInputStream()) {
                    NativeImage img = NativeImage.read(in);
                    int[] size = new int[]{img.getWidth(), img.getHeight()};
                    img.close();
                    return size;
                }
            } catch (Throwable ignored) {
                return new int[]{0, 0};
            }
        });
    }

    /* ----------------------------------------------------------------------
       Rain-World-like lightning logic (tick-based)
       ---------------------------------------------------------------------- */

    private static Lightning makeLightning(Entry e) {
        long seed = seedFrom(e);
        return new Lightning(seed);
    }

    private static void prepareLightningFrame(long worldTick, float minecraftTickDelta) {
        // Keep the original Minecraft cadence: one state update per 20 TPS
        // world tick, with normal partial-tick interpolation between states.
        float timeStacker = MathHelper.clamp(minecraftTickDelta, 0.0f, 1.0f);
        for (Entry entry : ENTRIES) {
            if (overlayFor(entry.texture()) == null) {
                continue;
            }
            Lightning lightning = LIGHTNING.computeIfAbsent(entry, DistantStructuresRenderer::makeLightning);
            lightning.updateTo(worldTick);
            lightning.frameIntensity = lightning.lightIntensity(timeStacker);
        }
    }

    private static long seedFrom(Entry e) {
        long h = 1469598103934665603L;
        h ^= e.texture.toString().hashCode(); h *= 1099511628211L;
        h ^= Double.doubleToLongBits(e.x);   h *= 1099511628211L;
        h ^= Double.doubleToLongBits(e.y);   h *= 1099511628211L;
        h ^= Double.doubleToLongBits(e.z);   h *= 1099511628211L;
        return h;
    }

    private static Identifier overlayFor(Identifier base) {
        String p = base.getPath();
        if (p.endsWith("atc_structure1.png")) return LIGHT1;
        if (p.endsWith("atc_structure2.png")) return LIGHT2;
        if (p.endsWith("atc_structure3.png")) return LIGHT3;
        if (p.endsWith("atc_fivepebbles.png")) return LIGHTP;
        return null;
    }

    /** Exact DrawSprites Y offsets, converted from source pixels to blocks. */
    private static float lightningYOffset(Entry entry) {
        float pixelOffset;
        String path = entry.texture().getPath();
        if (path.endsWith("atc_structure1.png")) {
            pixelOffset = -34.0f;
        } else if (path.endsWith("atc_structure2.png")) {
            pixelOffset = -16.0f;
        } else if (path.endsWith("atc_structure3.png")) {
            pixelOffset = -8.0f;
        } else if (path.endsWith("atc_fivepebbles.png")) {
            pixelOffset = 30.0f;
        } else {
            return 0.0f;
        }

        int[] baseSize = getTextureSize(entry.texture());
        if (baseSize[1] <= 0) {
            return 0.0f;
        }
        return pixelOffset * entry.height() / baseSize[1];
    }

    private static final class Lightning {
        final Random rng;

        int wait;
        int tinyThunderWait;
        int tinyThunder;
        int tinyThunderLength;
        int thunder;
        int thunderLength;
        float randomLevel;
        int randomLevelChange;
        float power;

        float lastIntensity;
        float intensity;
        float frameIntensity;

        float intensityMultiplier = 1f;

        long lastTickAdvanced = Long.MIN_VALUE;

        Lightning(long seed) {
            this.rng = new Random(seed);
            this.tinyThunderWait = 5;
        }

        private void resetBurst() {
            this.wait = (int) lerp(10.0f, 440.0f, rng.nextFloat());
            this.power = lerp(0.7f, 1.0f, rng.nextFloat());
            int maxExclusive = (int) lerp(10.0f, 32.0f, power);
            this.thunderLength = randomRange(1, maxExclusive);
        }

        void updateTo(long nowTick) {
            if (lastTickAdvanced == Long.MIN_VALUE) {
                // The C# object receives Update before its first DrawSprites.
                lastTickAdvanced = nowTick - 1L;
            }
            if (nowTick < lastTickAdvanced) {
                lastTickAdvanced = nowTick - 1L;
            }
            if (nowTick == lastTickAdvanced) return;

            for (long t = lastTickAdvanced + 1; t <= nowTick; t++) stepOneTick();
            lastTickAdvanced = nowTick;
        }

        private void stepOneTick() {
            randomLevelChange--;
            if (randomLevelChange < 1) {
                randomLevelChange = 1 + rng.nextInt(5);
                randomLevel = rng.nextFloat();
            }

            if (wait > 0) {
                wait--;
                if (wait < 1) {
                    thunder = thunderLength;
                }
            } else {
                thunder--;
                if (thunder < 1) {
                    resetBurst();
                }
            }

            if (tinyThunderWait > 0) {
                tinyThunderWait--;
                if (tinyThunderWait < 1) {
                    tinyThunderWait = randomRange(10, 80);
                    tinyThunderLength = randomRange(5, tinyThunderWait);
                    tinyThunder = tinyThunderLength;
                }
            }

            lastIntensity = intensity;

            float a = 0f;
            float b = 0f;

            if (thunder > 0) {
                float thunderFac = 1f - (float) thunder / (float) Math.max(1, thunderLength);
                float expo = lerp(3f, 0.1f, (float) Math.sin(thunderFac * Math.PI));
                a = (float) Math.pow(clamp01(randomLevel), expo);
            }

            if (tinyThunder > 0) {
                tinyThunder--;
                float tinyFac = 1f - (float) tinyThunder / (float) Math.max(1, tinyThunderLength);
                float expo = lerp(3f, 0.1f, (float) Math.sin(tinyFac * Math.PI));
                b = (float) Math.pow(rng.nextFloat(), expo) * 0.4f;
            }

            intensity = Math.max(a, b);
        }

        float lightIntensity(float timeStacker) {
            float num = lerp(lastIntensity, intensity, clamp01(timeStacker));
            if (rng.nextFloat() < (1f / 3f)) {
                float target = (rng.nextFloat() < 0.5f) ? 1f : 0f;
                num = lerp(num, target, rng.nextFloat() * num);
            }
            return sCurve(num, 0.5f);
        }

        private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
        private static float clamp01(float x) { return Math.max(0f, Math.min(1f, x)); }

        /** Direct port of RWCustom.Custom.SCurve. */
        private static float sCurve(float x, float k) {
            x = x * 2.0f - 1.0f;
            if (x < 0.0f) {
                x = Math.abs(1.0f + x);
                return k * x / (k - x + 1.0f) * 0.5f;
            }
            k = -1.0f - k;
            return 0.5f + k * x / (k - x + 1.0f) * 0.5f;
        }

        /** UnityEngine.Random.Range(int, int): inclusive min, exclusive max. */
        private int randomRange(int minInclusive, int maxExclusive) {
            if (maxExclusive <= minInclusive) {
                return minInclusive;
            }
            return minInclusive + rng.nextInt(maxExclusive - minInclusive);
        }
    }

    /* ----------------------------------------------------------------------
       Render helpers
       ---------------------------------------------------------------------- */
    private static void bindStructurePalette(Vector3f atmosphere, Vector3f multiplyColor) {
        ShaderProgram program = AtcCloudShaders.STRUCTURE_PROGRAM;
        if (program == null) {
            return;
        }
        program.bind();
        setUniform3f(program.getUniform("uAtmosphereColor"),
                atmosphere.x, atmosphere.y, atmosphere.z);
        setUniform3f(program.getUniform("uMultiplyColor"),
                multiplyColor.x, multiplyColor.y, multiplyColor.z);
    }

    /** Direct port of DistantBuilding.DrawSprites' red-channel shader input. */
    private static float csharpAtmosphereDepth(float worldDistance, Identifier texture) {
        float depth = worldDistance / CSHARP_DEPTH_WORLD_SCALE;
        float adjustedDepth = depth + csharpAtmosphereDepthAdd(texture);
        float normalizedDepth = MathHelper.clamp(
                adjustedDepth / CSHARP_ATMOSPHERE_END_DEPTH,
                0.0f,
                1.0f
        );
        return (float) Math.pow(normalizedDepth, 0.3f) * 0.9f;
    }

    /** atmosphericalDepthAdd values assigned in AboveCloudsView.cs. */
    private static float csharpAtmosphereDepthAdd(Identifier texture) {
        String path = texture.getPath();
        if (path.endsWith("atc_structure1.png")) return -20.0f;
        if (path.endsWith("atc_structure2.png")) return 0.0f;
        if (path.endsWith("atc_structure3.png")) return -100.0f;
        if (path.endsWith("atc_structure4.png")) return -200.0f;
        if (path.endsWith("atc_structure5.png")) return -350.0f;
        if (path.endsWith("atc_structure6.png")) return -350.0f;
        if (path.endsWith("atc_spire1.png")) return -60.0f;
        if (path.endsWith("atc_spire2.png")) return 10.0f;
        if (path.endsWith("atc_spire3.png")) return 0.0f;
        if (path.endsWith("atc_spire4.png")) return 80.0f;
        if (path.endsWith("atc_spire5.png")) return -100.0f;
        if (path.endsWith("atc_spire6.png")) return 0.0f;
        if (path.endsWith("atc_spire7.png")) return -85.0f;
        if (path.endsWith("atc_spire8.png")) return -50.0f;
        if (path.endsWith("atc_spire9.png")) return -50.0f;
        if (path.endsWith("atc_fivepebbles.png")) return -100.0f;
        return 0.0f;
    }

    private static int colorByte(float value) {
        return MathHelper.clamp(Math.round(value * 255.0f), 0, 255);
    }

    private static void setUniform3f(GlUniform uniform, float x, float y, float z) {
        if (uniform != null) {
            uniform.set(x, y, z);
        }
    }

    private static void renderQuad(VertexConsumer vc, MatrixStack matrices, float width, float height,
                                   int light, int r, int g, int b, int a) {
        renderQuadAbove(vc, matrices, width, height, 0.0f, light, r, g, b, a, 0.0f, 0.0f);
    }

    private static void renderQuadAbove(VertexConsumer vc, MatrixStack matrices, float width, float height,
                                        float cutY, int light, int r, int g, int b, int a,
                                        float edgePhase, float fadeHeight) {
        if (cutY >= 1.0f) {
            return;
        }

        MatrixStack.Entry me = matrices.peek();
        Matrix4f model = me.getPositionMatrix();

        float halfW = width * 0.5f;
        if (fadeHeight <= 0.0001f || cutY <= 0.0f || !ensureCloudEdgeImage()) {
            float bottom = MathHelper.clamp(cutY, 0.0f, 1.0f) * height;
            float bottomV = 1.0f - bottom / Math.max(height, 0.0001f);

            vc.vertex(model, -halfW, height, 0).color(r, g, b, a).texture(0f, 0f).light(light);
            vc.vertex(model,  halfW, height, 0).color(r, g, b, a).texture(1f, 0f).light(light);
            vc.vertex(model,  halfW, bottom,          0).color(r, g, b, a).texture(1f, bottomV).light(light);
            vc.vertex(model, -halfW, bottom,          0).color(r, g, b, a).texture(0f, bottomV).light(light);
            return;
        }

        float baseBottom = MathHelper.clamp(cutY, 0.0f, 1.0f) * height;
        float fadeTop = MathHelper.clamp(baseBottom + fadeHeight * height, baseBottom, height);
        float fadeTopV = 1.0f - fadeTop / Math.max(height, 0.0001f);
        int topAlpha = a;

        for (int i = 0; i < CLOUD_EDGE_SEGMENTS; i++) {
            float u0 = i / (float) CLOUD_EDGE_SEGMENTS;
            float u1 = (i + 1) / (float) CLOUD_EDGE_SEGMENTS;
            float x0 = MathHelper.lerp(u0, -halfW, halfW);
            float x1 = MathHelper.lerp(u1, -halfW, halfW);
            float cloud0 = sampleCloudEdge(u0 + edgePhase);
            float cloud1 = sampleCloudEdge(u1 + edgePhase);
            int bottomA0 = MathHelper.clamp((int) (a * MathHelper.lerp(smoothstep(0.18f, 0.92f, cloud0), 0.10f, 0.42f)), 0, 255);
            int bottomA1 = MathHelper.clamp((int) (a * MathHelper.lerp(smoothstep(0.18f, 0.92f, cloud1), 0.10f, 0.42f)), 0, 255);

            if (fadeTop < height - 0.0001f) {
                vc.vertex(model, x0, height, 0).color(r, g, b, topAlpha).texture(u0, 0f).light(light);
                vc.vertex(model, x1, height, 0).color(r, g, b, topAlpha).texture(u1, 0f).light(light);
                vc.vertex(model, x1, fadeTop, 0).color(r, g, b, topAlpha).texture(u1, fadeTopV).light(light);
                vc.vertex(model, x0, fadeTop, 0).color(r, g, b, topAlpha).texture(u0, fadeTopV).light(light);
            }

            float bottomV = 1.0f - baseBottom / Math.max(height, 0.0001f);
            vc.vertex(model, x0, fadeTop,   0).color(r, g, b, topAlpha).texture(u0, fadeTopV).light(light);
            vc.vertex(model, x1, fadeTop,   0).color(r, g, b, topAlpha).texture(u1, fadeTopV).light(light);
            vc.vertex(model, x1, baseBottom, 0).color(r, g, b, bottomA1).texture(u1, bottomV).light(light);
            vc.vertex(model, x0, baseBottom, 0).color(r, g, b, bottomA0).texture(u0, bottomV).light(light);
        }
    }

    private static RenderLayer baseLayer(Identifier texture) {
        return RenderLayer.of(
                "karma_gate_billboard_base",
                VertexFormats.POSITION_COLOR_TEXTURE_LIGHT,
                VertexFormat.DrawMode.QUADS,
                1536,
                false,
                true,
                RenderLayer.MultiPhaseParameters.builder()
                        .program(AtcCloudShaders.structurePhase())
                        .texture(new Texture(texture, false, true))
                        .transparency(TRANSLUCENT_TRANSPARENCY)
                        .cull(DISABLE_CULLING)
                        .lightmap(ENABLE_LIGHTMAP)
                        .depthTest(LEQUAL_DEPTH_TEST)
                        .writeMaskState(ALL_MASK)
                        .build(false)
        );
    }

    private static RenderLayer glowLayer(Identifier texture) {
    return RenderLayer.of(
            "karma_gate_billboard_glow",
            VertexFormats.POSITION_COLOR_TEXTURE_LIGHT,
            VertexFormat.DrawMode.QUADS,
            1024,
            false,
            true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(POSITION_COLOR_TEXTURE_LIGHTMAP_PROGRAM)
                    // DistantLightning loads its illustrations with
                    // crispPixels=true and no mipmapped filtering.
                    .texture(new Texture(texture, false, false))
                    .transparency(TRANSLUCENT_TRANSPARENCY)
                    .cull(DISABLE_CULLING)
                    .lightmap(ENABLE_LIGHTMAP)
                    // ✅ Respect world depth (so it doesn’t show through terrain)
                    .depthTest(LEQUAL_DEPTH_TEST)
                    // ✅ Don’t write to depth (so clouds & sky remain behind)
                    .writeMaskState(COLOR_MASK)
                    .build(false)
    );
}


    /* ----------------------------------------------------------------------
       Config loading
       ---------------------------------------------------------------------- */
    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;

        try {
            ResourceManager rm = MinecraftClient.getInstance().getResourceManager();
            var opt = rm.getResource(CONFIG_ID);
            if (opt.isPresent()) {
                Resource res = opt.get();
                try (var in = res.getInputStream(); var reader = new InputStreamReader(in)) {
                    JsonArray arr = JsonParser.parseReader(reader).getAsJsonArray();
                    for (JsonElement el : arr) {
                        if (!el.isJsonObject()) continue;
                        JsonObject o = el.getAsJsonObject();
                        Identifier tex = Identifier.of(o.get("texture").getAsString());
                        double x = o.get("x").getAsDouble();
                        double y = o.get("y").getAsDouble();
                        double z = o.get("z").getAsDouble();
                        float w = o.has("width") ? o.get("width").getAsFloat() : 64f;
                        float h = o.has("height") ? o.get("height").getAsFloat() : 64f;
                        boolean always = o.has("alwaysVisible") && o.get("alwaysVisible").getAsBoolean();
                        ENTRIES.add(new Entry(tex, x, y, z, w, h, false, always));
                    }
                }
            } else {
                autoGenerateEntries(ENTRIES);
            }
        } catch (Exception ignored) {}

        // Optional debug: one at world origin
        ENTRIES.add(0, new Entry(
                Identifier.of("karma-gate-mod", "structures/atc_spire1.png"),
                0.0, 0.0, 0.0,
                32f, 48f,
                false,
                true
        ));
    }

    private static void autoGenerateEntries(List<Entry> list) {
        String[] names = {
                "atc_spire1.png","atc_spire2.png","atc_spire3.png","atc_spire4.png","atc_spire5.png","atc_spire6.png","atc_spire7.png","atc_spire8.png","atc_spire9.png",
                "atc_structure1.png","atc_structure2.png","atc_structure3.png","atc_structure4.png","atc_structure5.png","atc_structure6.png", "benjamin.png", "atc_fivepebbles.png"
        };
        double radius = 3000.0, y = 160.0;
        for (int i = 0; i < names.length; i++) {
            double ang = (Math.PI * 2.0) * i / names.length;
            double x = Math.sin(ang) * radius;
            double z = Math.cos(ang) * radius;
            Identifier tex = Identifier.of("karma-gate-mod", "structures/" + names[i]);
            list.add(new Entry(tex, x, y, z, 96f, 128f, false, true));
        }
    }

    /* ----------------------------------------------------------------------
       small math helpers
       ---------------------------------------------------------------------- */
    private static float edgePhase(long ticks, float tickDelta, Entry e) {
        long wrappedTicks = Math.floorMod(ticks, CLOUD_EDGE_TIME_WRAP_TICKS);
        float time = (wrappedTicks + tickDelta) * 0.0016f;
        float position = (float) (e.x * 0.000021 + e.z * 0.000037);
        return time + position;
    }

    private static float cloudEdgeFadeHeight(float spriteHeightBlocks) {
        return MathHelper.clamp(120.0f / Math.max(spriteHeightBlocks, 1.0f), 0.045f, 0.15f);
    }

    private static boolean ensureCloudEdgeImage() {
        if (cloudEdgeImage != null && cloudEdgeW > 0 && cloudEdgeH > 0) {
            return true;
        }
        if (triedCloudEdgeLoad) {
            return false;
        }
        triedCloudEdgeLoad = true;
        try {
            ResourceManager rm = MinecraftClient.getInstance().getResourceManager();
            var opt = rm.getResource(CLOUD_EDGE);
            if (opt.isEmpty()) {
                return false;
            }
            Resource res = opt.get();
            try (InputStream in = res.getInputStream()) {
                cloudEdgeImage = NativeImage.read(in);
                cloudEdgeW = cloudEdgeImage.getWidth();
                cloudEdgeH = cloudEdgeImage.getHeight();
                return cloudEdgeW > 0 && cloudEdgeH > 0;
            }
        } catch (Throwable ignored) {
            cloudEdgeImage = null;
            cloudEdgeW = 0;
            cloudEdgeH = 0;
            return false;
        }
    }

    private static float sampleCloudEdge(float u) {
        if (cloudEdgeImage == null || cloudEdgeW <= 0 || cloudEdgeH <= 0) {
            return 0.5f;
        }
        float wrappedU = u - (float) Math.floor(u);
        int x0 = Math.floorMod((int) Math.floor(wrappedU * cloudEdgeW), cloudEdgeW);
        int x1 = Math.floorMod(x0 + 9, cloudEdgeW);
        int y0 = MathHelper.clamp((int) (cloudEdgeH * 0.42f), 0, cloudEdgeH - 1);
        int y1 = MathHelper.clamp((int) (cloudEdgeH * 0.62f), 0, cloudEdgeH - 1);
        int y2 = MathHelper.clamp((int) (cloudEdgeH * 0.78f), 0, cloudEdgeH - 1);
        float a = sampleCloudEdgePixel(x0, y0);
        float b = sampleCloudEdgePixel(x1, y1);
        float c = sampleCloudEdgePixel(x0, y2);
        return MathHelper.clamp(a * 0.52f + b * 0.32f + c * 0.16f, 0.0f, 1.0f);
    }

    private static float sampleCloudEdgePixel(int x, int y) {
        int argb = cloudEdgeImage.getColor(x, y);
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        float luma = (r * 0.2126f + g * 0.7152f + b * 0.0722f) / 255.0f;
        return luma * (a / 255.0f);
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = MathHelper.clamp((x - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

}

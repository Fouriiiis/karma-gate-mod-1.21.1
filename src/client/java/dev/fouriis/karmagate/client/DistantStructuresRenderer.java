// dev/fouriis/karmagate/client/DistantStructuresRenderer.java
package dev.fouriis.karmagate.client;

import com.google.gson.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.render.RenderPhase.Texture;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;
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

    // Per-entry lightning state (RW-like)
    private static final Map<Entry, Lightning> LIGHTNING = new ConcurrentHashMap<>();

    // Cache source image sizes to preserve overlay/base size ratio
    private static final Map<Identifier, int[]> TEX_SIZE = new ConcurrentHashMap<>();

    private static final float GLOW_Z_PUSH = -0.0025f; // render just behind the base
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

        // --- Height-based visibility (camera Y) ---
        float camY = (float) camera.getPos().y;
        float heightVis = AtcCloudVolumeRenderer.aboveCloudsVisibility(camY);
        if (heightVis <= 0.001f) return; // entirely hidden
        AtcSkyRenderer.CloudPalette cloudPalette = AtcSkyRenderer.cloudPalette(tickDelta);
        Vector3f atmosphere = cloudPalette.atmosphere();
        Vector3f textureGrade = AtcSkyRenderer.authoredTextureMultiply(tickDelta);

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

        // Ambient grayscale (day/night brightness) without hue tint
        float ambient01 = 1f;
        int cr = 255, cg = 255, cb = 255;
        if (mc.world != null) {
            Vec3d sky = mc.world.getSkyColor(camPos, tickDelta);
            float r = MathHelper.clamp((float) sky.x, 0f, 1f);
            float g = MathHelper.clamp((float) sky.y, 0f, 1f);
            float b = MathHelper.clamp((float) sky.z, 0f, 1f);
            float luma = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            float minNight = 0.12f;
            float scale    = 0.95f;
            float ambient  = MathHelper.clamp(minNight + scale * luma, minNight, 1.0f);
            ambient01 = ambient;
            int gray = (int) (ambient * 255f);
            cr = gray; cg = gray; cb = gray;
        }

        // Night factor: 0 (day) -> 1 (night)
        float nightFactor = smoothstep(0.65f, 0.15f, ambient01);

        // +50% daytime boost to lightning; fades at night
        float baseMult = 0.25f + 1.25f * nightFactor;
        float dayBoostAdd = 0.125f * (1.0f - nightFactor);
        float globalGlowMultiplier = MathHelper.clamp(baseMult + dayBoostAdd, 0.25f, 1.5f);

        // Back-to-front sort for translucency
        List<Entry> sorted = new ArrayList<>(ENTRIES);
        sorted.sort((a, b) -> Double.compare(
                camPos.squaredDistanceTo(b.x, b.y, b.z),
                camPos.squaredDistanceTo(a.x, a.y, a.z)
        ));

        // tick timeline (precision-safe)
        long nowTicks = 0L;
        if (mc.world != null) {
            long base = mc.world.getTime();
            int frac = MathHelper.floor(tickDelta * 20.0f + 0.5f);
            nowTicks = base + frac;
        }

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
            float structureFog = smoothstep(5_000.0f, 42_000.0f, dist);
            float colorFog = structureFog * 0.68f;
            int sr = lerpByte(cr, MathHelper.clamp((int) (atmosphere.x * 255.0f) + 36, 0, 255), colorFog);
            int sg = lerpByte(cg, MathHelper.clamp((int) (atmosphere.y * 255.0f) + 34, 0, 255), colorFog);
            int sb = lerpByte(cb, MathHelper.clamp((int) (atmosphere.z * 255.0f) + 30, 0, 255), colorFog);
            sr = MathHelper.clamp(Math.round(sr * textureGrade.x), 0, 255);
            sg = MathHelper.clamp(Math.round(sg * textureGrade.y), 0, 255);
            sb = MathHelper.clamp(Math.round(sb * textureGrade.z), 0, 255);
            float alphaFog = MathHelper.lerp(structureFog, 0.92f, 0.48f);

            matrices.push();
            matrices.translate((float) place.x, (float) place.y, (float) place.z);
            matrices.multiply(RotationAxis.POSITIVE_Y.rotation(yawRad));

            float cloudEdgePhase = edgePhase(nowTicks, tickDelta, e);
            if (renderBase) {
                // ---- Base, non-emissive pass ----
                matrices.push();
                matrices.scale(e.width, e.height, 1f);
                VertexConsumer vcBase = immediate.getBuffer(baseLayer(e.texture()));
                int packedLight;
                var world = mc.world;
                if (world != null) {
                    int bx = MathHelper.floor(place.x);
                    int by = MathHelper.floor(place.y);
                    int bz = MathHelper.floor(place.z);
                    int block = world.getLightLevel(LightType.BLOCK, new BlockPos(bx, by, bz));
                    int sky   = world.getLightLevel(LightType.SKY,   new BlockPos(bx, by, bz));
                    packedLight = LightmapTextureManager.pack(block, sky);
                } else {
                    packedLight = LightmapTextureManager.pack(0, 0);
                }
                int baseA = MathHelper.clamp((int)(255f * heightVis * alphaFog), 0, 255);
                float cutY = (AtcCloudVolumeRenderer.distantStructureCloudCutY() - (float) place.y) / e.height;
                float cloudFadeHeight = cloudEdgeFadeHeight(e.height);
                renderQuadAbove(vcBase, matrices, 1f, 1f, cutY, packedLight, sr, sg, sb, baseA, cloudEdgePhase, cloudFadeHeight);
                matrices.pop(); // base scale
            }

            // ---- Emissive lightning overlay ----
            Identifier lightTex = overlayFor(e.texture());
            if (renderGlow && lightTex != null && mc.world != null) {
                Lightning L = LIGHTNING.computeIfAbsent(e, DistantStructuresRenderer::makeLightning);
                L.updateTo(nowTicks);

                // fold the height fade into glow as well
                L.globalMultiplier = globalGlowMultiplier * heightVis;

                float alpha = L.lightIntensity(tickDelta) * 0.50f; // keep clear visibility
                if (alpha > 0.003f) {
                    float[] ratio = overlayScaleRatio(e.texture(), lightTex);
                    float sx = ratio[0];
                    float sy = ratio[1];

                    int ia = MathHelper.clamp((int)(alpha * 255f * MathHelper.lerp(structureFog, 1.0f, 0.55f)), 0, 255);
                    int fullbright = LightmapTextureManager.pack(15, 15);

                    matrices.push();
                    float yOffset = -(e.height * (sy - 1f) * 0.5f);
                    matrices.translate(0f, yOffset, +GLOW_Z_PUSH);
                    matrices.scale(e.width * sx, e.height * sy, 1f);

                    VertexConsumer vcGlow = immediate.getBuffer(glowLayer(lightTex));
                    renderQuad(vcGlow, matrices, 1f, 1f, fullbright, 255, 255, 255, ia);
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

        boolean nonPositionBasedIntensity = true;
        float intensityMultiplier = 1f;
        float globalMultiplier    = 1f;

        long lastTickAdvanced = Long.MIN_VALUE;

        Lightning(long seed) {
            this.rng = new Random(seed);
            this.tinyThunderWait = 5;
            resetBurst();
            this.thunder = 0;
            this.tinyThunder = 0;
            this.tinyThunderLength = 0;
            this.randomLevel = rng.nextFloat();
            this.randomLevelChange = 1 + rng.nextInt(5);
            this.lastIntensity = 0f;
            this.intensity = 0f;
            this.lastTickAdvanced = Long.MIN_VALUE;
        }

        private void resetBurst() {
            this.wait = lerpInt(10, 440, rng.nextFloat());
            this.power = lerp(0.7f, 1.0f, rng.nextFloat());
            this.thunderLength = 1 + rng.nextInt(Math.max(1, (int) lerp(10f, 32f, power)));
        }

        void updateTo(long nowTick) {
            if (lastTickAdvanced == Long.MIN_VALUE) {
                lastTickAdvanced = nowTick;
                return;
            }
            if (nowTick <= lastTickAdvanced) return;

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
                    tinyThunderWait = 10 + rng.nextInt(71);
                    tinyThunderLength = 5 + rng.nextInt(tinyThunderWait - 4);
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
                b = (float) Math.pow(rng.nextFloat(), expo) * 0.7f;
            }

            intensity = Math.max(a, b);
        }

        float lightIntensity(float timeStacker) {
            float num = lerp(lastIntensity, intensity, clamp01(timeStacker));
            if (rng.nextFloat() < (1f / 3f)) {
                float target = (rng.nextFloat() < 0.5f) ? 1f : 0f;
                num = lerp(num, target, rng.nextFloat() * num);
            }
            float shaped = sCurve(num);
            shaped = (float)Math.pow(shaped, 0.7f);
            return shaped * intensityMultiplier * globalMultiplier;
        }

        private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
        private static int lerpInt(int a, int b, float t) { return Math.round(a + (b - a) * t); }
        private static float clamp01(float x) { return Math.max(0f, Math.min(1f, x)); }
        private static float sCurve(float x) {
            x = clamp01(x);
            return x * x * (3f - 2f * x);
        }
    }

    /* ----------------------------------------------------------------------
       Render helpers
       ---------------------------------------------------------------------- */
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
                    .texture(new Texture(texture, false, true))
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

    private static int lerpByte(int from, int to, float t) {
        return MathHelper.clamp((int) MathHelper.lerp(MathHelper.clamp(t, 0.0f, 1.0f), from, to), 0, 255);
    }
}

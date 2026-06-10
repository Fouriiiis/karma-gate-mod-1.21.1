package dev.fouriis.karmagate.client.gridproject;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import dev.fouriis.karmagate.mixin.client.GameRendererAccessor;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.*;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL20;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class GridProjectRenderer {
    private GridProjectRenderer() {}
    
    // Maximum circles that can be uploaded to shader
    private static final int MAX_CIRCLES = ProjectedCirclePatternManager.MAX_CIRCLES;
    private static final int MAX_STAR_DOTS = StarMatrixPatternManager.MAX_STAR_DOTS;
    private static final int MAX_STAR_LINES = StarMatrixPatternManager.MAX_STAR_LINES;
    private static final int MAX_STAR_RINGS = StarMatrixPatternManager.MAX_STAR_RINGS;

    private static final int MAX_QUADS = 1_000_000;
    private static final float SURFACE_NUDGE = 0.00125f;

    private static final Identifier GLYPHS_TEXTURE =
        Identifier.of("karma-gate-mod", "textures/projector/glyphs.png");

    // Cache: zone -> VBO and stats
    private static final Map<ProjectionZone, VertexBuffer> ZONE_VBOS = new IdentityHashMap<>();
    private static final Map<ProjectionZone, Integer> ZONE_QUADS = new IdentityHashMap<>();
    private static final Map<ProjectionZone, Long> ZONE_NEXT_BUILD_ATTEMPT_TICK = new IdentityHashMap<>();

    private static boolean testZonesInitialized = false;

    private static final BlockPos.Mutable POS = new BlockPos.Mutable();
    private static final BlockPos.Mutable NEIGHBOR_POS = new BlockPos.Mutable();

    /** Call this if you ever recreate zones or change settings that affect geometry. */
    public static void invalidateMeshes() {
        for (VertexBuffer vb : ZONE_VBOS.values()) {
            if (vb == null) continue;
            try { vb.close(); } catch (Exception ignored) {}
        }
        ZONE_VBOS.clear();
        ZONE_QUADS.clear();
        ZONE_NEXT_BUILD_ATTEMPT_TICK.clear();
    }

    /**
     * If you cache a mesh before all chunks are loaded, you permanently "bake in" holes.
     * This prevents partial builds by requiring FULL chunks for all chunk coords in the zone AABB.
     */
    private static boolean areZoneChunksLoaded(World world, ProjectionZone zone) {
        int minChunkX = zone.getMinX() >> 4;
        int maxChunkX = zone.getMaxX() >> 4;
        int minChunkZ = zone.getMinZ() >> 4;
        int maxChunkZ = zone.getMaxZ() >> 4;

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                // IMPORTANT: create=false so we don't force-load
                if (world.getChunk(cx, cz, ChunkStatus.FULL, false) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void ensureZoneMeshBuilt(World world, ProjectionZone zone) {
        if (ZONE_VBOS.containsKey(zone)) return;

        Long nextAttempt = ZONE_NEXT_BUILD_ATTEMPT_TICK.get(zone);
        long nowTick = world.getTime();
        if (nextAttempt != null && nowTick < nextAttempt) return;

        // ✅ Prevent partial meshes (holes) by waiting until all chunks are loaded
        if (!areZoneChunksLoaded(world, zone)) {
            ZONE_NEXT_BUILD_ATTEMPT_TICK.put(zone, nowTick + 10L); // retry soon (~0.5s)
            return;
        }

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer =
            tessellator.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR_TEXTURE_LIGHT);

        final float zoneCenterX = zone.getCenterXf();
        final float zoneCenterZ = zone.getCenterZf();
        final float zoneRadius  = zone.getRadiusf();

        final int a = 255;
        final int c = 255;
        final int fullBright = LightmapTextureManager.pack(15, 15);

        int quads = 0;
        int[][] shellSlabs = zone.getShellSlabs();

        outer:
        for (int[] slab : shellSlabs) {
            int slabMinX = slab[0], slabMaxX = slab[1];
            int slabMinY = slab[2], slabMaxY = slab[3];
            int slabMinZ = slab[4], slabMaxZ = slab[5];

            for (int x = slabMinX; x <= slabMaxX; x++) {
                for (int z = slabMinZ; z <= slabMaxZ; z++) {
                    for (int y = slabMinY; y <= slabMaxY; y++) {
                        POS.set(x, y, z);

                        BlockState state = world.getBlockState(POS);
                        if (state.isAir()) continue;
                        if (!state.isOpaqueFullCube(world, POS)) continue;

                        final float fcx = x + 0.5f;
                        final float fcz = z + 0.5f;
                        final float toCenterX = zoneCenterX - fcx;
                        final float toCenterZ = zoneCenterZ - fcz;

                        boolean showUp    = shouldRenderFace(world, POS, NEIGHBOR_POS, Direction.UP);
                        boolean showDown  = shouldRenderFace(world, POS, NEIGHBOR_POS, Direction.DOWN);
                        boolean showNorth = toCenterZ < 0 && shouldRenderFace(world, POS, NEIGHBOR_POS, Direction.NORTH);
                        boolean showSouth = toCenterZ > 0 && shouldRenderFace(world, POS, NEIGHBOR_POS, Direction.SOUTH);
                        boolean showWest  = toCenterX < 0 && shouldRenderFace(world, POS, NEIGHBOR_POS, Direction.WEST);
                        boolean showEast  = toCenterX > 0 && shouldRenderFace(world, POS, NEIGHBOR_POS, Direction.EAST);

                        if (!(showUp || showDown || showNorth || showSouth || showWest || showEast)) continue;

                        float x0 = x;
                        float y0 = y;
                        float z0 = z;
                        float x1 = x + 1.0f;
                        float y1 = y + 1.0f;
                        float z1 = z + 1.0f;

                        if (showUp) {
                            emitQuadWorld(buffer,
                                x0, y1 + SURFACE_NUDGE, z0,
                                x1, y1 + SURFACE_NUDGE, z0,
                                x1, y1 + SURFACE_NUDGE, z1,
                                x0, y1 + SURFACE_NUDGE, z1,
                                c, c, c, a, fullBright,
                                zoneCenterX, zoneCenterZ, zoneRadius
                            );
                            quads++;
                        }

                        if (showDown) {
                            emitQuadWorld(buffer,
                                x0, y0 - SURFACE_NUDGE, z1,
                                x1, y0 - SURFACE_NUDGE, z1,
                                x1, y0 - SURFACE_NUDGE, z0,
                                x0, y0 - SURFACE_NUDGE, z0,
                                c, c, c, a, fullBright,
                                zoneCenterX, zoneCenterZ, zoneRadius
                            );
                            quads++;
                        }

                        if (showNorth) {
                            emitQuadWorld(buffer,
                                x1, y0, z0 - SURFACE_NUDGE,
                                x0, y0, z0 - SURFACE_NUDGE,
                                x0, y1, z0 - SURFACE_NUDGE,
                                x1, y1, z0 - SURFACE_NUDGE,
                                c, c, c, a, fullBright,
                                zoneCenterX, zoneCenterZ, zoneRadius
                            );
                            quads++;
                        }

                        if (showSouth) {
                            emitQuadWorld(buffer,
                                x0, y0, z1 + SURFACE_NUDGE,
                                x1, y0, z1 + SURFACE_NUDGE,
                                x1, y1, z1 + SURFACE_NUDGE,
                                x0, y1, z1 + SURFACE_NUDGE,
                                c, c, c, a, fullBright,
                                zoneCenterX, zoneCenterZ, zoneRadius
                            );
                            quads++;
                        }

                        if (showWest) {
                            emitQuadWorld(buffer,
                                x0 - SURFACE_NUDGE, y0, z0,
                                x0 - SURFACE_NUDGE, y0, z1,
                                x0 - SURFACE_NUDGE, y1, z1,
                                x0 - SURFACE_NUDGE, y1, z0,
                                c, c, c, a, fullBright,
                                zoneCenterX, zoneCenterZ, zoneRadius
                            );
                            quads++;
                        }

                        if (showEast) {
                            emitQuadWorld(buffer,
                                x1 + SURFACE_NUDGE, y0, z1,
                                x1 + SURFACE_NUDGE, y0, z0,
                                x1 + SURFACE_NUDGE, y1, z0,
                                x1 + SURFACE_NUDGE, y1, z1,
                                c, c, c, a, fullBright,
                                zoneCenterX, zoneCenterZ, zoneRadius
                            );
                            quads++;
                        }

                        if (quads >= MAX_QUADS) break outer;
                    }
                }
            }
        }

        if (quads <= 0) {
            // If everything is loaded but we found nothing, just retry a bit later.
            ZONE_NEXT_BUILD_ATTEMPT_TICK.put(zone, nowTick + 20L);
            return;
        }

        BuiltBuffer built = buffer.end();

        VertexBuffer vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
        BufferRenderer.resetCurrentVertexBuffer();

        vb.bind();
        vb.upload(built);
        VertexBuffer.unbind();

        try { built.close(); } catch (Exception ignored) {}

        BufferRenderer.resetCurrentVertexBuffer();

        ZONE_VBOS.put(zone, vb);
        ZONE_QUADS.put(zone, quads);
        ZONE_NEXT_BUILD_ATTEMPT_TICK.remove(zone);
    }

    public static void renderLate(float tickDelta, Camera camera) {
        if (GridProjectShaders.PROGRAM == null) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        World world = mc.world;
        if (world == null || camera == null) return;

        if (!testZonesInitialized) {
            invalidateMeshes();
            
            testZonesInitialized = true;
        }

        List<ProjectionZone> zones = ProjectionZone.getZones();

        if (mc.getWindow() == null
            || mc.getWindow().getFramebufferWidth() <= 0
            || mc.getWindow().getFramebufferHeight() <= 0) return;

        Vec3d camPos = camera.getPos();
        ShaderProgram program = GridProjectShaders.PROGRAM;

        setUniform1f(program, "uTime", (float) (world.getTime() + tickDelta));

        Matrix4f view = new Matrix4f()
            .rotation(camera.getRotation())
            .transpose()
            .translate((float) -camPos.x, (float) -camPos.y, (float) -camPos.z);
        setUniformMat4(program, "uViewMat", view);

        double dynFovDeg = ((GameRendererAccessor) mc.gameRenderer)
            .karmaGate$invokeGetFov(camera, tickDelta, true);
        float fovRad = (float) Math.toRadians(dynFovDeg);
        float aspect = (float) mc.getWindow().getFramebufferWidth() / Math.max(1, mc.getWindow().getFramebufferHeight());
        float near = 0.05f;
        float far = (float) (mc.options.getClampedViewDistance() * 16.0 * 4.0);
        Matrix4f customProj = new Matrix4f().setPerspective(fovRad, aspect, near, far);

        Matrix4f savedProj = new Matrix4f(RenderSystem.getProjectionMatrix());

        RenderSystem.setShader(() -> program);
        RenderSystem.setShaderTexture(0, GLYPHS_TEXTURE);
        RenderSystem.setProjectionMatrix(customProj, VertexSorter.BY_DISTANCE);

        RenderSystem.getModelViewStack().pushMatrix();
        RenderSystem.getModelViewStack().identity();
        RenderSystem.applyModelViewMatrix();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.enableDepthTest();

        Matrix4f identityModelView = new Matrix4f(RenderSystem.getModelViewMatrix());

        try {
            BufferRenderer.resetCurrentVertexBuffer();

            for (ProjectionZone zone : zones) {
                ensureZoneMeshBuilt(world, zone);

                VertexBuffer vb = ZONE_VBOS.get(zone);
                if (vb == null) continue;

                // Bind the shader program first so uniforms can be set
                program.bind();
                
                setUniform1f(program, "uZoneCenterX", zone.getCenterXf());
                setUniform1f(program, "uZoneCenterZ", zone.getCenterZf());
                setUniform1f(program, "uZoneRadius",  zone.getRadiusf());

                // Zone bounds as world-space edges (max is exclusive: maxBlock + 1)
                setUniform1f(program, "uZoneMinX", (float) zone.getMinX());
                setUniform1f(program, "uZoneMaxX", (float) (zone.getMaxX() + 1));
                setUniform1f(program, "uZoneMinZ", (float) zone.getMinZ());
                setUniform1f(program, "uZoneMaxZ", (float) (zone.getMaxZ() + 1));
                
                // Upload draw flags as floats (0.0 or 1.0)
                setUniform1f(program, "uDrawCircles", zone.isDrawCircles() ? 1.0f : 0.0f);
                setUniform1f(program, "uDrawGrid", zone.isDrawGrid() ? 1.0f : 0.0f);
                
                // Upload circle data for this zone (now that shader is bound)
                uploadCircleUniforms(program, zone.getName());
                uploadStarMatrixUniforms(program, zone.getName());

                BufferRenderer.resetCurrentVertexBuffer();
                vb.bind();
                vb.draw(identityModelView, customProj, program);
                VertexBuffer.unbind();
            }
        } finally {
            BufferRenderer.resetCurrentVertexBuffer();

            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
            RenderSystem.getModelViewStack().popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(savedProj, VertexSorter.BY_DISTANCE);
        }
    }

    private static boolean shouldRenderFace(World world, BlockPos.Mutable pos, BlockPos.Mutable neighborPos, Direction dir) {
        neighborPos.set(pos).move(dir);
        BlockState neighbor = world.getBlockState(neighborPos);
        if (neighbor.isAir()) return true;
        return !neighbor.isOpaqueFullCube(world, neighborPos);
    }

    private static void setUniform1f(ShaderProgram program, String name, float x) {
        if (program == null) return;
        GlUniform u = program.getUniform(name);
        if (u != null) u.set(x);
    }

    private static void setUniformMat4(ShaderProgram program, String name, Matrix4f mat) {
        if (program == null || mat == null) return;
        GlUniform u = program.getUniform(name);
        if (u != null) u.set(mat);
    }

    /**
     * Uploads circle uniform data to the shader for the given zone.
     * IMPORTANT: The shader program must be bound before calling this method.
     */
    private static void uploadCircleUniforms(ShaderProgram program, String zoneName) {
        if (program == null) return;
        
        ProjectedCirclePatternManager.PackedCircleData data = 
            ProjectedCirclePatternManager.getInstance().getPackedData(zoneName);
        
        int count = (data != null) ? data.count : 0;
        
        // Set circle count
        setUniform1i(program, "uCircleCount", count);
        
        if (count <= 0 || data == null) return;
        
        // Get the OpenGL program ID
        int programId = program.getGlRef();
        if (programId <= 0) return;
        
        // Upload circle data using direct GL calls
        // uCircles is a vec4 array in the shader
        int circlesLoc = GL20.glGetUniformLocation(programId, "uCircles");
        if (circlesLoc >= 0) {
            GL20.glUniform4fv(circlesLoc, data.circles);
        }
        
        // uCircleExtras is also a vec4 array
        int extrasLoc = GL20.glGetUniformLocation(programId, "uCircleExtras");
        if (extrasLoc >= 0) {
            GL20.glUniform4fv(extrasLoc, data.extras);
        }
    }

    private static void uploadStarMatrixUniforms(ShaderProgram program, String zoneName) {
        if (program == null) return;

        StarMatrixPatternManager.PackedStarMatrixData data =
            StarMatrixPatternManager.getInstance().getPackedData(zoneName);

        int dotCount = data != null ? data.dotCount : 0;
        int lineCount = data != null ? data.lineCount : 0;
        int ringCount = data != null ? data.ringCount : 0;

        setUniform1i(program, "uStarDotCount", dotCount);
        setUniform1i(program, "uStarLineCount", lineCount);
        setUniform1i(program, "uStarRingCount", ringCount);

        if (data == null) {
            return;
        }

        int programId = program.getGlRef();
        if (programId <= 0) return;

        if (dotCount > 0) {
            int dotsLoc = GL20.glGetUniformLocation(programId, "uStarDots");
            if (dotsLoc >= 0) {
                GL20.glUniform4fv(dotsLoc, data.dots);
            }
        }

        if (lineCount > 0) {
            int linesLoc = GL20.glGetUniformLocation(programId, "uStarLines");
            if (linesLoc >= 0) {
                GL20.glUniform4fv(linesLoc, data.lines);
            }
        }

        if (ringCount > 0) {
            int ringsLoc = GL20.glGetUniformLocation(programId, "uStarRings");
            if (ringsLoc >= 0) {
                GL20.glUniform4fv(ringsLoc, data.rings);
            }
        }
    }
    
    private static void setUniform1i(ShaderProgram program, String name, int value) {
        if (program == null) return;
        GlUniform u = program.getUniform(name);
        if (u != null) u.set(value);
    }

    private static void emitQuadWorld(
        BufferBuilder buffer,
        float x0, float y0, float z0,
        float x1, float y1, float z1,
        float x2, float y2, float z2,
        float x3, float y3, float z3,
        int r, int g, int b, int a,
        int light,
        float zoneCenterX, float zoneCenterZ,
        float zoneRadius
    ) {
        float u0 = computeSquarePerimeterU(x0, z0, zoneCenterX, zoneCenterZ, zoneRadius);
        float u1 = computeSquarePerimeterU(x1, z1, zoneCenterX, zoneCenterZ, zoneRadius);
        float u2 = computeSquarePerimeterU(x2, z2, zoneCenterX, zoneCenterZ, zoneRadius);
        float u3 = computeSquarePerimeterU(x3, z3, zoneCenterX, zoneCenterZ, zoneRadius);

        float period = 8.0f * Math.max(zoneRadius, 1e-6f);
        float[] us = {u0, u1, u2, u3};
        fixUvWrappingPerimeter(us, period);
        u0 = us[0]; u1 = us[1]; u2 = us[2]; u3 = us[3];

        buffer.vertex(x0, y0, z0)
            .color(r, g, b, a)
            .texture(u0, y0)
            .light(light);

        buffer.vertex(x1, y1, z1)
            .color(r, g, b, a)
            .texture(u1, y1)
            .light(light);

        buffer.vertex(x2, y2, z2)
            .color(r, g, b, a)
            .texture(u2, y2)
            .light(light);

        buffer.vertex(x3, y3, z3)
            .color(r, g, b, a)
            .texture(u3, y3)
            .light(light);
    }

    private static float computeSquarePerimeterU(float worldX, float worldZ,
                                                 float centerX, float centerZ,
                                                 float radius) {
        float R = Math.max(radius, 1e-6f);

        float rx = worldX - centerX;
        float rz = worldZ - centerZ;

        float len = (float) Math.sqrt(rx * rx + rz * rz);
        float dx = (len > 1e-9f) ? (rx / len) : 1.0f;
        float dz = (len > 1e-9f) ? (rz / len) : 0.0f;

        float m = Math.max(Math.abs(dx), Math.abs(dz));
        if (m < 1e-9f) m = 1.0f;

        float hx = dx * (R / m);
        float hz = dz * (R / m);

        float ax = Math.abs(hx);
        float az = Math.abs(hz);

        float u;
        if (ax >= az) {
            if (hx >= 0.0f) {
                u = hz + R;
            } else {
                u = 4.0f * R + (R - hz);
            }
        } else {
            if (hz >= 0.0f) {
                u = 2.0f * R + (R - hx);
            } else {
                u = 6.0f * R + (hx + R);
            }
        }

        float perim = 8.0f * R;
        u = u % perim;
        if (u < 0.0f) u += perim;

        return u;
    }

    private static void fixUvWrappingPerimeter(float[] us, float period) {
        if (period <= 0.0f) return;

        float uMin = us[0], uMax = us[0];
        for (int i = 1; i < 4; i++) {
            uMin = Math.min(uMin, us[i]);
            uMax = Math.max(uMax, us[i]);
        }

        if (uMax - uMin > period * 0.5f) {
            float mid = (uMin + uMax) * 0.5f;
            for (int i = 0; i < 4; i++) {
                if (us[i] < mid) us[i] += period;
            }
        }
    }
}

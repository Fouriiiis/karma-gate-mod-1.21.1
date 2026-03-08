package dev.fouriis.karmagate.entity.centipede;

import net.brickcraftdream.librainworldmc.client.LibrainworldmcClient;
import net.brickcraftdream.librainworldmc.client.atlas.FAtlasElement;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/**
 * Wing rendering for Centiwings.
 * Ports C# CentipedeGraphics wing rendering:
 * - Each body segment has one wing pair (left + right)
 * - Wings are C# CustomFSprite("CentipedeWing") with "CicadaWing" shader
 * - Wing tip position computed from body orientation + flap cycle + fold state
 * - 4-vertex custom sprite from wing tip to body attach point
 * - Vertex colors: iridescent HSL-based coloring at tips, dark body color at base
 *
 * Uses the same billboard sprite quad approach as CentipedeLegRenderer.
 */
public final class CentiwingWingRenderer {

    private static FAtlasElement wingSprite = null;

    // Scale: 1 C# pixel ≈ 0.025 MC blocks
    private static final float PX = 0.05f;

    // Wing width along the chain direction (C#: num17 = 2f pixels)
    private static final float WING_HALF_WIDTH = 2f * PX;

    // Black body color (C#: palette.blackColor ≈ very dark)
    private static final int BLACK_R = 9, BLACK_G = 7, BLACK_B = 6;

    // Debug rendering toggle
    private static final boolean debug = false;

    private CentiwingWingRenderer() {}

    /**
     * Render wings for a centiwing body segment.
     * Called from CentipedeBodyRenderer after legs are rendered, only for Centiwing parents.
     */
    public static void renderWings(CentipedeSegmentEntity entity, MatrixStack matrices,
                                    VertexConsumerProvider vcProvider, int light, float tickDelta) {
        if (wingSprite == null) {
            wingSprite = LibrainworldmcClient.getAtlasManager().getElementWithName("CentipedeWing");
            if (wingSprite == null) return;
        }

        CentipedeController parent = entity.getParentCentipede();
        if (parent == null || !parent.hasWings()) return;

        CentipedeSegmentEntity[] segs = parent.getSegments();
        if (segs == null) return;

        int idx = entity.getSegmentIndex();
        int totalSegs = segs.length;
        if (idx < 0 || idx >= totalSegs) return;

        // Interpolated segment position
        Vec3d segPos = lerpPos(entity, tickDelta);

        // Chain direction and perpendicular
        Vec3d chainDir = computeChainDirection(segs, idx, tickDelta);
        Vec3d perp = surfacePerp(chainDir, entity);

        // Body radius at this segment
        float bodyRadius = parent.computeSegmentRadius(idx) * PX;

        // Wing length at this segment
        float wingLength = parent.getWingLength(idx) * PX;
        if (wingLength < 0.01f) return;

        // Wing flap state (interpolated)
        float flapCycle = MathHelper.lerp(tickDelta, parent.getLastWingFlapCycle(), parent.getWingFlapCycle());
        float wingsFolded = MathHelper.lerp(tickDelta, parent.getLastWingsFolded(), parent.getWingsFolded());

        // C# chunkRotat: for 3D we treat it as (roll=x, pitch=y) ≈ (1, 1) upright
        // In 3D the surface normal determines orientation, but we simplify:
        // chunkRotat.x ≈ roll, chunkRotat.y ≈ pitch projection
        // For flying centiwings in 3D, we use y≈1 (upright flight)
        float chunkRotY = 1f; // pitch factor
        float chunkRotX = 0f; // roll factor

        // VerticalWingFlapAtChunk: sin((flapCycle + chunk * 1.8) * PI * 0.3) * (1 - wingsFolded)
        float verticalFlap = (float) Math.sin(
                (flapCycle + (float) idx * 1.8f) * (float) Math.PI * 0.3f)
                * (1f - wingsFolded);

        // HorizontalWingFlapAtChunk: cos((flapCycle + chunk * lerpMap(chunkRotY,-1,1,1.8,0.6)) * PI * 0.3) * (1-wingsFolded)
        float horizLerp = MathHelper.lerp((chunkRotY + 1f) / 2f, 1.8f, 0.6f);
        float horizontalFlap = (float) Math.cos(
                (flapCycle + (float) idx * horizLerp) * (float) Math.PI * 0.3f)
                * (1f - wingsFolded);

        for (int side = 0; side < 2; side++) {
            float sideSign = (side == 0) ? -1f : 1f;

            // --- Compute wing tip position (C# WingPos) ---
            float t = (totalSegs > 1) ? (float) idx / (float) (totalSegs - 1) : 0.5f;

            // C# WingPos formula simplified for 3D:
            // Wing extends perpendicular to body, with flap modulation
            // Base direction: perpendicular * sideSign (horizontal spread)
            // Vertical modulation from wingFlap: adds up/down displacement
            Vec3d wingDir = perp.multiply(sideSign * chunkRotY);

            // Add vertical flap component
            // C#: prp * pow(abs(f), 0.5) * sign(f) * abs(chunkRotX) when rotated
            // In 3D, vertical flap means up/down
            Vec3d upDir = new Vec3d(0, 1, 0);
            // Cross product to get the up direction relative to the chain
            Vec3d chainUp = perp.crossProduct(chainDir);
            if (chainUp.lengthSquared() > 0.001) {
                chainUp = chainUp.normalize();
            } else {
                chainUp = upDir;
            }

            // Mix horizontal spread with vertical flap
            Vec3d flapUp = chainUp.multiply(
                    (float) Math.pow(Math.abs(verticalFlap), 0.5f) * Math.signum(verticalFlap));

            // Horizontal modulation from chain direction
            float tLerp = MathHelper.lerp(t, -1f, 1f);
            Vec3d horizComponent = chainDir.multiply(
                    MathHelper.lerp(tLerp, horizontalFlap * chunkRotY, 0.5f * Math.abs(chunkRotY)));

            // Mix wing direction with flap
            // Normal flight (chunkRotX ≈ 0): primarily horizontal wings with vertical flap
            Vec3d wingDirFinal = wingDir.add(flapUp.multiply(0.6f)).add(horizComponent.multiply(0.3f));

            // Folded wing direction: tuck along body
            Vec3d foldedDir = chainDir.multiply(MathHelper.lerp(1f, -1f, t))
                    .add(perp.multiply(sideSign * chunkRotY)).multiply(0.5);

            // Lerp between extended and folded
            wingDirFinal = lerpVec(wingDirFinal, foldedDir, wingsFolded);

            // Normalize and scale to wing length
            if (wingDirFinal.lengthSquared() > 0.001) {
                wingDirFinal = wingDirFinal.normalize();
            } else {
                wingDirFinal = perp.multiply(sideSign);
            }

            // Attach point on body
            Vec3d attachWorld = segPos.add(perp.multiply(sideSign * bodyRadius * chunkRotY));
            Vec3d attachLocal = perp.multiply(sideSign * bodyRadius * chunkRotY);

            // Wing tip position
            Vec3d tipLocal = attachLocal.add(wingDirFinal.multiply(wingLength));

            // --- Compute iridescent vertex color (C# HSL) ---
            // C#: angle-based factor 'a' for iridescence
            // Simplified: use flap angle as the iridescence driver
            float iridFactor = Math.abs(verticalFlap) * 0.5f;
            iridFactor = (float) Math.pow(Math.max(iridFactor, 0), 0.5f);

            // Tip color: C# HSL(0.99 - 0.4 * pow(a,2), 1, 0.5 + 0.5*a, alpha = 0.5 + 0.5*a)
            float hue = 0.99f - 0.4f * iridFactor * iridFactor;
            float lightness = 0.5f + 0.5f * iridFactor;
            float alpha = 0.5f + 0.5f * iridFactor;
            int[] tipRGB = hslToRGB(hue, 1f, lightness);
            int tipAlpha = (int) (alpha * 255);

            // Base color: lerp(blackColor, white, 0.5 * a)
            int baseR = (int) MathHelper.lerp(0.5f * iridFactor, BLACK_R, 255);
            int baseG = (int) MathHelper.lerp(0.5f * iridFactor, BLACK_G, 255);
            int baseB = (int) MathHelper.lerp(0.5f * iridFactor, BLACK_B, 255);

            // Render wing quad from attach to tip
            renderWingSprite(matrices, vcProvider, light,
                    attachLocal, tipLocal, WING_HALF_WIDTH, wingSprite,
                    perp,
                    tipRGB[0], tipRGB[1], tipRGB[2], tipAlpha,
                    baseR, baseG, baseB, 255);

            // Debug: green = wing (attach → tip)
            if (debug) {
                renderDebugLine(matrices, vcProvider, attachLocal, tipLocal, 50, 255, 80);
            }
        }
    }

    // =========================================================================
    // Rendering
    // =========================================================================

    private static void renderWingSprite(MatrixStack matrices, VertexConsumerProvider vcProvider, int light,
                                          Vec3d startLocal, Vec3d endLocal, float halfWidth,
                                          FAtlasElement sprite,
                                          Vec3d perp,
                                          int topR, int topG, int topB, int topA,
                                          int botR, int botG, int botB, int botA) {
        Vec3d limbDir = endLocal.subtract(startLocal);
        double limbLen = limbDir.length();
        if (limbLen < 0.001) return;

        // Fixed-angle orientation: sprite lies in the leg plane, not camera-facing.
        Vec3d tangent = limbDir.normalize();
        Vec3d perpProj = perp.subtract(tangent.multiply(perp.dotProduct(tangent)));
        if (perpProj.lengthSquared() < 0.001) {
            perpProj = tangent.crossProduct(new Vec3d(0, 1, 0));
            if (perpProj.lengthSquared() < 0.001) perpProj = tangent.crossProduct(new Vec3d(1, 0, 0));
        }
        perpProj = perpProj.normalize();
        Vec3d widthDir = tangent.crossProduct(perpProj);
        if (widthDir.lengthSquared() < 0.001) widthDir = perpProj;
        else widthDir = widthDir.normalize();

        float nfx = (float) perpProj.x, nfy = (float) perpProj.y, nfz = (float) perpProj.z;

        float wdx = (float) (widthDir.x * halfWidth);
        float wdy = (float) (widthDir.y * halfWidth);
        float wdz = (float) (widthDir.z * halfWidth);

        float s0x = (float) startLocal.x, s0y = (float) startLocal.y, s0z = (float) startLocal.z;
        float s1x = (float) endLocal.x,   s1y = (float) endLocal.y,   s1z = (float) endLocal.z;

        // 4 corners: BL, BR, TR, TL  (start = bottom, end = top)
        float blX = s0x - wdx, blY = s0y - wdy, blZ = s0z - wdz;
        float brX = s0x + wdx, brY = s0y + wdy, brZ = s0z + wdz;
        float trX = s1x + wdx, trY = s1y + wdy, trZ = s1z + wdz;
        float tlX = s1x - wdx, tlY = s1y - wdy, tlZ = s1z - wdz;

        Matrix4f mat = matrices.peek().getPositionMatrix();
        VertexConsumer vc = vcProvider.getBuffer(
                RenderLayer.getEntityTranslucent(sprite.textureIdentifier));

        vc.vertex(mat, blX, blY, blZ).color(botR, botG, botB, botA)
                .texture(0f, 1f).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nfx, nfy, nfz);
        vc.vertex(mat, brX, brY, brZ).color(botR, botG, botB, botA)
                .texture(1f, 1f).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nfx, nfy, nfz);
        vc.vertex(mat, trX, trY, trZ).color(topR, topG, topB, topA)
                .texture(1f, 0f).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nfx, nfy, nfz);
        vc.vertex(mat, tlX, tlY, tlZ).color(topR, topG, topB, topA)
                .texture(0f, 0f).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(nfx, nfy, nfz);
    }

    // =========================================================================
    // Debug line rendering
    // =========================================================================

    private static void renderDebugLine(MatrixStack matrices, VertexConsumerProvider vcProvider,
                                         Vec3d start, Vec3d end, int r, int g, int b) {
        Matrix4f mat = matrices.peek().getPositionMatrix();
        VertexConsumer vc = vcProvider.getBuffer(RenderLayer.LINES);
        Vec3d dir = end.subtract(start);
        double len = dir.length();
        float nx = 0f, ny = 1f, nz = 0f;
        if (len > 0.001) {
            Vec3d n = dir.normalize();
            nx = (float) n.x; ny = (float) n.y; nz = (float) n.z;
        }
        vc.vertex(mat, (float) start.x, (float) start.y, (float) start.z)
                .color(r, g, b, 255).normal(nx, ny, nz);
        vc.vertex(mat, (float) end.x, (float) end.y, (float) end.z)
                .color(r, g, b, 255).normal(nx, ny, nz);
    }

    // =========================================================================
    // Utility
    // =========================================================================

    /** HSL to RGB conversion (H in 0..1, S in 0..1, L in 0..1). Returns int[3] RGB 0-255. */
    private static int[] hslToRGB(float h, float s, float l) {
        h = h - (float) Math.floor(h); // wrap to 0-1
        float c = (1f - Math.abs(2f * l - 1f)) * s;
        float x = c * (1f - Math.abs((h * 6f) % 2f - 1f));
        float m = l - c / 2f;
        float r, g, b;
        if (h < 1f / 6f) { r = c; g = x; b = 0; }
        else if (h < 2f / 6f) { r = x; g = c; b = 0; }
        else if (h < 3f / 6f) { r = 0; g = c; b = x; }
        else if (h < 4f / 6f) { r = 0; g = x; b = c; }
        else if (h < 5f / 6f) { r = x; g = 0; b = c; }
        else { r = c; g = 0; b = x; }
        return new int[]{
                MathHelper.clamp((int) ((r + m) * 255), 0, 255),
                MathHelper.clamp((int) ((g + m) * 255), 0, 255),
                MathHelper.clamp((int) ((b + m) * 255), 0, 255)
        };
    }

    /** Interpolated segment position. */
    private static Vec3d lerpPos(CentipedeSegmentEntity seg, float tickDelta) {
        return new Vec3d(
                MathHelper.lerp(tickDelta, seg.prevTickPos.x, seg.getPos().x),
                MathHelper.lerp(tickDelta, seg.prevTickPos.y, seg.getPos().y),
                MathHelper.lerp(tickDelta, seg.prevTickPos.z, seg.getPos().z));
    }

    /** Chain direction with interpolation. */
    private static Vec3d computeChainDirection(CentipedeSegmentEntity[] segs, int idx, float tickDelta) {
        if (idx < 0 || idx >= segs.length) return new Vec3d(0, 0, 1);
        Vec3d dir = Vec3d.ZERO;
        int count = 0;
        if (idx > 0 && segs[idx - 1] != null && !segs[idx - 1].isRemoved()) {
            Vec3d prev = lerpPos(segs[idx - 1], tickDelta);
            Vec3d curr = lerpPos(segs[idx], tickDelta);
            Vec3d d = prev.subtract(curr);
            if (d.lengthSquared() > 0.001) { dir = dir.add(d.normalize()); count++; }
        }
        if (idx < segs.length - 1 && segs[idx + 1] != null && !segs[idx + 1].isRemoved()) {
            Vec3d curr = lerpPos(segs[idx], tickDelta);
            Vec3d next = lerpPos(segs[idx + 1], tickDelta);
            Vec3d d = curr.subtract(next);
            if (d.lengthSquared() > 0.001) { dir = dir.add(d.normalize()); count++; }
        }
        if (count > 0 && dir.lengthSquared() > 0.001) return dir.normalize();
        return new Vec3d(0, 0, 1);
    }

    /** Perpendicular to chain direction, oriented toward the surface the segment is crawling on. */
    private static Vec3d surfacePerp(Vec3d chainDir, CentipedeSegmentEntity entity) {
        Vec3d surfaceNormal = new Vec3d(entity.surfaceNormalX, entity.surfaceNormalY, entity.surfaceNormalZ);
        if (surfaceNormal.lengthSquared() < 0.01) {
            surfaceNormal = new Vec3d(0, 1, 0);
        } else {
            surfaceNormal = surfaceNormal.normalize();
        }
        Vec3d perp = chainDir.crossProduct(surfaceNormal);
        if (perp.lengthSquared() < 0.001) {
            perp = chainDir.crossProduct(new Vec3d(0, 1, 0));
            if (perp.lengthSquared() < 0.001) {
                perp = chainDir.crossProduct(new Vec3d(1, 0, 0));
            }
        }
        return perp.normalize();
    }

    /** Lerp between two Vec3d. */
    private static Vec3d lerpVec(Vec3d a, Vec3d b, double t) {
        return new Vec3d(
                a.x + (b.x - a.x) * t,
                a.y + (b.y - a.y) * t,
                a.z + (b.z - a.z) * t);
    }
}

#version 150

uniform sampler2D Sampler0;

uniform float uGridCells;
uniform float uLineWidthPx;
uniform float uOpacity;
uniform float uEffectAmount;
uniform float uElectricPower;

uniform float uTime;
uniform float uAnimSpeed;
uniform float GameTime;

in vec4 vColor;
in vec2 vWorldUV;
in vec3 vWorldPos;

uniform float uZoneCenterX;
uniform float uZoneCenterZ;
uniform float uZoneRadius;

uniform float uZoneMinX;
uniform float uZoneMaxX;
uniform float uZoneMinZ;
uniform float uZoneMaxZ;

// Circle uniforms
const int MAX_CIRCLES = 32;
uniform int uCircleCount;
uniform vec4 uCircles[MAX_CIRCLES];      // [u, y, radius, blink] per circle
uniform vec4 uCircleExtras[MAX_CIRCLES]; // [rotationDeg, spokes, reserved, alphaScale] per circle

out vec4 fragColor;

// ---------- Hash helpers ----------
float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}
float hash11(float p) {
    float x = fract(p * 0.1031);
    x *= x + 33.33;
    x *= x + x;
    return fract(x);
}
float saturate(float x) { return clamp(x, 0.0, 1.0); }

// Deterministic unit random vector like Custom.RNV()
vec2 rnv(float s) {
    float a = hash11(s) * 6.28318530718;
    return vec2(cos(a), sin(a));
}

// Clamp magnitude like Vector2.ClampMagnitude
vec2 clampMag(vec2 v, float m) {
    float l = length(v);
    return (l > m) ? v * (m / max(l, 1e-6)) : v;
}

// Positive modulo for floats
float pmod(float x, float m) { return mod(mod(x, m) + m, m); }

// Periodic distance on a ring [0, period)
float ringDist(float a, float b, float period) {
    float d = abs(a - b);
    return min(d, period - d);
}

// Signed shortest delta on a looped axis
float shortestDelta(float from, float to, float period) {
    float d = to - from;
    return mod(d + period * 0.5, period) - period * 0.5;
}

float distToSegment(vec2 p, vec2 a, vec2 b) {
    vec2 pa = p - a;
    vec2 ba = b - a;
    float h = clamp(dot(pa, ba) / max(dot(ba, ba), 1e-6), 0.0, 1.0);
    return length(pa - ba * h);
}

// Sample a glyph from the 50-column strip atlas
float sampleGlyph(vec2 cellUV, float glyphIndex, float pad, float edgeLo, float edgeHi) {
    vec2 glyphLocal = (cellUV - vec2(pad)) / max(vec2(1.0 - 2.0 * pad), vec2(1e-6));
    float inBox = step(0.0, glyphLocal.x) * step(0.0, glyphLocal.y) *
                  step(glyphLocal.x, 1.0) * step(glyphLocal.y, 1.0);

    float u0 = glyphIndex / 50.0;
    float u1 = (glyphIndex + 1.0) / 50.0;

    vec2 atlasUV = vec2(mix(u0, u1, clamp(glyphLocal.x, 0.0, 1.0)),
                        1.0 - clamp(glyphLocal.y, 0.0, 1.0));

    float src = texture(Sampler0, atlasUV).r;
    return smoothstep(edgeHi, edgeLo, src) * inBox;
}

// AA line helper (1 at center, 0 outside)
float lineA(float dist, float halfW, float aa) {
    return 1.0 - smoothstep(halfW, halfW + aa, dist);
}

void main() {
    float tTick = (GameTime != 0.0) ? (GameTime * 24000.0) : uTime;

    float effect = saturate(uEffectAmount);
    float anim   = (uAnimSpeed <= 0.0) ? 1.0 : uAnimSpeed;

    // ============================================================
    // Zone-edge inward fade
    // ============================================================
    float dLeft  = vWorldPos.x - uZoneMinX;
    float dRight = uZoneMaxX - vWorldPos.x;
    float dNear  = vWorldPos.z - uZoneMinZ;
    float dFar   = uZoneMaxZ - vWorldPos.z;
    float inward = min(min(dLeft, dRight), min(dNear, dFar));
    inward = max(inward, 0.0);

    const float borderBlocks = 10.0;
    const float fadeBlocks   = 10.0;
    float zoneFade = 1.0 - smoothstep(borderBlocks, borderBlocks + fadeBlocks, inward);
    if (zoneFade <= 0.001) discard;

    // Extra gradient fade toward the OUTER edge:
    // keep the existing inward fade, but clamp the very edge to ~75% opacity.
    const float edgeMin = 0.5;
    float edgeFade = mix(edgeMin, 1.0, smoothstep(0.0, borderBlocks, inward));

    // ---- power flicker ----
    float power = saturate(uElectricPower);
    float flickerStep = floor(tTick * 0.10);
    float gate = step(hash11(flickerStep + 91.7), power);
    float flickerA = mix(0.18, 1.00, gate) * mix(0.85, 1.15, hash11(flickerStep + 169.7));
    float baseOpacity = uOpacity * vColor.a * flickerA * zoneFade * edgeFade;
    if (baseOpacity < 0.01) discard;

    // ============================================================
    // Projection coordinates
    // ============================================================
    const float glyphScale = 1.0;

    float uCont = vWorldUV.x;
    vec2 gBase = vec2(uCont, vWorldPos.y);
    vec2 gScaled = gBase / glyphScale;

    const float cellPx = 1.0;
    const float snapPx = 2.0;

    float perim = 8.0 * max(uZoneRadius, 1e-6);
    float perimScaled = perim / glyphScale;
    float perimCells = max(floor(perimScaled / cellPx + 0.5), 1.0);

    vec2 baseVel = vec2(0.029, 0.021) * (0.85 + 0.15 * anim) * anim;

    vec2 wobble =
        vec2(0.1 * sin(tTick * 0.090) + 0.05 * sin(tTick * 0.173 + 1.7),
             0.08 * cos(tTick * 0.081) + 0.04 * sin(tTick * 0.161 + 3.2));

    // ============================================================
    // Requested: glyph grid drift should be MORE aggressive
    // Previously: 3.0, now 6.0 (2× faster than before, 6× original)
    // ============================================================
    const float DRIFT_MULT = 6.0;
    vec2 scrollOffset = DRIFT_MULT * (tTick * baseVel + wobble);

    vec2 k = floor((scrollOffset + vec2(cellPx)) / snapPx);
    vec2 gridPosPx = scrollOffset - k * snapPx;
    vec2 cellShift = 2.0 * k;
    vec2 gridOffsetCells = gridPosPx / cellPx;

    vec2 gRender = gScaled + gridOffsetCells;

    float gxLoop = pmod(gRender.x, perimCells);

    float gxLoopCont = gxLoop;
    float dg = max(abs(dFdx(gxLoop)), abs(dFdy(gxLoop)));
    if (dg > perimCells * 0.5) {
        if (gxLoop < perimCells * 0.5) gxLoopCont += perimCells;
    }

    vec2 gRenderAA = vec2(gxLoopCont, gRender.y);

    vec2 cellId = vec2(floor(gxLoop), floor(gRender.y));
    vec2 cellUV = fract(gRenderAA);

    vec2 logicalCellId = cellId + cellShift;
    logicalCellId.x = pmod(logicalCellId.x, perimCells);

    vec2 fw = fwidth(gRenderAA);
    float aa = max(max(fw.x, fw.y), 0.05);

    // ============================================================
    // Glyph field
    // ============================================================
    float baseHash  = hash12(logicalCellId);
    float baseHash2 = hash12(logicalCellId + 17.3);

    // IMPORTANT: always-available glyph index for this cell
    // (used by cursor highlight even if glyph isn't "present/alive")
    float glyphIndexCell = floor(fract(baseHash * 7.31) * 50.0);

    vec2 clusterCell = floor(logicalCellId / 8.0);
    float cluster = smoothstep(0.25, 0.90, hash12(clusterCell));

    float baseDensity = mix(0.08, 0.22, effect);
    float density = clamp(baseDensity + cluster * 0.20 * effect, 0.0, 0.55);
    float present = step(baseHash, density);

    float glyphA = 0.0;
    float glyphCoreVisible = 0.0;

    if (present > 0.5) {
        float lifeTicks = mix(60.0, 520.0, baseHash2);
        float phase = fract((tTick + baseHash * 1000.0) / max(lifeTicks, 1.0));
        float alive = step(phase, 0.92);

        if (alive > 0.5) {
            float shimmer = 0.55 + 0.45 * sin(tTick * (0.18 + 0.12 * baseHash2) + baseHash * 6.283185);
            // Thinner glyphs: more padding + tighter threshold
            glyphCoreVisible = sampleGlyph(cellUV, glyphIndexCell, 0.13, 0.78, 0.97);
            glyphA = glyphCoreVisible * baseOpacity * (0.55 + 0.75 * shimmer);
        }
    }

    float selected = step(fract(baseHash * 99.4), 0.015);

    // ============================================================
    // Scan lines (brighter) + slowed motion preserved
    // ============================================================
    const float SCAN_SLOW = 2.8;
    float tScan = tTick / SCAN_SLOW;

    const int MAX_SCAN = 6;
    float scanLinesA = 0.0;

    vec2 gScan = vec2(gxLoop, gRender.y);

    // Thinner scan lines
    float lineHalfW = 0.028;
    float driftBoost = mix(0.6, 1.0, effect);

    for (int i = 0; i < MAX_SCAN; i++) {
        float fi = float(i);
        float isH = step(0.5, fract(fi * 0.5)); // 0,0,1,1...

        float seed = 73.1 + fi * 19.7;

        float dir = mix(-1.0, 1.0, step(0.5, hash11(seed + 1.3)));
        float spd = mix(0.012, 0.050, hash11(seed + 2.7)) * dir * driftBoost;

        float basePosX = hash11(seed + 10.1) * perimCells;
        float basePosY = hash11(seed + 11.9) * 80.0;

        float posX = pmod(basePosX + tScan * spd * 80.0, perimCells);
        float posY = pmod(basePosY + tScan * spd * 80.0, 80.0);

        float d0, d1;
        if (isH > 0.5) {
            float yL = pmod(gScan.y, 80.0);
            d0 = abs(yL - posY);
            d1 = abs(yL - pmod(posY + 1.0, 80.0));
        } else {
            d0 = ringDist(gScan.x, posX, perimCells);
            d1 = ringDist(gScan.x, pmod(posX + 1.0, perimCells), perimCells);
        }

        float a0 = lineA(d0, lineHalfW, aa);
        float a1 = lineA(d1, lineHalfW, aa);

        float jitter = 0.70 + 0.30 * sin(tScan * (0.06 + 0.03 * hash11(seed + 5.1)) + seed);

        // BRIGHTER: was ~0.10..0.32-ish, now ~0.22..0.77-ish
        float strength = (0.22 + 0.55 * effect) * jitter;

        scanLinesA = max(scanLinesA, max(a0, a1) * strength);
    }

    // Broad sweep band (also a bit stronger)
    float heightPx = 20.0;
    float y = pmod(gRenderAA.y, heightPx);

    float scanSpeed = 0.05;
    float scanPos = pmod(tScan * scanSpeed, heightPx);
    float scanDist = ringDist(y, scanPos, heightPx);
    // Thinner sweep band
    float scanBandsA = (1.0 - smoothstep(0.06, 0.16, scanDist)) * 0.70;

    // ============================================================
    // Multiple cursors
    // ============================================================
    const float CURSOR_SLOW = 2.4;

    const int MAX_CURSORS = 4;
    float cursorCountF = clamp(floor(1.0 + effect * 3.25), 1.0, float(MAX_CURSORS));

    float boxFillMax    = 0.0;
    float boxOutlineMax = 0.0;
    float crossMax      = 0.0;
    float holeMaskMax   = 0.0;

    vec2 gCursor = vec2(gxLoop, gRender.y);

    for (int ci = 0; ci < MAX_CURSORS; ci++) {
        float fci = float(ci);
        float enabled = step(fci, cursorCountF - 1.0);
        if (enabled <= 0.0) continue;

        float seed = 200.0 + fci * 31.7;

        float cursorRate = mix(38.0, 62.0, hash11(seed + 0.1)) * CURSOR_SLOW;
        float tt = (tTick / CURSOR_SLOW) + hash11(seed + 9.7) * 1000.0;

        float stepT = floor(tt / cursorRate);
        float fracT = fract(tt / cursorRate);
        fracT = fracT * fracT * (3.0 - 2.0 * fracT);

        vec2 p0 = vec2(hash12(vec2(stepT - 1.0, seed + 1.23)),
                       hash12(vec2(stepT - 1.0, seed + 4.56)));
        vec2 p1 = vec2(hash12(vec2(stepT + 0.0, seed + 1.23)),
                       hash12(vec2(stepT + 0.0, seed + 4.56)));

        float ySpan = 70.0;
        vec2 target  = vec2(p0.x * perimCells, p0.y * ySpan);
        vec2 target2 = vec2(p1.x * perimCells, p1.y * ySpan);

        vec2 curCell = floor(mix(target, target2, fracT));
        curCell.x = pmod(curCell.x, perimCells);

        float sizeStep = floor((tt + seed * 3.1) / 60.0);
        float selSize  = 1.0 + floor(hash11(sizeStep * 1.37 + seed) * 3.0);

        float inX = step(curCell.x, cellId.x) * step(cellId.x, curCell.x + selSize - 0.5);
        float inY = step(curCell.y, cellId.y) * step(cellId.y, curCell.y + selSize - 0.5);
        float inRect = inX * inY;

        vec2 lp = gCursor - curCell;
        float dEdge = min(min(lp.x, selSize - lp.x), min(lp.y, selSize - lp.y));
        // Thinner cursor outline
        float th = 0.08;
        float outline = (1.0 - smoothstep(th, th + aa, dEdge)) * inRect;

        float vx0 = curCell.x;
        float vx1 = curCell.x + selSize;
        float hy0 = curCell.y;
        float hy1 = curCell.y + selSize;

        float dx0 = ringDist(gCursor.x, pmod(vx0, perimCells), perimCells);
        float dx1 = ringDist(gCursor.x, pmod(vx1, perimCells), perimCells);
        float dy0 = abs(gCursor.y - hy0);
        float dy1 = abs(gCursor.y - hy1);

        // Thinner cursor cross lines
        float crossW = 0.055;
        float vA = max(lineA(dx0, crossW, aa), lineA(dx1, crossW, aa));
        float hA = max(lineA(dy0, crossW, aa), lineA(dy1, crossW, aa));
        float crossA = max(vA, hA);

        float nearBlock = 1.0 - smoothstep(0.0, 15.0, min(min(dx0, dx1), min(dy0, dy1)));
        crossA *= (0.55 + 0.45 * nearBlock);

        boxFillMax    = max(boxFillMax, inRect);
        boxOutlineMax = max(boxOutlineMax, outline);
        crossMax      = max(crossMax, crossA);
        holeMaskMax   = max(holeMaskMax, inRect);
    }

    // ============================================================
    // Cursor should also highlight NON-visible glyphs:
    // Always compute a cursor glyph core for the cell (same glyph index),
    // but only pay the cost when inside any cursor.
    // ============================================================
    float cursorGlyphCore = 0.0;
    if (holeMaskMax > 0.0) {
        cursorGlyphCore = sampleGlyph(cellUV, glyphIndexCell, 0.13, 0.78, 0.97);
    }

    // Visible glyphs outside cursor
    float glyphA_outside = glyphA * (1.0 - holeMaskMax);

    // Holes inside cursor: use cursorGlyphCore so even "empty" cells get a glyph silhouette
    float hole = cursorGlyphCore * holeMaskMax;
    float holeStrength = (0.80 + 0.25 * effect);

    // ============================================================
    // Projected Circles (Rain World style)
    // ============================================================
    float circlesA = 0.0;
    vec3 circlesRGB = vec3(0.0);
    float circlesHoleMask = 0.0;   // punches out EVERYTHING (including circles) inside hollow area
    float circlesOccludeMask = 0.0; // occludes underlying patterns under the ring/fill so it truly draws on top
    float circleLinesA = 0.0;    // connection lines between circles
    
    // Current fragment position in perimeter coordinate space
    // gBase.x = perimeter U from mesh, gBase.y = world Y
    vec2 fragPosPerim = gBase; // (uCont, worldY)
    
    for (int ci = 0; ci < MAX_CIRCLES; ci++) {
        if (ci >= uCircleCount) break;

        // Unpack circle data
        vec4 circleData = uCircles[ci];
        float circleU = circleData.x;      // perimeter U coord
        float circleY = circleData.y;      // world Y
        float circleRad = circleData.z;    // base radius
        float circleBlink = clamp(circleData.w, 0.0, 1.0);

        vec4 extra = uCircleExtras[ci];
        float rotationDeg = extra.x;
        float spokesF = max(0.0, extra.y);
        float alphaScale = extra.w;

        // Distance in (perimeterU, worldY) space
        float du = fragPosPerim.x - circleU;
        float dy = fragPosPerim.y - circleY;
        float distToCenter = length(vec2(du, dy));
        float fw = fwidth(distToCenter) + 1e-6;

        // ------------------------------------------------------------
        // Desired draw order:
        // 1) spokes (like original)
        // 2) hollow ring on top (slightly smaller than spoke length) that masks everything below
        // 3) on blink: ring becomes fully filled, spokes thicken -> gear look
        // ------------------------------------------------------------

        // Shrink/expand with blink a bit (keeps the RW vibe)
        float blinkScale = mix(1.0, 0.82, circleBlink);
        float spokeLen = circleRad * blinkScale;

        // Ring sits slightly inside the spoke length so spoke tips stick out
        float ringInset = max(0.65, spokeLen * 0.12);
        float ringRad = max(0.0, spokeLen - ringInset);

        // Thickness grows on blink (helps the gear feel when filled)
        // Thinner ring
        float ringThickness = mix(0.14, 0.38, smoothstep(0.15, 1.0, circleBlink));

        // Fill factor: when blinking, the circle becomes completely filled
        float fillFactor = smoothstep(0.28, 0.70, circleBlink);

        // --------------------
        // Spokes (radiating lines)
        // --------------------
        float spokesA = 0.0;
        if (spokesF > 0.5 && distToCenter > 0.25 && distToCenter < spokeLen + ringThickness) {
            float angle = atan(dy, du) * 180.0 / 3.14159265;
            angle += rotationDeg;

            float spokeAngle = 360.0 / spokesF;
            float angleMod = mod(angle, spokeAngle);
            float spokeDistAngle = min(angleMod, spokeAngle - angleMod);

            // Convert angular distance to linear distance at this radius
            float spokeDistLinear = spokeDistAngle * distToCenter * 3.14159265 / 180.0;

            // Spokes thicken on blink to create gear appearance
            // Thinner spokes
            float spokeWidth = mix(0.04, 0.14, fillFactor);
            spokesA = 1.0 - smoothstep(spokeWidth, spokeWidth + fw, spokeDistLinear);

            // Mask spokes so the ring "draws on top" and blocks them (only tips outside ring remain)
            float ringOuter = ringRad + ringThickness * 0.5;
            float spokesOutside = smoothstep(ringOuter - fw, ringOuter + fw, distToCenter);
            spokesA *= spokesOutside;

            // Hard stop at spoke length
            float spokeEnd = 1.0 - smoothstep(spokeLen - fw, spokeLen + fw, distToCenter);
            spokesA *= spokeEnd;
        }

        // --------------------
        // Hollow ring (on top of spokes)
        // --------------------
        float ringDist = abs(distToCenter - ringRad);
        float ringA = 1.0 - smoothstep(ringThickness * 0.5, ringThickness * 0.5 + fw, ringDist);

        // --------------------
        // Filled disc on blink (replaces hollow look)
        // --------------------
        float ringOuter = ringRad + ringThickness * 0.5;
        float insideOuter = 1.0 - smoothstep(ringOuter - fw, ringOuter + fw, distToCenter);
        float fillA = insideOuter * fillFactor;

        // Circle visible alpha (spokes + ring + optional fill)
        float circleA = max(max(spokesA, ringA), fillA) * alphaScale * baseOpacity;

        // Accumulate
        circlesA = max(circlesA, circleA);

        // ------------------------------------------------------------
        // Masks:
        // - circlesOccludeMask: removes underlying shader patterns under the ring/fill so it truly draws on top
        // - circlesHoleMask: punches out EVERYTHING (incl circles) for the hollow interior (disabled when filled)
        // ------------------------------------------------------------

        // Ring/fill occlusion mask (do NOT include the hollow interior unless we're filled)
        float occlude = max(ringA, fillA);
        circlesOccludeMask = max(circlesOccludeMask, occlude);

        // Hollow interior: slightly inside the ring inner edge
        float holeR = max(0.0, ringRad - ringThickness * 0.55);
        float holeInside = 1.0 - smoothstep(holeR - fw, holeR + fw, distToCenter);
        float holeMask = holeInside * (1.0 - fillFactor);
        circlesHoleMask = max(circlesHoleMask, holeMask);
    }

    // ------------------------------------------------------------
    // Connection lines between circles (shader-side, deterministic)
    // ------------------------------------------------------------
    if (uCircleCount > 1) {
        for (int i = 0; i < MAX_CIRCLES; i++) {
            if (i >= uCircleCount) break;

            vec2 a = vec2(uCircles[i].x, uCircles[i].y);

            // Two outgoing links per circle
            for (int lk = 0; lk < 2; lk++) {
                float fi = float(i);
                float flk = float(lk);

                // Pick a target index (stable over time)
                int j = int(floor(hash11(fi * 71.7 + flk * 13.9) * float(uCircleCount)));
                if (j == i) j = (j + 1) % uCircleCount;

                // Randomly disable some links so it doesn't become a hairball
                float enabled = step(hash11(fi * 19.3 + flk * 9.7), 0.55);
                if (enabled <= 0.0) continue;

                // Unwrap b along perimeter so the segment is the shortest path
                float duAB = shortestDelta(a.x, uCircles[j].x, perim);
                vec2 b = vec2(a.x + duAB, uCircles[j].y);

                // Unwrap fragment U into the same space
                float uFrag = a.x + shortestDelta(a.x, fragPosPerim.x, perim);
                vec2 p = vec2(uFrag, fragPosPerim.y);

                // Distance to segment + endpoint fade so it doesn't draw "into" the circles
                vec2 ba = b - a;
                float h = clamp(dot(p - a, ba) / max(dot(ba, ba), 1e-6), 0.0, 1.0);
                float d = length((p - a) - ba * h);

                float fwL = max(fwidth(d), 0.02);

                float blinkLine = max(uCircles[i].w, uCircles[j].w);
                // Thinner connection lines
                float w = mix(0.035, 0.085, pow(blinkLine, 1.5));

                float lineCore = 1.0 - smoothstep(w, w + fwL, d);
                float endFade = smoothstep(0.03, 0.14, h) * smoothstep(0.03, 0.14, 1.0 - h);
                lineCore *= endFade;

                // Soft blinking along the line (Rain World-style "connectionsBlink")
                float stepT = floor(tTick * 0.12);
                float gate = step(hash11(stepT + fi * 31.1 + float(j) * 17.7 + flk * 3.3), 0.35);
                float flick = mix(0.45, 1.0, gate);

                float lineA = lineCore * flick * baseOpacity * zoneFade;
                circleLinesA = max(circleLinesA, lineA);
            }
        }
    }

    // ============================================================
    // Compose
    // ============================================================
    vec3 baseCyan = vec3(0.55, 1.00, 0.90);
    vec3 redTint  = vec3(1.00, 0.25, 0.35);
    vec3 glyphCol = mix(baseCyan, redTint, selected);

    // BRIGHTER scan lines contribution
    float scanBandsFinal = scanBandsA * baseOpacity * (0.20 + 0.55 * effect);
    float scanLinesFinal = scanLinesA * baseOpacity * (0.55 + 1.05 * effect);

    float boxFillA       = boxFillMax    * baseOpacity * (0.16 + 0.45 * effect);
    float boxOutlineA    = boxOutlineMax * baseOpacity * (0.28 + 0.68 * effect);
    float crossFinalA    = crossMax      * baseOpacity * (0.14 + 0.34 * effect);

    float outA = 0.0;
    vec3 outRGB = vec3(0.0);

    outRGB += baseCyan * scanBandsFinal;  outA = max(outA, scanBandsFinal);
    outRGB += baseCyan * scanLinesFinal;  outA = max(outA, scanLinesFinal);

    outRGB += baseCyan * crossFinalA;     outA = max(outA, crossFinalA);

    outRGB += glyphCol  * glyphA_outside; outA = max(outA, glyphA_outside);

    outRGB += baseCyan * boxFillA;        outA = max(outA, boxFillA);
    outRGB += baseCyan * boxOutlineA;     outA = max(outA, boxOutlineA);

    // Connection lines between circles
    outRGB += baseCyan * circleLinesA;    outA = max(outA, circleLinesA);

    // Circles occlude anything drawn underneath (spokes/glyphs/lines) under the ring/fill
    outRGB *= (1.0 - circlesOccludeMask);
    outA   *= (1.0 - circlesOccludeMask);

    
    // Add projected circles (additive blend with cyan color)
    outRGB += baseCyan * circlesA;        outA = max(outA, circlesA);

    if (outA <= 0.001) discard;

    outRGB = outRGB / max(outA, 1e-6);

    // Punch glyph holes inside cursor fill (now includes "non-visible" glyphs)
    float holeA = hole * holeStrength;
    outRGB *= (1.0 - holeA);

    // Punch circle inner holes: remove ALL shader pattern behind them (transparent)
    outRGB *= (1.0 - circlesHoleMask);
    outA   *= (1.0 - circlesHoleMask);
    if (outA <= 0.001) discard;

    float glow = smoothstep(0.25, 0.95, outA);
    outRGB = mix(outRGB, vec3(1.0), 0.10 * glow);

    fragColor = vec4(outRGB * vColor.rgb, outA);
}

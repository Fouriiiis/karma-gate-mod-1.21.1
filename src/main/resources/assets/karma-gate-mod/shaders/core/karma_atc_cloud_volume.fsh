#version 150

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

uniform vec3 uCameraPos;
uniform vec3 uBoxMin;
uniform vec3 uBoxMax;
uniform vec3 uProfileCenter;
uniform vec3 uProfileHalfSize;
uniform vec3 uProfileRight;
uniform vec3 uProfileForward;
uniform float uTime;
uniform float uSeed;
uniform float uAlphaScale;
uniform float uLight;
uniform float uLayerDepth;
uniform float uDistanceTint;
uniform float uStepCount;
uniform float uDensityScale;
uniform vec3 uAtmosphereColor;
uniform vec3 uCloudMultiply;

in vec3 vWorldPos;
in vec4 vColor;
in vec2 vUV;

out vec4 fragColor;

float hash13(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.yzx + 33.33);
    return fract((p.x + p.y) * p.z);
}

float overlay(float a, float b) {
    if (b > 0.5) {
        return 2.0 * a * b;
    }
    return 1.0 - 2.0 * (1.0 - a) * (1.0 - b);
}

float cloudGreenKeyMask(vec4 tex) {
    float greenEnough = smoothstep(0.12, 0.22, tex.g);
    float noRed = 1.0 - smoothstep(0.015, 0.055, tex.r);
    float noBlue = 1.0 - smoothstep(0.010, 0.040, tex.b);
    return 1.0 - clamp(greenEnough * noRed * noBlue, 0.0, 1.0);
}

float horizontalCoverage(vec3 p) {
    vec2 drift = vec2(uTime * 0.00030, -uTime * 0.00016);
    vec2 uv0 = fract(p.xz * 0.00024 + vec2(0.17, 0.61) + drift);
    vec2 uv1 = fract((p.xz + vec2(p.z, -p.x) * 0.31) * 0.00062 + vec2(0.53, 0.23) - drift * 1.7);
    vec2 uv2 = fract((p.xz + vec2(p.z, -p.x) * 0.17) * 0.00135 + vec2(0.31, 0.79));

    float n0 = texture(Sampler1, uv0).r;
    float n1 = texture(Sampler1, uv1).g;
    float n2 = texture(Sampler1, uv2).b;
    float field = n0 * 0.56 + n1 * 0.31 + n2 * 0.13;

    float broad = smoothstep(0.20, 0.78, field);
    float detail = smoothstep(0.18, 0.82, n1 * 0.65 + n2 * 0.35);
    return clamp(broad * 0.86 + detail * 0.14, 0.0, 1.0);
}

float sampleDetail(vec2 uv) {
    return texture(Sampler2, fract(uv)).r;
}

float volumeClumps(vec3 p, float u, float v, float d) {
    vec3 local = vec3(u, v, d);
    vec2 drift = vec2(uTime * 0.025, uTime * 0.018);

    float xy = sampleDetail(local.xy * vec2(3.4, 1.9) + vec2(uSeed * 1.7, 0.13) + drift);
    float zy = sampleDetail(local.zy * vec2(3.1, 2.2) + vec2(0.37, uSeed * 1.3) - drift * 0.7);
    float xz = texture(Sampler1, fract(p.xz * 0.00135 + vec2(uSeed * 0.41, uSeed * 0.17))).r;
    float fine = sampleDetail((local.xz + local.yy * 0.23) * 6.7 + vec2(uSeed * 2.1, 0.73));
    float cheap3d = hash13(floor(vec3(u * 10.0 + uSeed * 17.0, v * 5.0, d * 12.0)));

    float clump = xy * 0.30 + zy * 0.27 + xz * 0.19 + fine * 0.14 + cheap3d * 0.10;
    float puffs = smoothstep(0.24, 0.74, clump);

    vec2 cell = abs(local.xz - 0.5);
    float roundBody = 1.0 - smoothstep(0.32, 0.58, length(cell * vec2(0.90, 1.10)));
    float baseShelf = smoothstep(0.02, 0.18, 1.0 - v);
    return clamp(max(puffs * mix(0.58, 1.0, roundBody), baseShelf * 0.10), 0.0, 1.0);
}

vec4 profileSample(vec3 p, out float profileDepth, out float clds) {
    vec2 right = normalize(uProfileRight.xy);
    vec2 forward = normalize(uProfileForward.xy);
    vec3 halfSize = max(uProfileHalfSize, vec3(0.001));
    vec2 toPoint = p.xz - uProfileCenter.xz;
    float profileU = clamp(0.5 + dot(toPoint, right) / (halfSize.x * 2.0), 0.0, 0.999);
    profileDepth = clamp(0.5 + dot(toPoint, forward) / (halfSize.z * 2.0), 0.0, 1.0);

    float profileV = clamp(0.5 - (p.y - uProfileCenter.y) / (halfSize.y * 2.0), 0.0, 0.999);
    float depthTint = clamp(uLayerDepth * 0.75, 0.0, 0.95);
    float profileScroll = uTime * 0.025 * (1.0 - depthTint);
    vec2 sampleCoord = vec2(fract(uSeed + profileU - profileScroll), profileV);
    vec2 profileOffset = vec2(0.5 - profileU, 0.6666667 - profileV);
    profileOffset.y *= 0.5;

    float h2Noise = texture(Sampler1, fract(vec2(sampleCoord.x * 1.5, sampleCoord.y * 0.75))).r;
    float h2 = 0.5 - sin((uTime * 0.07 + h2Noise * 2.0) * 6.2831853) * 0.5;
    vec4 baseTex = texture(Sampler0, sampleCoord + profileOffset * 0.05 * h2 + vec2(0.0, mix(-1.0, 1.0, h2) * 0.01));
    float keyMask = cloudGreenKeyMask(baseTex);
    float redDepth = baseTex.r * keyMask;
    float dp = baseTex.r * keyMask;
    dp -= 0.1 * h2;
    dp = pow(max(dp - 0.15, 0.0), mix(0.2, 0.35, h2 * h2));
    dp = min(1.0, dp + max(0.0, ((1.0 - profileV) - 0.9) * 15.0));

    float clds1 = texture(Sampler2, fract(vec2(sampleCoord.x * 5.0,
                                            sampleCoord.y * 1.5)
                                      + profileOffset * 0.11 * dp)).r;
    float clds2 = texture(Sampler2, fract(vec2(sampleCoord.x * 8.0,
                                            sampleCoord.y * 2.5)
                                      + profileOffset * mix(0.1, 0.2, clds1) * dp)).r;

    clds = overlay(clds1, clds2);
    vec4 tex = texture(Sampler0, sampleCoord + profileOffset * 0.15 * dp);
    keyMask = min(keyMask, cloudGreenKeyMask(tex));
    redDepth = max(redDepth, tex.r * keyMask);
    clds *= keyMask;
    dp = overlay(dp, clds) * keyMask;

    tex.r = dp;
    tex.b = redDepth;
    tex.a = keyMask;
    return tex;
}

float densityAt(vec3 p, out float tone) {
    vec3 q = (p - uBoxMin) / max(uBoxMax - uBoxMin, vec3(0.001));
    if (q.x < 0.0 || q.x > 1.0 || q.y < 0.0 || q.y > 1.0 || q.z < 0.0 || q.z > 1.0) {
        tone = 0.0;
        return 0.0;
    }

    float profileDepth;
    float clds;
    vec4 profile = profileSample(p, profileDepth, clds);
    if (profile.a <= 0.001) {
        tone = 0.0;
        return 0.0;
    }
    float coverage = horizontalCoverage(p);
    float gapMask = smoothstep(0.10, 0.68, coverage);
    float dp = profile.r * profile.a * mix(0.48, 1.08, gapMask);
    dp = clamp(dp, 0.0, 1.0);

    vec2 profileRight = normalize(uProfileRight.xy);
    vec2 profileForward = normalize(uProfileForward.xy);
    vec2 toProfile = p.xz - uProfileCenter.xz;
    float orientedU = 0.5 + dot(toProfile, profileRight) / (max(uProfileHalfSize.x, 0.001) * 2.0);
    float orientedDepth = 0.5 + dot(toProfile, profileForward) / (max(uProfileHalfSize.z, 0.001) * 2.0);
    float localV = clamp(0.5 - (p.y - uProfileCenter.y) / (max(uProfileHalfSize.y, 0.001) * 2.0), 0.0, 1.0);
    float redDepth = smoothstep(0.04, 0.95, profile.b);
    float mirroredDepth = abs(orientedDepth - 0.5) * 2.0;
    float normalizedDepth = mirroredDepth / max(redDepth + 0.08, 0.08);
    float redDepthMask = 1.0 - smoothstep(0.82, 1.14, normalizedDepth);
    if (redDepthMask <= 0.001) {
        tone = 0.0;
        return 0.0;
    }
    float roundedDepth = pow(clamp(1.0 - normalizedDepth, 0.0, 1.0), 0.55);
    float clumpMask = volumeClumps(p, orientedU, localV, orientedDepth);
    float interiorNoise = mix(0.62, 1.24, clumpMask);
    dp *= interiorNoise * mix(0.72, 1.12, roundedDepth);
    dp = clamp(dp, 0.0, 1.0);

    float depthFeather = redDepthMask * mix(0.50, 1.0, roundedDepth);
    float localX = orientedU * 2.0 - 1.0;
    float localZ = orientedDepth * 2.0 - 1.0;
    float localY = (p.y - uProfileCenter.y) / max(uProfileHalfSize.y, 0.001);
    float fadeX = 1.0 - smoothstep(0.65, 1.0, abs(localX));
    float fadeZ = 1.0 - smoothstep(0.35, 1.0, abs(localZ));
    float fadeTop = 1.0 - smoothstep(0.88, 1.0, localY);
    float edgeFeather = fadeX * fadeZ * fadeTop;
    float verticalFeather = smoothstep(0.0, 0.05, q.y) * (1.0 - smoothstep(0.96, 1.0, q.y));

    float body = pow(clamp(dp, 0.0, 1.0), mix(1.16, 0.08, clds));
    float alphaShape = body * 1.18 - (1.0 - clds) * 0.22;
    alphaShape = clamp(alphaShape * mix(0.72, 1.16, clumpMask), 0.0, 1.0);
    float mistNoise = texture(Sampler2, fract(p.xz * 0.003 + vec2(uTime * 0.0025, 0.0))).r;
    float topMist = smoothstep(0.25, 0.85, mistNoise) * smoothstep(0.54, 0.92, q.y) * edgeFeather * 0.16;
    alphaShape = max(alphaShape * edgeFeather, topMist);

    float greenTone = profile.g * clamp((dp - 0.3) * 6.0, 0.48, 1.0);
    tone = clamp(overlay(greenTone, mix(clds, clumpMask, 0.18)) * 1.34, 0.0, 1.0);

    float brokenEdges = mix(0.30, 1.06, smoothstep(0.06, 0.74, coverage));
    return alphaShape * depthFeather * verticalFeather * brokenEdges * gapMask;
}

void main() {
    vec3 rayStart = uCameraPos;
    vec3 rayEnd = vWorldPos;
    vec3 ray = rayEnd - rayStart;
    float rayLen = length(ray);
    if (rayLen <= 0.001) {
        discard;
    }

    vec3 dir = ray / rayLen;
    vec3 invDir = 1.0 / max(abs(dir), vec3(0.00001)) * sign(dir);
    vec3 t0v = (uBoxMin - rayStart) * invDir;
    vec3 t1v = (uBoxMax - rayStart) * invDir;
    vec3 tminv = min(t0v, t1v);
    vec3 tmaxv = max(t0v, t1v);
    float tEnter = max(max(tminv.x, tminv.y), tminv.z);
    float tExit = min(min(tmaxv.x, tmaxv.y), tmaxv.z);
    if (tExit < 0.0 || tEnter > tExit) {
        discard;
    }

    tEnter = max(tEnter, 0.0);
    tExit = min(tExit, rayLen);
    float span = tExit - tEnter;
    if (span <= 0.001) {
        discard;
    }

    float alpha = 0.0;
    float toneAccum = 0.0;
    const int MAX_STEPS = 8;
    int steps = int(clamp(floor(uStepCount + 0.5), 3.0, float(MAX_STEPS)));
    float jitter = hash13(vec3(gl_FragCoord.xy, uSeed + uTime * 0.013));
    for (int i = 0; i < MAX_STEPS; i++) {
        if (i >= steps || alpha > 0.92) {
            break;
        }
        float fi = (float(i) + jitter) / float(steps);
        vec3 p = rayStart + dir * (tEnter + span * fi);
        float tone;
        float d = densityAt(p, tone);
        float sampleAlpha = clamp(d * span * 0.0032 * uDensityScale * uAlphaScale, 0.0, 0.42);
        toneAccum += (1.0 - alpha) * sampleAlpha * tone;
        alpha += (1.0 - alpha) * sampleAlpha;
    }

    alpha = clamp(alpha * vColor.a, 0.0, 0.96);
    if (alpha <= 0.003) {
        discard;
    }

    float tone = clamp(toneAccum / max(alpha, 0.001), 0.0, 1.0);

    vec3 cloudDark = vec3(0.42, 0.48, 0.56);
    vec3 cloudLight = vec3(0.76, 0.80, 0.86);
    vec3 atmosphere = uAtmosphereColor;

    vec3 cloudColor = pow(mix(cloudDark, cloudLight, tone), vec3(mix(1.55, 0.55, tone)));
    cloudColor *= mix(vec3(1.0), uCloudMultiply, 0.78);
    float cSharpLayerBlend = mix(0.18, 0.92, clamp(uLayerDepth, 0.0, 1.0));
    float distanceBlend = clamp(cSharpLayerBlend + uDistanceTint * 0.24, 0.0, 0.96);
    vec3 color = mix(cloudColor, atmosphere, distanceBlend);
    color *= mix(vec3(0.76), vColor.rgb, 0.24);
    color *= mix(0.86, 1.08, clamp(uLight, 0.0, 1.0));

    fragColor = vec4(color, alpha);
}

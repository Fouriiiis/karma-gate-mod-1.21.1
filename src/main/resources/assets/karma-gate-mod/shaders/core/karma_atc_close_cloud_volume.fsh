#version 150

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
uniform sampler2D Sampler3;
uniform sampler2D Sampler4;
uniform sampler2D Sampler5;

uniform float uTime;
uniform float uLight;
uniform float uOpacity;
uniform float uFirstRadius;
uniform float uFadeWidth;
uniform float uGradientLayerSpacing;
uniform float uWarp;
uniform float uNoiseInfluence;
uniform vec2 uCameraXZ;
uniform vec2 uProfileOffset;
uniform vec2 uWarpPhase;
uniform vec3 uCameraPos;
uniform vec3 uTileOrigin;
uniform vec3 uTileScale;
uniform vec3 uVoxelGrid;
uniform vec3 uAtmosphereColor;
uniform vec3 uCloudMultiply;

in vec3 vWorldPos;
in vec3 vLocalPos;
flat in vec3 vLocalNormal;
flat in float vShellOpacity;

out vec4 fragColor;

float smooth01(float value) {
    value = clamp(value, 0.0, 1.0);
    return value * value * (3.0 - 2.0 * value);
}

float overlay(float base, float blend) {
    if (blend > 0.5) {
        return 2.0 * base * blend;
    }
    return 1.0 - 2.0 * (1.0 - base) * (1.0 - blend);
}

float profileDensity(vec4 sampleColor) {
    float greenDominance = sampleColor.g - max(sampleColor.r, sampleColor.b);
    float background = smoothstep(0.06, 0.28, greenDominance);
    float coverage = (1.0 - background) * sampleColor.a;
    return coverage * (0.68 + sampleColor.r * 0.32);
}

float layoutFbm(vec2 uv, vec2 phase) {
    float first = dot(
            texture(Sampler1, fract(uv + phase)).rgb,
            vec3(0.2126, 0.7152, 0.0722)
    );
    float second = dot(texture(
            Sampler1,
            fract(uv * 2.03 + phase * 0.71 + vec2(0.19, -0.27))
    ).rgb, vec3(0.2126, 0.7152, 0.0722));
    return first * 0.67 + second * 0.33;
}

void main() {
    float radialDistance = length(vWorldPos.xz - uCameraXZ);
    float fadeStart = uFirstRadius - max(uFadeWidth, 1.0);
    float handoff = 1.0 - smooth01(
            (radialDistance - fadeStart)
                    / max(uFirstRadius - fadeStart, 1.0)
    );
    if (handoff <= 0.001) {
        discard;
    }
    float finalAlpha = clamp(vShellOpacity * uOpacity * handoff, 0.0, 1.0);
    if (finalAlpha <= 0.003) {
        discard;
    }

    // The mesh boundary lies between cells. Move a quarter voxel inward so
    // every fragment shades the occupied source voxel that emitted the face.
    vec3 samplePosition = clamp(
            vLocalPos - vLocalNormal * (0.25 / uVoxelGrid),
            vec3(0.0),
            vec3(0.999999)
    );
    ivec2 frontSize = textureSize(Sampler2, 0);
    ivec2 sideSize = textureSize(Sampler3, 0);
    ivec3 gridSize = max(ivec3(round(uVoxelGrid)), ivec3(1));
    int geometryVoxelX = clamp(
            int(floor(samplePosition.x * float(gridSize.x))),
            0,
            gridSize.x - 1
    );
    int geometryVoxelY = clamp(
            int(floor(samplePosition.y * float(gridSize.y))),
            0,
            gridSize.y - 1
    );
    int geometryVoxelZ = clamp(
            int(floor(samplePosition.z * float(gridSize.z))),
            0,
            gridSize.z - 1
    );

    // Geometry resolution is independently configurable. Map its voxel
    // coordinates across the complete authored profiles instead of treating
    // profile texels as mesh-grid coordinates. Keeping the two coordinate
    // spaces separate is also required for correct world-space gradients.
    int voxelX = int(round(
            float(geometryVoxelX)
                    / max(float(gridSize.x - 1), 1.0)
                    * float(frontSize.x - 1)
    ));
    float geometryY = float(geometryVoxelY)
            / max(float(gridSize.y - 1), 1.0);
    int voxelZ = int(round(
            float(geometryVoxelZ)
                    / max(float(gridSize.z - 1), 1.0)
                    * float(sideSize.x - 1)
    ));
    vec2 horizontalUv = vec2(voxelX, voxelZ)
            / max(vec2(frontSize.x - 1, sideSize.x - 1), vec2(1.0));
    float warpNoiseX = layoutFbm(horizontalUv, uWarpPhase);
    float warpNoiseZ = layoutFbm(horizontalUv, uWarpPhase + vec2(0.37, 0.61));
    int maxWarpX = int(floor(
            clamp(uWarp, 0.0, 1.0) * float(frontSize.x) * 0.22 + 0.5
    ));
    int maxWarpZ = int(floor(
            clamp(uWarp, 0.0, 1.0) * float(sideSize.x) * 0.22 + 0.5
    ));
    int frontPeriod = max(frontSize.x - 1, 1);
    int sidePeriod = max(sideSize.x - 1, 1);
    voxelX = int(mod(
            float(voxelX + int(floor(
                    (warpNoiseX * 2.0 - 1.0) * float(maxWarpX) + 0.5
            ))) + float(frontPeriod),
            float(frontPeriod)
    ));
    voxelZ = int(mod(
            float(voxelZ + int(floor(
                    (warpNoiseZ * 2.0 - 1.0) * float(maxWarpZ) + 0.5
            ))) + float(sidePeriod),
            float(sidePeriod)
    ));
    int frontY = int(round((1.0 - geometryY) * float(frontSize.y - 1)));
    int sideY = int(round((1.0 - geometryY) * float(sideSize.y - 1)));
    vec4 southSample = texelFetch(Sampler2, ivec2(voxelX, frontY), 0);
    vec4 westSample = texelFetch(Sampler3, ivec2(voxelZ, sideY), 0);
    vec4 northSample = texelFetch(Sampler4, ivec2(voxelX, frontY), 0);
    vec4 eastSample = texelFetch(Sampler5, ivec2(voxelZ, sideY), 0);
    float baseBlendX = float(geometryVoxelX)
            / max(float(gridSize.x - 1), 1.0);
    float baseBlendZ = float(geometryVoxelZ)
            / max(float(gridSize.z - 1), 1.0);
    float edgeLockX = pow(sin(3.14159265359 * baseBlendX), 2.0);
    float edgeLockZ = pow(sin(3.14159265359 * baseBlendZ), 2.0);
    float blendWarp = clamp(uWarp, 0.0, 1.0) * 0.32;
    float blendX = clamp(
            baseBlendX + (warpNoiseZ * 2.0 - 1.0) * blendWarp * edgeLockX,
            0.0,
            1.0
    );
    float blendZ = clamp(
            baseBlendZ + (warpNoiseX * 2.0 - 1.0) * blendWarp * edgeLockZ,
            0.0,
            1.0
    );
    vec4 frontSample = mix(southSample, northSample, blendZ);
    vec4 sideSample = mix(westSample, eastSample, blendX);

    float frontDensity = mix(
            profileDensity(southSample),
            profileDensity(northSample),
            blendZ
    );
    float sideDensity = mix(
            profileDensity(westSample),
            profileDensity(eastSample),
            blendX
    );
    float intersection = sqrt(max(0.0, frontDensity * sideDensity));
    float support = intersection * 0.80
            + min(frontDensity, sideDensity) * 0.20;
    // Reuse the two horizontal FBM channels for formation selection. The
    // previous third FBM repeated two full-resolution texture lookups for
    // every fragment even though it represented the same low-frequency field.
    float layoutNoise = clamp(warpNoiseX * 0.57 + warpNoiseZ * 0.43, 0.0, 1.0);
    float noiseSelectedSupport = mix(
            frontDensity,
            sideDensity,
            smooth01((layoutNoise - 0.12) / 0.76)
    );
    float degridStrength = clamp(uNoiseInfluence / 3.0, 0.0, 1.0);
    support = max(support, noiseSelectedSupport * 0.66 * degridStrength);

    // All procedural lookups use the integer voxel coordinate. Adjacent
    // fragments on the same voxel therefore receive exactly the same colour.
    vec3 voxelCoordinate = vec3(
            geometryVoxelX,
            geometryVoxelY,
            geometryVoxelZ
    );
    vec2 voxelXZ = (voxelCoordinate.xz + vec2(0.5)) / uVoxelGrid.xz;
    float noise = texture(
            Sampler1,
            fract(voxelXZ * 3.0 + uProfileOffset * 2.7)
    ).r;
    noise = 0.5 + sin((noise * 2.0 + uTime * 0.00025) * 6.28318530718) * 0.5;
    noise = clamp(
            0.5 + (noise - 0.5) * clamp(uNoiseInfluence, 0.0, 4.0),
            0.0,
            1.0
    );

    float detail1 = texture(
            Sampler0,
            fract(vec2(float(voxelX) / float(frontSize.x), geometryY)
                    * vec2(5.0, 1.5)
                    + uProfileOffset)
    ).r;
    float detail2 = texture(
            Sampler0,
            fract(vec2(float(voxelZ) / float(sideSize.x), geometryY)
                    * vec2(8.0, 2.5)
                    + uProfileOffset.yx * 0.73)
    ).r;
    float cloudDetail = overlay(detail1, detail2);
    cloudDetail = mix(
            0.5,
            cloudDetail,
            clamp(uNoiseInfluence, 0.0, 1.0)
    );

    // Port of Cloud.shader's authored green-channel tone, overlay operation,
    // lower-deck neutralisation and four-step palette posterisation.
    float authoredShade = clamp((frontSample.g + sideSample.g) * 0.5, 0.0, 1.0);
    float colorLevel = pow(max(authoredShade, 0.001), mix(1.4, 0.7, noise))
            * clamp((support - 0.30) * 6.0, 0.5, 1.0);
    colorLevel = overlay(colorLevel, cloudDetail);
    colorLevel = clamp(colorLevel * 1.4, 0.0, 1.0);
    float lowerDeck = clamp(
            ((1.0 - samplePosition.y) - mix(0.8, 0.5, noise))
                    * mix(5.0, 2.0, noise),
            0.0,
            1.0
    );
    colorLevel = mix(colorLevel, 0.5, lowerDeck);
    float posterized = floor(clamp(colorLevel, 0.0, 1.0) * 4.0 + 0.5) * 0.25;

    vec3 paletteBase = vec3(0.70, 0.73, 0.80);
    vec3 cloudColor = pow(
            paletteBase,
            vec3(mix(1.6, 0.4, posterized))
    );
    float voxelLight = mix(0.88, 1.05, clamp(uLight, 0.0, 1.0));
    cloudColor *= voxelLight;
    // Preserve Rain World's brighter nearby night highlights. Applying the
    // full dusk/night multiplier after atmospheric blending crushed every
    // depth to black and removed the illustrated close-to-far separation.
    cloudColor *= mix(vec3(1.0), uCloudMultiply, 0.72);

    // Measured from AboveCloudsView's night output. At full night the tonal
    // variation within one layer spans this charcoal-to-silver range rather
    // than being a uniformly multiplied daytime palette.
    const vec3 nightCloudDark = vec3(14.0, 15.0, 22.0) / 255.0;
    const vec3 nightCloudLight = vec3(59.0, 63.0, 69.0) / 255.0;
    float fullNightRed = 4.0 / 51.0;
    float nightAmount = clamp(
            (1.0 - uCloudMultiply.r) / (1.0 - fullNightRed),
            0.0,
            1.0
    );
    vec3 nightCloudColor = mix(nightCloudDark, nightCloudLight, posterized);
    cloudColor = mix(cloudColor, nightCloudColor, nightAmount);

    vec3 geometryVoxelCoordinate = vec3(
            geometryVoxelX,
            geometryVoxelY,
            geometryVoxelZ
    );
    vec3 voxelCenterLocal = (geometryVoxelCoordinate + vec3(0.5)) / uVoxelGrid;
    vec3 voxelCenterWorld = (voxelCenterLocal - vec3(0.5, 0.0, 0.5))
            * uTileScale
            + uTileOrigin;
    float voxelRadialDistance = length(voxelCenterWorld - uCameraPos);
    // The C# scene has seven close layers with cloudDepth = layerIndex / 6.
    // Treat each configurable block interval as one of those layer steps:
    // spacing maps to layer 1, 2 * spacing to layer 2, and so on. Smooth the
    // interpolation within an interval while preserving every exact layer stop.
    const float closeLayerCount = 7.0;
    float layerCoordinate = clamp(
            voxelRadialDistance / max(uGradientLayerSpacing, 1.0) - 1.0,
            0.0,
            closeLayerCount - 1.0
    );
    float layerBase = floor(layerCoordinate);
    float layerBlend = smooth01(fract(layerCoordinate));
    float csharpCloudDepth = (layerBase + layerBlend)
            / (closeLayerCount - 1.0);

    // CloseCloud.DrawSprites passes cloudDepth * 0.75 to Cloud.shader, which
    // uses it as the atmosphere blend. Applying the same value continuously
    // reproduces the C# layer fade without visible radial bands.
    float atmosphereDepth = csharpCloudDepth * 0.75;
    cloudColor = mix(cloudColor, uAtmosphereColor, atmosphereDepth);
    fragColor = vec4(cloudColor, finalAlpha);
}

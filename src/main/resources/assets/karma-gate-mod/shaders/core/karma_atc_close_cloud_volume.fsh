#version 150

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

uniform float uTime;
uniform float uLight;
uniform float uOpacity;
uniform float uVolumeDensity;
uniform float uEdgeFalloff;
uniform float uDepthPrepass;
uniform float uFirstRadius;
uniform float uFadeWidth;
uniform vec2 uCameraXZ;
uniform vec3 uCameraPos;
uniform vec3 uTileScale;
uniform vec3 uAtmosphereColor;
uniform vec3 uCloudMultiply;

in vec4 vColor;
in vec3 vWorldPos;
in vec3 vWorldNormal;
in vec3 vLocalPos;
in float vHeight;
in float vProfileShade;

out vec4 fragColor;

const float TAU = 6.28318530718;

float smooth01(float x) {
    x = clamp(x, 0.0, 1.0);
    return x * x * (3.0 - 2.0 * x);
}

float overlay(float base, float blend) {
    if (blend > 0.5) {
        return 2.0 * base * blend;
    }
    return 1.0 - 2.0 * (1.0 - base) * (1.0 - blend);
}

float volumeOpacity(float entryFacing, float densityCue) {
    float volumeRadius = max(uTileScale.y * 0.5, 1.0);
    float chordLength = 2.0 * volumeRadius * entryFacing;
    float depthToCore = chordLength * 0.5;
    float edgeRamp = smooth01(depthToCore / max(uEdgeFalloff, 1.0));
    float authoredDensity = mix(0.55, 1.0, densityCue);
    float opticalDepth = chordLength
            * edgeRamp
            * authoredDensity
            * max(uVolumeDensity, 0.0001)
            * clamp(uOpacity, 0.0, 1.0);
    return 1.0 - exp(-opticalDepth);
}

float projectedSample(sampler2D source,
                      vec3 position,
                      vec3 normal,
                      vec3 frequency,
                      vec2 drift) {
    // One dominant-axis lookup replaces three-way triplanar blending. The
    // inputs are low-frequency cloud textures, so projection transitions are
    // hidden by the authored shade while fragment texture cost drops by 3x.
    vec3 axis = abs(normal);
    vec2 uv;
    if (axis.y >= axis.x && axis.y >= axis.z) {
        uv = position.xz * frequency.xz;
    } else if (axis.x >= axis.z) {
        uv = position.zy * frequency.zy;
    } else {
        uv = position.xy * frequency.xy;
    }
    return texture(source, fract(uv + drift)).r;
}

vec3 cloudPalette(float shade, float atmosphereDepth) {
    // Cloud.shader quantizes the authored lighting into five broad values and
    // changes the exponent applied to the palette colour.
    float posterized = floor(clamp(shade, 0.0, 1.0) * 4.0 + 0.5) * 0.25;
    vec3 paletteBase = vec3(0.70, 0.73, 0.80);
    vec3 color = pow(paletteBase, vec3(mix(1.6, 0.4, posterized)));
    color = mix(color, uAtmosphereColor, clamp(atmosphereDepth, 0.0, 0.96));
    color *= mix(vec3(1.0), uCloudMultiply, 0.42);
    return color * mix(0.88, 1.06, clamp(uLight, 0.0, 1.0));
}

void main() {
    float radialDistance = length(vWorldPos.xz - uCameraXZ);
    float fadeStart = uFirstRadius - max(uFadeWidth, 1.0);
    float handoff = 1.0 - smooth01(
            (radialDistance - fadeStart)
                    / max(uFirstRadius - fadeStart, 1.0)
    );
    if (handoff <= 0.003) {
        discard;
    }

    vec3 normal = normalize(vWorldNormal);
    if (uDepthPrepass > 0.5) {
        vec3 prepassToCamera = uCameraPos - vWorldPos;
        float prepassDistance = length(prepassToCamera);
        vec3 prepassViewDirection = prepassDistance > 0.001
                ? prepassToCamera / prepassDistance
                : vec3(0.0, 1.0, 0.0);
        float prepassFacing = clamp(dot(normal, prepassViewDirection), 0.0, 1.0);
        float prepassDensity = smooth01(clamp(vProfileShade, 0.0, 1.0));
        float prepassAlpha = volumeOpacity(prepassFacing, prepassDensity) * handoff;
        if (prepassAlpha < 0.90) {
            discard;
        }
        fragColor = vec4(0.0);
        return;
    }
    vec3 local = vec3(vLocalPos.x, vHeight, vLocalPos.z);
    vec2 slowDrift = vec2(uTime * 0.00017, -uTime * 0.00011);

    // These two octaves mirror Cloud.shader's 5x/1.5x and 8x/2.5x
    // _CloudsTex samples. Triplanar projection keeps the detail attached to
    // the 3D surface and independent of camera angle.
    float clouds1 = projectedSample(
            Sampler0,
            local,
            normal,
            vec3(5.0, 1.5, 5.0),
            slowDrift
    );
    float clouds2 = projectedSample(
            Sampler0,
            local,
            normal,
            vec3(8.0, 2.5, 8.0),
            slowDrift * 0.73 + vec2(0.31, 0.17)
    );
    float clouds = overlay(clouds1, clouds2);

    float noise = projectedSample(
            Sampler1,
            local,
            normal,
            vec3(3.0, 1.5, 3.0),
            slowDrift * 0.22 + vec2(0.19, 0.43)
    );
    noise = 0.5 + sin((uTime * 0.00012 + noise * 2.0) * TAU) * 0.5;

    // The original cloud sprite's green channel is carried on the extracted
    // mesh and remains the primary source of its illustrated tonal shapes.
    float shade = pow(
            clamp(vProfileShade, 0.001, 1.0),
            mix(1.4, 0.7, noise)
    );
    shade = overlay(shade, clouds);
    shade = clamp(shade * 1.4, 0.0, 1.0);

    // Preserve the reference's broad neutral shelf near the cloud base.
    float sourceV = 1.0 - clamp(vHeight, 0.0, 1.0);
    float shelf = clamp(
            (sourceV - mix(0.8, 0.5, noise)) * mix(5.0, 2.0, noise),
            0.0,
            1.0
    );
    shade = mix(shade, 0.5, shelf);

    // Rain World's close layers use cloudDepth for atmosphere mixing. The 3D
    // field replaces seven discrete layers with the equivalent continuous
    // camera-relative gradient.
    float cloudDepth = smooth01(radialDistance / max(uFirstRadius, 1.0));
    float atmosphereDepth = cloudDepth * 0.75;

    // A restrained normal term gives true 3D forms readable curvature while
    // retaining the unlit, posterized character of the 2D shader.
    vec3 sunDir = normalize(vec3(-0.45, 0.82, -0.28));
    float normalLight = clamp(dot(normal, sunDir) * 0.5 + 0.5, 0.0, 1.0);
    shade = clamp(shade * mix(0.88, 1.08, normalLight), 0.0, 1.0);

    vec3 color = cloudPalette(shade, atmosphereDepth);
    // Approximate Blender's spherical Blend texture without raymarching. For
    // a sphere, the chord traversed by a view ray entering at this surface is
    // exactly 2R*cos(theta). The irregular cloud mesh uses the same local
    // curvature proxy, producing a genuine optical-depth gradient from its
    // silhouette toward its visually dense interior.
    vec3 toCamera = uCameraPos - vWorldPos;
    float cameraDistance = length(toCamera);
    vec3 viewDirection = cameraDistance > 0.001
            ? toCamera / cameraDistance
            : vec3(0.0, 1.0, 0.0);
    float entryFacing = clamp(dot(normal, viewDirection), 0.0, 1.0);
    float densityCue = smooth01(
            clamp(vProfileShade * 0.68 + clouds * 0.32, 0.0, 1.0)
    );
    float volumeAlpha = volumeOpacity(entryFacing, densityCue);

    color = mix(uAtmosphereColor, color, clamp(uOpacity, 0.0, 1.0));
    color = mix(uAtmosphereColor, color, handoff);
    float alpha = clamp(volumeAlpha * handoff, 0.0, 0.985);
    if (alpha <= 0.015) {
        discard;
    }
    fragColor = vec4(color, alpha);
}

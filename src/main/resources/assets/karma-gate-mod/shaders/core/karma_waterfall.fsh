#version 150

uniform sampler2D Sampler0; // Minecraft water_flow.png
uniform sampler2D Sampler1; // Rain World noise-hq.png
uniform float uRain;
uniform float uSourceWorldY;
uniform float uNoiseFeatureBlocks;
uniform vec3 uBiomeWaterColor;

in vec2 vLocalUV;
in vec3 vWaterfallState; // density, source edge, strike edge
in vec3 vWorldPos;

out vec4 fragColor;

float levelGradient(vec2 coordinate) {
    // The isolated demo's default analytic GrabTexture depth field.
    float wave = round(4.0
        + 3.0 * sin(coordinate.x * 19.0 + coordinate.y * 7.0)
        + 2.0 * sin(coordinate.y * 31.0));
    return clamp(6.0 + wave, 0.0, 29.0) / 30.0;
}

void main() {
    // One fixed room/world-space field is shared by both crossed planes. The
    // current fall length never occurs in these coordinates: moving endpoints
    // reveals more of the field instead of stretching the existing pattern.
    float horizontalWorld = vWorldPos.x + vWorldPos.z * 0.37;
    float textX = 0.5 + horizontalWorld / 36.0;        // 720 RW px / 20 px per block
    float textY = 590.0 / 650.0 + (vWorldPos.y - uSourceWorldY) / 32.5;
    float noiseU = 7.5 + horizontalWorld / max(uNoiseFeatureBlocks, 0.001);
    float noiseFlowY = 1.8 * uRain + textY * 0.2;
    float noiseValue = texture(Sampler1, vec2(noiseU, noiseFlowY)).r;
    float sincol = sin((0.8 * uRain + noiseValue * 3.0) * 3.14 * 2.0) * 0.5 + 0.5;

    float displacedY = textY + mix(-0.013, 0.013, sincol);
    float gradient = levelGradient(vec2(textX, displacedY));
    gradient = pow(floor(mix(gradient, sincol, 0.2) * 10.0) / 10.0, 0.7);

    float horizontalEdge = vLocalUV.x < 0.5
        ? vLocalUV.x * 10.0
        : (1.0 - vLocalUV.x) * 10.0;
    float verticalEdge = vLocalUV.y < 0.5
        ? vLocalUV.y / max(vWaterfallState.g, 0.00001)
        : (1.0 - vLocalUV.y) / max(vWaterfallState.b, 0.00001);
    float edge = min(horizontalEdge, verticalEdge);
    if (mix(edge, sincol, 0.5) < 0.5 || sincol < 1.0 - vWaterfallState.r) discard;

    // water_flow.png is a vertical sheet of square animation frames. Select
    // one frame explicitly, then tile it in fixed world coordinates so a
    // changing endpoint cannot stretch the Minecraft water detail either.
    vec2 waterTextureSize = vec2(textureSize(Sampler0, 0));
    float waterFrameCount = max(floor(waterTextureSize.y / waterTextureSize.x), 1.0);
    float waterFrame = mod(floor(uRain * 50.0), waterFrameCount);
    const float waterVerticalScale = 0.65;
    const float noiseVerticalWorldScale = 0.2 / 32.5;
    // Convert the noise coordinate into water-texture coordinates. Compensating
    // for their different world scales makes both fields travel downward at
    // exactly the same blocks-per-second velocity.
    float matchedWaterV = -noiseFlowY * (waterVerticalScale / noiseVerticalWorldScale);
    vec2 waterLocalUV = vec2(fract(horizontalWorld), fract(matchedWaterV));
    vec2 waterUV = vec2(waterLocalUV.x, (waterFrame + waterLocalUV.y) / waterFrameCount);
    vec4 waterFlow = texture(Sampler0, waterUV);

    float waterDetail = smoothstep(0.55, 0.85, waterFlow.r);
    float patternLight = mix(0.62, 1.15, gradient);
    vec3 waterColor = uBiomeWaterColor
        * patternLight
        * mix(0.78, 1.18, waterDetail);
    float waterAlpha = waterFlow.a * mix(0.62, 0.90, gradient);
    fragColor = vec4(waterColor, waterAlpha);
}

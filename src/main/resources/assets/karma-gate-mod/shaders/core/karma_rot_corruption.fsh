#version 150

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform float uMode;

in vec4 vColor;
in vec2 vUV;
in vec2 vNoiseUV;

out vec4 fragColor;

float sampleMask(vec2 coordinates) {
    return texture(Sampler1, fract(coordinates)).r;
}

void main() {
    float noiseValue = texture(Sampler0, vNoiseUV).r;

    // C# creates a BlackGoo sprite for every occupied tile, but each sprite's
    // radius-derived scale extends across several neighbouring tiles. Recreate
    // that overlap with three continuous, differently rotated world-space
    // projections. At the Java scale these patches span roughly 3-6 blocks.
    vec2 layerB = vec2(-vUV.y, vUV.x) * 0.84 + vec2(0.371, 0.193);
    vec2 layerC = vec2(vUV.x + vUV.y * 0.28,
                       vUV.y - vUV.x * 0.23) * 1.28 + vec2(0.127, 0.617);
    float sourceRed = min(sampleMask(vUV), min(sampleMask(layerB), sampleMask(layerC)));

    // BlackGoo.shader reconstruction used by the isolated C# demo: its atlas
    // darkness is broken up by noise, then quantized into transparent, half
    // and opaque regions. Corruption level shrinks the patch toward a zone's
    // boundary instead of replacing its recognizable tendril silhouette with
    // generic procedural noise.
    float alpha = (1.0 - sourceRed) * (0.4 + 0.6 * noiseValue);
    // Level affects the generated bulb sizes, but should not erase most of the
    // overlapping surface sprites near the corruption boundary.
    alpha *= mix(0.72, 1.0, smoothstep(0.04, 0.58, vColor.a));
    if (alpha > 0.5) alpha = pow(alpha, 0.7);
    if (alpha < 0.3) discard;
    alpha = alpha > 0.6 ? 1.0 : 0.5;
    fragColor = vec4(0.0, 0.008, 0.012, alpha);
}

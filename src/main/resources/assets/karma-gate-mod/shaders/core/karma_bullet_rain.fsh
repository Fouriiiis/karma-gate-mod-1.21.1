#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float GameTime;
uniform float uRainIntensity;
uniform float uDistortionStrength;

in vec2 v_uv;
in vec4 v_color;

out vec4 fragColor;

void main() {
    vec2 uv = clamp(v_uv, 0.0, 1.0);
    float topFade = clamp(1.0 - uv.y, 0.0, 1.0);

    // Adapted from the original BulletRain effect, but in UV/world space.
    float scroll = GameTime * (0.6 + uRainIntensity * 0.8);
    float swirl = sin((uv.y + scroll) * 18.0) * 0.01 * uRainIntensity;

    vec2 sampleUv = vec2(
        clamp(uv.x + swirl, 0.0, 1.0),
        fract(uv.y + topFade * uDistortionStrength + scroll)
    );

    vec4 col = texture(Sampler0, sampleUv);
    col.rgb = mix(col.rgb, vec3(1.0), pow(topFade, 10.0) * 0.5);
    col.a *= (0.40 + 0.60 * uRainIntensity);

    fragColor = col * v_color * ColorModulator;
}

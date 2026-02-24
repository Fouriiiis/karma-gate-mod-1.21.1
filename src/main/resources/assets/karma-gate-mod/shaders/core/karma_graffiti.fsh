#version 150

uniform sampler2D Sampler0;

uniform float GameTime;

in vec2 texCoord0;
in vec4 vData;
in vec4 vLight;
in float fragDepth;

out vec4 fragColor;

float hash21(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float valueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);

    float a = hash21(i + vec2(0.0, 0.0));
    float b = hash21(i + vec2(1.0, 0.0));
    float c = hash21(i + vec2(0.0, 1.0));
    float d = hash21(i + vec2(1.0, 1.0));

    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

void main() {
    float melt = clamp(vData.b, 0.0, 1.0);
    float opacity = clamp(vData.a, 0.0, 1.0);

    vec2 uv = texCoord0;
    if (melt > 0.001) {
        float t = GameTime;
        // Vertical streaks: high-frequency x noise + slow time scroll.
        float streakN = valueNoise(vec2(uv.x * 40.0, uv.y * 2.0 + t * 0.15));
        float streak = smoothstep(0.35, 0.75, streakN);

        // Streaky drips: push UV downward in streak bands.
        float dripN = valueNoise(vec2(uv.x * 6.0 + t * 0.03, uv.y * 0.4 + t * 0.08));
        float drip = pow(max(0.0, dripN - 0.5) * 2.0, 3.0 - melt);

        float meltOffset = drip * streak * 0.35 * melt;
        uv.y += meltOffset;

        // Fade gaps between streaks for a stringy look.
        opacity *= mix(0.45, 1.0, streak);
    }

    // don't sample outside the [0,1] range – avoids repeats on full‑screen quads
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
        discard;
    }

    vec4 texColor = texture(Sampler0, uv);

    // Discard fully transparent pixels
    if (texColor.a < 0.01) {
        discard;
    }

    vec3 litColor = texColor.rgb * vLight.rgb;
    float alpha = texColor.a * opacity;
    if (alpha < 0.01) {
        discard;
    }

    fragColor = vec4(litColor, alpha);
}

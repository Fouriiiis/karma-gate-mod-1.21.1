#version 150

uniform sampler2D Sampler0;
uniform vec3 uAtmosphereColor;
uniform vec3 uCloudMultiply;

in vec4 vColor;
in vec2 vUV;

out vec4 fragColor;

void main() {
    vec2 uv = vec2(fract(vUV.x), fract(vUV.y));
    float mask = texture(Sampler0, uv).r;
    float soft = smoothstep(0.10, 0.82, mask);
    float solidDeck = step(0.995, vColor.a);

    float ribbonAlpha = pow(soft, 0.92) * vColor.a;
    float deckAlpha = mix(0.78, 0.94, pow(soft, 0.70));
    float alpha = mix(ribbonAlpha, deckAlpha, solidDeck);
    if (alpha <= 0.003) {
        discard;
    }

    vec3 atmosphere = uAtmosphereColor;
    vec3 ribbonDim = vec3(0.35, 0.45, 0.55);
    vec3 ribbonLight = vec3(0.63, 0.70, 0.78);
    vec3 deckDim = vec3(0.42, 0.51, 0.61);
    vec3 deckLight = vec3(0.70, 0.77, 0.84);

    vec3 ribbonColor = mix(ribbonDim, ribbonLight, pow(soft, 0.72));
    vec3 deckColor = mix(deckDim, deckLight, pow(soft, 0.86));
    vec3 color = mix(ribbonColor, deckColor, solidDeck);
    color *= mix(vec3(1.0), uCloudMultiply, 0.78);
    color *= mix(vec3(0.92), vColor.rgb, 0.22);
    color = mix(color, atmosphere, mix(0.32, 0.46, solidDeck));
    fragColor = vec4(color, alpha);
}

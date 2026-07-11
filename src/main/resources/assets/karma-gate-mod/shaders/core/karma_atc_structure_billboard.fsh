#version 150

uniform sampler2D Sampler0;

in vec4 vColor;
in vec2 vUV;

out vec4 fragColor;

void main() {
    vec4 tex = texture(Sampler0, vUV);
    float alpha = tex.a * vColor.a;
    if (alpha <= 0.08) {
        discard;
    }

    fragColor = vec4(tex.rgb * vColor.rgb, alpha);
}

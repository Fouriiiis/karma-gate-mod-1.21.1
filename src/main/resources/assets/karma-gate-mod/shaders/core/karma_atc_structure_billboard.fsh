#version 150

uniform sampler2D Sampler0;
uniform vec3 uAtmosphereColor;
uniform vec3 uMultiplyColor;

in vec4 vColor;
in vec2 vUV;

out vec4 fragColor;

void main() {
    vec4 tex = texture(Sampler0, vUV);
    float alpha = tex.a * vColor.a;
    if (alpha <= 0.0) {
        discard;
    }

    // Direct port of Rain World's DistantBkgObject shader. The C# renderer
    // stores its nonlinear atmospheric depth in vertex red; green and blue
    // are not RGB tint channels for this material.
    vec3 color = mix(tex.rgb, uAtmosphereColor, clamp(vColor.r, 0.0, 1.0));
    fragColor = vec4(color * uMultiplyColor, alpha);
}

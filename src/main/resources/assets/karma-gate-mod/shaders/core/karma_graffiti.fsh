#version 150

uniform sampler2D Sampler0;

in vec2 texCoord0;
in vec4 vertexColor;
in float fragDepth;

out vec4 fragColor;

void main() {
    // don't sample outside the [0,1] range – avoids repeats on full‑screen quads
    if (texCoord0.x < 0.0 || texCoord0.x > 1.0 ||
        texCoord0.y < 0.0 || texCoord0.y > 1.0) {
        discard;
    }

    vec4 texColor = texture(Sampler0, texCoord0);

    // Discard fully transparent pixels
    if (texColor.a < 0.01) {
        discard;
    }

    // Apply vertex colour (includes lighting)
    fragColor = texColor * vertexColor;
}

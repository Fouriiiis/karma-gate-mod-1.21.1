#version 150

uniform sampler2D Sampler0;

in vec2 texCoord0;
in vec4 vertexColor;
in float fragDepth;

out vec4 fragColor;

void main() {
    vec4 texColor = texture(Sampler0, texCoord0);
    
    // Discard fully transparent pixels
    if (texColor.a < 0.01) {
        discard;
    }
    
    // Apply vertex color (includes lighting)
    fragColor = texColor * vertexColor;
}

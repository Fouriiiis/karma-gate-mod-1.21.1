#version 150

in vec3 Position;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec4 vertexColor;

void main() {
    vertexColor = Color;
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    // Keep the compositor just inside clear depth. Exact far-plane depth can
    // be rejected after depth-buffer conversion, making the band intermittent.
    gl_Position.z = gl_Position.w * 0.9999998;
}

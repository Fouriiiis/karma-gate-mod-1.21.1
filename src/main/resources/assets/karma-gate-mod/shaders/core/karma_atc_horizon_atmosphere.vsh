#version 150

in vec3 Position;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec4 vertexColor;

void main() {
    vertexColor = Color;
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    // This is a background compositor, not physical geometry. Pin it to the
    // far depth plane so the already-rendered world occludes it even though its
    // cylinder stays near enough to avoid far-clip precision problems.
    gl_Position.z = gl_Position.w;
}

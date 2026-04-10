#version 150

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

in vec3 Position;
in vec4 Color;
in vec2 UV0;

out vec2 v_uv;
out vec4 v_color;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    v_uv = UV0;
    v_color = Color;
}

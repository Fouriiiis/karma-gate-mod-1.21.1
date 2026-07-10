#version 150

in vec3 Position;
in vec4 Color;
in vec2 UV0;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat4 uViewMat;

out vec3 vWorldPos;
out vec4 vColor;
out vec2 vUV;

void main() {
    vWorldPos = Position;
    vColor = Color;
    vUV = UV0;
    gl_Position = ProjMat * ModelViewMat * uViewMat * vec4(Position, 1.0);
}

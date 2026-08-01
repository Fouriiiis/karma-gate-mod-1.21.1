#version 150

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in vec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat4 uViewMat;
uniform vec3 uTileOrigin;
uniform vec3 uTileScale;

out vec3 vWorldPos;
out vec3 vLocalPos;
flat out vec3 vLocalNormal;
flat out float vShellOpacity;

void main() {
    vec3 worldPosition = Position * uTileScale + uTileOrigin;
    vWorldPos = worldPosition;
    vLocalPos = Position + vec3(0.5, 0.0, 0.5);
    vLocalNormal = Normal;
    vShellOpacity = Color.a;
    gl_Position = ProjMat * uViewMat * vec4(worldPosition, 1.0);
}

#version 150

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform sampler2D Sampler2;

out vec2 texCoord0;
out vec4 vData;
out vec4 vLight;
out float fragDepth;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    texCoord0 = UV0;
    vData = Color;
    vLight = texelFetch(Sampler2, UV2 / 16, 0);
    fragDepth = gl_Position.z / gl_Position.w;
}

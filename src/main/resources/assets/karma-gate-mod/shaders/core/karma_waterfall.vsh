#version 150

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat4 uViewMat;
uniform vec3 uCameraWorldPos;

out vec2 vLocalUV;
out vec3 vWaterfallState;
out vec3 vWorldPos;

void main() {
    gl_Position = ProjMat * ModelViewMat * uViewMat * vec4(Position, 1.0);
    vLocalUV = UV0;
    vWaterfallState = Color.rgb;
    vWorldPos = Position + uCameraWorldPos;
}

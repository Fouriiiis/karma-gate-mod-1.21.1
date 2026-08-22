#version 150

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat4 uViewMat;
uniform float uViewDepthBias;

out vec4 vColor;
out vec2 vUV;
out vec2 vNoiseUV;

void main() {
    vec4 viewPosition = ModelViewMat * uViewMat * vec4(Position, 1.0);
    vec4 projected = ProjMat * viewPosition;

    // Iris can place a nearly coplanar horizontal decal on alternating sides
    // of the terrain depth value. Obtain the depth of a point slightly closer
    // to the camera, but retain the original clip X/Y/W so the decal neither
    // grows nor slides across its supporting model.
    if (uViewDepthBias > 0.0) {
        vec4 biasedProjection = ProjMat * vec4(
                viewPosition.xyz + vec3(0.0, 0.0, uViewDepthBias),
                viewPosition.w);
        if (abs(biasedProjection.w) > 0.000001) {
            projected.z = (biasedProjection.z / biasedProjection.w) * projected.w;
        }
    }

    gl_Position = projected;
    vColor = Color;
    vUV = UV0;
    vNoiseUV = Normal.xy;
}

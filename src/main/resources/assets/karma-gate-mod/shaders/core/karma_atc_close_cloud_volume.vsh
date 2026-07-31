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
uniform vec2 uTileYawSinCos;

out vec4 vColor;
out vec3 vWorldPos;
out vec3 vWorldNormal;
out vec3 vLocalPos;
out float vHeight;
out float vProfileShade;

void main() {
    vec3 local = Position * uTileScale;
    float sy = uTileYawSinCos.x;
    float cy = uTileYawSinCos.y;
    vec3 worldPosition = vec3(
            local.x * cy + local.z * sy,
            local.y,
            -local.x * sy + local.z * cy
    ) + uTileOrigin;
    // Normal vectors require the inverse of the non-uniform tile scale. The
    // cloud height is much smaller than its X/Z extent, so rotating the raw
    // local normal exaggerated voxel facets along vertical surfaces.
    vec3 scaledNormal = normalize(Normal / max(abs(uTileScale), vec3(0.0001)));
    vec3 worldNormal = normalize(vec3(
            scaledNormal.x * cy + scaledNormal.z * sy,
            scaledNormal.y,
            -scaledNormal.x * sy + scaledNormal.z * cy
    ));
    vec4 world = vec4(worldPosition, 1.0);
    vColor = Color;
    vWorldPos = world.xyz;
    vWorldNormal = worldNormal;
    vLocalPos = Position + vec3(0.5, 0.0, 0.5);
    vHeight = UV0.x;
    vProfileShade = UV0.y;
    gl_Position = ProjMat * uViewMat * world;
}

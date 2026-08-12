#version 150

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

uniform float uTime;
uniform float uLight;
uniform float uDistantStyle;
uniform vec3 uAtmosphereColor;
uniform vec3 uBiomeFogColor;
uniform vec3 uCloudMultiply;

// Matches AboveCloudsView's sprite-color payload:
//   r = atmospheric depth, g = random U phase,
//   b = vertical flattening, a = sprite opacity.
in vec4 vColor;
in vec2 vUV;

out vec4 fragColor;

float overlay(float base, float blend) {
    if (blend > 0.5) {
        return 2.0 * base * blend;
    }
    return 1.0 - 2.0 * (1.0 - base) * (1.0 - blend);
}

float sourceCoverage(vec4 texel) {
    // clouds1-3 use rgba(0,101,0) outside the authored cloud. This replaces
    // Cloud.shader's unavailable _LevelTex/_GrabTexture rejection without
    // treating dark cloud shading as transparent.
    float greenBackground = smoothstep(0.30, 0.38, texel.g)
            * (1.0 - smoothstep(0.010, 0.055, texel.r))
            * (1.0 - smoothstep(0.008, 0.040, texel.b));
    return 1.0 - clamp(greenBackground, 0.0, 1.0);
}

vec4 sampleProfile(vec2 uv) {
    // clouds1-3 tile horizontally but not vertically. The JSON sampler must
    // repeat for U, so clamp V here after every distortion. Without this, a
    // sample warped above the sprite wraps to its solid bottom row and appears
    // as detached horizontal patches over the cloud tops.
    return texture(Sampler0, vec2(fract(uv.x), clamp(uv.y, 0.0, 0.99)));
}

vec3 paletteColor(float shade) {
    float posterizedShade = floor(shade * 4.0 + 0.5) * 0.25;
    // Cloud.shader and CloudDistant.shader sample the same room-palette texel.
    // Sharing the biome fog input with the close-volume shader is essential:
    // both sides of the handoff have atmospheric depth 0.75 in C#.
    vec3 paletteBase = clamp(uBiomeFogColor, vec3(1.0 / 255.0), vec3(1.0));
    vec3 color = pow(paletteBase, vec3(mix(1.6, 0.4, posterizedShade)));
    // Tint the palette, not the completed atmospheric result. This keeps the
    // close silver highlights visible at night while distant layers converge
    // on Rain World's independently animated atmosphere colour.
    color *= mix(vec3(1.0), uCloudMultiply, 0.72);

    const vec3 nightCloudDark = vec3(14.0, 15.0, 22.0) / 255.0;
    const vec3 nightCloudLight = vec3(59.0, 63.0, 69.0) / 255.0;
    float fullNightRed = 4.0 / 51.0;
    float nightAmount = clamp(
            (1.0 - uCloudMultiply.r) / (1.0 - fullNightRed),
            0.0,
            1.0
    );
    color = mix(
            color,
            mix(nightCloudDark, nightCloudLight, posterizedShade),
            nightAmount
    );
    color *= mix(0.88, 1.06, clamp(uLight, 0.0, 1.0));
    color = mix(color, uAtmosphereColor, clamp(vColor.r, 0.0, 0.96));
    return color;
}

void main() {
    float flattening = max(vColor.b, 1.0 / 255.0);
    float wind = uTime;

    // Distant rings receive a world-Z UV from Java so their two hemispheres
    // mirror and translate north together. Retain the legacy shader scroll
    // only for non-distant cards; applying both would double the movement and
    // turn the world-space motion back into an apparent rotation.
    float shaderProfileScroll = uDistantStyle > 0.5
            ? 0.0
            : 0.00070 * wind * (1.0 - vColor.r);
    float tileU = fract(vColor.g + vUV.x - shaderProfileScroll);
    vec2 sampleCoord = vec2(tileU, min(vUV.y, 0.99));

    if (uDistantStyle > 0.5) {
        // Direct translation of CloudDistant.frag. Unlike the close shader it
        // performs no screen-space swelling and uses one detail octave.
        vec4 mainTex = sampleProfile(sampleCoord);
        float coverage = sourceCoverage(mainTex);
        float profileDensity = mainTex.r;
        float clouds = texture(
                Sampler2,
                fract(vec2(
                        sampleCoord.x * 5.0 / flattening,
                        sampleCoord.y * 1.5 + wind * 0.00055
                ))
        ).r;
        float detailedDensity = overlay(profileDensity, clouds);

        float shade = mainTex.g * clamp((detailedDensity - 0.3) * 6.0, 0.5, 1.0);
        // CloudDistant quantizes density opacity into thirds. Restoring this
        // is important to the illustrated layer gradient: thin detail remains
        // translucent while dense formations stay solid.
        float densityAlpha = round((
                pow(
                        clamp(detailedDensity, 0.0, 1.0),
                        mix(1.2, 0.05, clouds)
                ) * 1.25
                        - (1.0 - clouds) * 0.2
        ) * 3.0) / 3.0;
        float alpha = coverage
                * clamp(densityAlpha, 0.0, 1.0)
                * vColor.a;
        if (alpha <= 0.003) {
            discard;
        }
        fragColor = vec4(paletteColor(shade), alpha);
        return;
    }

    // Keep the close-cloud shaping attached to the repeated asset. The
    // original screen-position input made the detail and swelling rearrange
    // whenever a 3D camera turned, which is inappropriate for fixed bands.
    vec2 profilePosition = vec2(
            fract(vColor.g + vUV.x),
            clamp(vUV.y, 0.0, 1.0)
    );
    vec2 profileOffset = vec2(0.5, 0.6666667) - profilePosition;
    profileOffset.y *= 0.5;

    float h2Noise = texture(
            Sampler1,
            fract(vec2(sampleCoord.x * 1.5, sampleCoord.y * 0.75 + vColor.g))
    ).r;
    float h2 = 0.5 - sin((wind * 0.0012 + h2Noise * 2.0) * 6.2831853) * 0.5;

    vec4 sourceTex = sampleProfile(sampleCoord);
    vec4 baseTex = sampleProfile(
            sampleCoord + profileOffset * 0.05 * h2
                    + vec2(0.0, mix(-1.0, 1.0, h2) * 0.01)
    );
    // Coverage belongs to the authored, unwarped silhouette. Using baseTex
    // here lets distortion wander onto green background and cuts holes through
    // cloud pixels that are valid in the source asset.
    float coverage = sourceCoverage(sourceTex);
    float dp = baseTex.r;
    dp -= 0.1 * h2;
    dp = pow(max(dp - 0.15, 0.0), mix(0.2, 0.35, h2 * h2));

    // Minecraft's texture V axis is top-to-bottom. This is the translated
    // version of Rain World's bottom-shelf term.
    dp = min(1.0, dp + max(0.0, (vUV.y - 0.9) * 15.0));
    // The C# shader intentionally turns the bottom tenth into continuous
    // cover, even where the source asset is green.
    coverage = max(coverage, smoothstep(0.89, 0.92, vUV.y));

    float clds1 = texture(
            Sampler2,
            fract(vec2(
                    sampleCoord.x * 5.0 / flattening,
                    sampleCoord.y * 1.5 + wind * 0.00055
            ) + profileOffset * 0.11 * dp)
    ).r;
    float clds2 = texture(
            Sampler2,
            fract(vec2(
                    sampleCoord.x * 8.0 / flattening,
                    sampleCoord.y * 2.5 + wind * 0.00040
            ) * mix(2.0, 1.0, sin(vColor.r * 3.14159265))
                    + profileOffset * mix(0.1, 0.2, clds1) * dp)
    ).r;
    float clouds = overlay(clds1, clds2);
    float detailedDensity = overlay(dp, clouds);

    vec4 shapedTex = sampleProfile(sampleCoord + profileOffset * 0.15 * detailedDensity);

    float lightingNoise = texture(
            Sampler1,
            fract(vec2(
                    (sampleCoord.x + profileOffset.x * 0.5 * detailedDensity) * 3.0,
                    (sampleCoord.y + profileOffset.y * 0.5 * detailedDensity) * 1.5
            ))
    ).r;
    lightingNoise = 0.5
            + sin((wind * 0.00012 + lightingNoise * 2.0) * 6.2831853) * 0.5;

    float shade = pow(shapedTex.g, mix(1.4, 0.7, lightingNoise))
            * clamp((detailedDensity - 0.3) * 6.0, 0.5, 1.0);
    shade = overlay(shade, clouds);
    shade = clamp(shade * 1.4, 0.0, 1.0);
    shade = mix(
            shade,
            0.5,
            clamp((vUV.y - mix(0.8, 0.5, lightingNoise))
                    * mix(5.0, 2.0, lightingNoise), 0.0, 1.0)
    );

    // Rain World quantizes the cloud lighting into five values. Keeping those
    // broad tonal islands is what gives the reference clouds their illustrated
    // rather than photographic appearance.
    vec3 cloudColor = paletteColor(shade);

    // The authored cutout owns opacity. Keep Rain World's broad 2/3-to-solid
    // body levels, but do not feed cloudstexture or lighting noise into alpha.
    float bodyAlpha = mix(2.0 / 3.0, 1.0, smoothstep(0.05, 0.72, dp));
    float alpha = coverage * bodyAlpha * vColor.a;
    if (alpha <= 0.003) {
        discard;
    }

    fragColor = vec4(cloudColor, alpha);
}

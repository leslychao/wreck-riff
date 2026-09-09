#import "Common/ShaderLib/GLSLCompat.glsllib"

varying vec4 effectColor;
varying vec2 effectUv;
varying float effectShape;
varying vec2 effectVariation;
varying vec3 effectGroundPosition;
uniform float m_FlashIntensity;

float groundHash(vec3 p) {
    return fract(sin(dot(p, vec3(127.1, 311.7, 74.7))) * 43758.5453);
}
float groundNoise(vec3 p) {
    // Volumetric noise remains irregular on both floors and vertical impact surfaces.
    vec3 cell = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float low = mix(mix(groundHash(cell), groundHash(cell + vec3(1.0, 0.0, 0.0)), f.x),
                    mix(groundHash(cell + vec3(0.0, 1.0, 0.0)), groundHash(cell + vec3(1.0, 1.0, 0.0)), f.x), f.y);
    float high = mix(mix(groundHash(cell + vec3(0.0, 0.0, 1.0)), groundHash(cell + vec3(1.0, 0.0, 1.0)), f.x),
                     mix(groundHash(cell + vec3(0.0, 1.0, 1.0)), groundHash(cell + vec3(1.0, 1.0, 1.0)), f.x), f.y);
    return mix(low, high, f.z);
}
float missingCell(float bit) {
    return mod(floor(effectVariation.x / bit), 2.0);
}
float groundEdgeDistance(vec2 uv) {
    // Distance to missing neighbouring cells, including concave corners. Adjacent
    // supported cells agree at their shared edge, so no grid seam enters the image.
    vec2 p = uv * 0.5;
    float d = 1.0;
    if (missingCell(1.0) > 0.5) d = min(d, 0.5 - p.x);
    if (missingCell(2.0) > 0.5) d = min(d, 0.5 + p.x);
    if (missingCell(4.0) > 0.5) d = min(d, 0.5 - p.y);
    if (missingCell(8.0) > 0.5) d = min(d, 0.5 + p.y);
    if (missingCell(16.0) > 0.5) d = min(d, length(vec2(0.5) - p));
    if (missingCell(32.0) > 0.5) d = min(d, length(vec2(0.5 + p.x, 0.5 - p.y)));
    if (missingCell(64.0) > 0.5) d = min(d, length(vec2(0.5 - p.x, 0.5 + p.y)));
    if (missingCell(128.0) > 0.5) d = min(d, length(vec2(0.5) + p));
    return d;
}
void main() {
    float coverage = 1.0;
    vec3 color = effectColor.rgb;
    if (effectShape > 5.5) {
        // Feather both sides of the thin impact warning without moving its radius.
        coverage = 1.0 - smoothstep(0.35, 1.0, abs(effectUv.y));
    } else if (effectShape > 4.5) {
        float soot = groundNoise(effectGroundPosition * 2.3);
        float grain = groundNoise(effectGroundPosition * 17.0);
        float edge = groundEdgeDistance(effectUv);
        coverage = smoothstep(0.0, 0.22 + soot * 0.20, edge)
                 * mix(0.28, 1.0, soot) * mix(0.65, 1.0, grain);
        float embers = smoothstep(0.70, 0.90, grain) * smoothstep(0.48, 0.78, soot);
        color = mix(color, vec3(0.9, 0.24, 0.025), embers * 0.8);
    } else if (effectShape > 0.5) {
        // Original analytic sprite mask: no external bitmap or hard rectangular silhouette.
        float angle = effectVariation.x;
        mat2 turn = mat2(cos(angle), -sin(angle), sin(angle), cos(angle));
        vec2 uv = turn * effectUv;
        // Stable per-particle masks share the same six vertices and bounded sprite radius.
        uv.x *= mix(1.02, 1.35, effectVariation.y);
        float radius = length(uv);
        float seed = effectVariation.y * 6.283185;
        if (effectShape > 3.5) {
            float edge = 0.85 + 0.095 * sin(uv.x * 12.0 + seed) * sin(uv.y * 9.0 - seed);
            coverage = 1.0 - smoothstep(0.10, edge, radius);
        } else if (effectShape > 2.5) {
            // Critical smoke retains a broad opaque core while its outer edge stays soft.
            float edge = 0.89 + 0.075 * sin(uv.x * 8.0 + uv.y * 5.0 + seed)
                                      * sin(uv.y * 7.0 - uv.x * 4.0);
            coverage = 1.0 - smoothstep(0.35, edge, radius);
        } else if (effectShape < 1.5) {
            float lobes = 0.87 + 0.08 * sin(uv.x * 8.0 + uv.y * 5.0 + seed)
                                * sin(uv.y * 7.0 - uv.x * 4.0);
            coverage = 1.0 - smoothstep(0.12, lobes, radius);
            coverage *= coverage;
        } else {
            coverage = 1.0 - smoothstep(0.0, 1.0, radius);
            coverage *= coverage;
        }
    }
    float alpha = effectColor.a * coverage;
    if ((effectShape > 1.5 && effectShape < 2.5) || (effectShape > 3.5 && effectShape < 4.5))
        alpha *= mix(0.25, 1.0, m_FlashIntensity);
    if (alpha < 0.003) discard;
    gl_FragColor = vec4(color, alpha);
}

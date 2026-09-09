#import "Common/ShaderLib/GLSLCompat.glsllib"

varying vec4 effectColor;
varying vec2 effectUv;
varying float effectShape;
varying vec2 effectVariation;
uniform float m_FlashIntensity;

void main() {
    float coverage = 1.0;
    if (effectShape > 0.5) {
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
    if ((effectShape > 1.5 && effectShape < 2.5) || effectShape > 3.5)
        alpha *= mix(0.25, 1.0, m_FlashIntensity);
    if (alpha < 0.003) discard;
    gl_FragColor = vec4(effectColor.rgb, alpha);
}

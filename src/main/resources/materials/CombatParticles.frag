#import "Common/ShaderLib/GLSLCompat.glsllib"

varying vec4 effectColor;
varying vec2 effectUv;
varying float effectShape;

void main() {
    float coverage = 1.0;
    if (effectShape > 0.5) {
        // Original analytic sprite mask: no external bitmap or hard rectangular silhouette.
        float radius = length(effectUv);
        if (effectShape < 1.5) {
            float lobes = 0.89 + 0.06 * sin(effectUv.x * 8.0 + effectUv.y * 5.0)
                                * sin(effectUv.y * 7.0 - effectUv.x * 4.0);
            coverage = 1.0 - smoothstep(0.12, lobes, radius);
            coverage *= coverage;
        } else {
            coverage = 1.0 - smoothstep(0.0, 1.0, radius);
            coverage *= coverage;
        }
    }
    float alpha = effectColor.a * coverage;
    if (alpha < 0.003) discard;
    gl_FragColor = vec4(effectColor.rgb, alpha);
}

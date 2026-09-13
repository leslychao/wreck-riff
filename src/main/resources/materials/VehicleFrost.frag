#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform sampler2D m_DamageMap;
uniform float m_MaxOpacity;
varying vec3 frostPosition;
varying vec3 frostRestNormal;
varying vec3 frostNormal;
varying vec2 frostUv;

vec2 crystalSeed(vec2 cell) {
    return fract(sin(vec2(dot(cell, vec2(127.1, 311.7)),
                          dot(cell, vec2(269.5, 183.3)))) * 43758.5453);
}

void main() {
    // Project in vehicle space rather than repeating the atlas on each panel.
    // The same metre-scale pattern works on the Rivet, Spark and Grinder LODs.
    vec3 axis = abs(frostRestNormal);
    vec2 surface = axis.y >= max(axis.x, axis.z) ? frostPosition.xz
                 : axis.x >= axis.z ? frostPosition.zy : frostPosition.xy;
    vec2 p = surface * 5.0;
    vec2 cell = floor(p), local = fract(p);
    vec2 crystal = vec2(0.0), selectedSeed = vec2(0.0);
    float nearest = 8.0, second = 8.0;
    // Fixed nine samples, with no textures, frame history or runtime generation.
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 neighbour = vec2(float(x), float(y));
            vec2 seed = crystalSeed(cell + neighbour);
            vec2 point = neighbour + 0.18 + seed * 0.64 - local;
            float distanceSquared = dot(point, point);
            if (distanceSquared < nearest) {
                second = nearest;
                nearest = distanceSquared;
                crystal = point;
                selectedSeed = seed;
            } else {
                second = min(second, distanceSquared);
            }
        }
    }
    float edge = sqrt(second) - sqrt(nearest);
    // Clear hairline cracks surrounded by cloudy ice, with sparse six-arm crystals.
    float crack = 1.0 - smoothstep(0.018, 0.052, edge);
    float ridge = 1.0 - smoothstep(0.07, 0.20, edge);
    float arms = min(abs(crystal.y), min(abs(dot(crystal, vec2(0.866025, 0.5))),
                                        abs(dot(crystal, vec2(0.866025, -0.5)))));
    float branches = (1.0 - smoothstep(0.016, 0.045, arms))
                   * (1.0 - smoothstep(0.16, 0.38, length(crystal)))
                   * step(0.42, selectedSeed.x);
    float facet = (1.0 - smoothstep(0.04, 0.17, abs(crystal.x) + abs(crystal.y) * 0.72))
                * step(0.60, selectedSeed.y);
    float cloud = smoothstep(-0.55, 0.65,
                  sin(surface.x * 2.7 + sin(surface.y * 1.9)) * sin(surface.y * 3.1 + 0.6));
    float opacity = (0.025 + cloud * 0.20 + ridge * 0.19 + branches * 0.38 + facet * 0.28)
                  * (1.0 - crack * 0.78);
    vec3 damage = texture2D(m_DamageMap, frostUv).rgb;
    // Retain readable paint, dents, scorch and glass cracks under the ice.
    opacity *= 1.0 - max(damage.r, max(damage.g, damage.b)) * 0.72;
    float sparkle = clamp(branches + facet + ridge * 0.32, 0.0, 1.0);
    float facing = 0.72 + 0.28 * abs(dot(normalize(frostNormal), normalize(vec3(0.35, 0.85, 0.4))));
    vec3 color = mix(vec3(0.36, 0.67, 0.79), vec3(0.87, 0.97, 1.0), sparkle) * facing;
    // Ordinary colour is the primary signal: the ice remains legible without bloom.
    gl_FragColor = vec4(color, clamp(opacity, 0.0, m_MaxOpacity));
}

#import "Common/ShaderLib/GLSLCompat.glsllib"
#ifdef SOFT_PARTICLES
#import "Common/ShaderLib/MultiSample.glsllib"
uniform DEPTHTEXTURE m_SceneDepth;
uniform vec2 m_CameraPlanes;
#endif

varying vec4 effectColor;
varying vec2 effectUv;
varying float effectShape;
varying vec2 effectVariation;
varying vec3 effectGroundPosition;
uniform float m_FlashIntensity;
uniform sampler2D m_SmokeAtlas;
uniform sampler2D m_FlameAtlas;
uniform sampler2D m_BlastAtlas;
uniform sampler2D m_DustAtlas;
uniform sampler2D m_AuxiliaryAtlas;
uniform vec3 m_LightDirection;
uniform vec4 m_KeyLight;
uniform vec4 m_FillLight;
varying vec2 effectAnimation;
varying float effectViewDistance;
varying vec4 effectClipPosition;

vec2 frameUv(vec2 uv, float frame, float grid, float gutter) {
    vec2 cell = vec2(mod(frame, grid), floor(frame / grid));
    return (cell + gutter + clamp(uv, 0.0, 1.0) * (1.0 - gutter * 2.0)) / grid;
}
vec4 atlasSample(sampler2D atlas, vec2 uv, float resolution) {
#if __VERSION__ >= 130
    // Eight gutter pixels remain one full texel at mip 3. Clamp derivatives
    // there so far-away billows cannot sample another animation frame's mip.
    vec2 dx=dFdx(uv),dy=dFdy(uv);
    float maximum=8.0/resolution;
    dx*=min(1.0,maximum/max(length(dx),0.000001));
    dy*=min(1.0,maximum/max(length(dy),0.000001));
    return textureGrad(atlas,uv,dx,dy);
#else
    return texture2D(atlas,uv);
#endif
}
vec4 baked(sampler2D atlas, vec2 uv) {
    float current = clamp(effectAnimation.x, 0.0, 63.0);
    vec4 first = atlasSample(atlas, frameUv(uv, floor(current), 8.0, 0.03125),2048.0);
    vec4 next = atlasSample(atlas, frameUv(uv, min(63.0, floor(current) + 1.0), 8.0, 0.03125),2048.0);
    return mix(first, next, fract(current));
}
vec3 fireRadiance(vec4 volume, float heat) {
    // Temperature is carried by the baked light, never a new analytic flame mask.
    // Preserve its turbulent bright/dark fronts instead of tinting every lobe brown.
    float temperature=clamp(dot(volume.rgb,vec3(.15,.70,.15))*1.4,0.0,1.0);
    vec3 edge=vec3(1.0,.12,.008);
    vec3 front=mix(edge,vec3(1.4,.70,.06),smoothstep(.10,.45,temperature));
    vec3 core=mix(front,vec3(1.65,1.35,.62),smoothstep(.40,.85,temperature));
    return mix(vec3(.24,.23,.22)*mix(vec3(.6),vec3(1.0),volume.rgb),core,heat);
}
float surfaceFade() {
#ifdef SOFT_PARTICLES
    vec2 screen = effectClipPosition.xy / effectClipPosition.w * 0.5 + 0.5;
    float fade = 0.0;
    float softness = max(0.025, effectAnimation.y);
#ifdef RESOLVE_DEPTH_MS
    int count = m_NumSamplesDepth;
#else
    int count = 1;
#endif
    for (int i = 0; i < count; i++) {
        float z = fetchTextureSample(m_SceneDepth, screen, i).r * 2.0 - 1.0;
        float distance = 2.0 * m_CameraPlanes.x * m_CameraPlanes.y /
            (m_CameraPlanes.y + m_CameraPlanes.x - z * (m_CameraPlanes.y - m_CameraPlanes.x));
        // Average coverage, never depth: mixed MSAA foreground/background samples
        // must not manufacture a phantom surface at the silhouette of a vehicle.
        fade += clamp((distance - effectViewDistance) / softness, 0.0, 1.0);
    }
    return fade / float(count);
#else
    return 1.0;
#endif
}

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
    if (effectShape > 5.5 && effectShape < 6.5) {
        // Feather both sides of the thin impact warning without moving its radius.
        coverage = 1.0 - smoothstep(0.35, 1.0, abs(effectUv.y));
    } else if (effectShape > 4.5 && effectShape < 5.5) {
        float soot = groundNoise(effectGroundPosition * 2.3);
        float grain = groundNoise(effectGroundPosition * 17.0);
        float edge = groundEdgeDistance(effectUv);
        coverage = smoothstep(0.0, 0.22 + soot * 0.20, edge)
                 * mix(0.28, 1.0, soot) * mix(0.65, 1.0, grain);
        float embers = smoothstep(0.70, 0.90, grain) * smoothstep(0.48, 0.78, soot);
        color = mix(color, vec3(0.9, 0.24, 0.025), embers * 0.8);
    } else if (effectShape > 0.5) {
        float angle = effectVariation.x;
        mat2 turn = mat2(cos(angle), -sin(angle), sin(angle), cos(angle));
        vec2 uv = turn * effectUv;
        vec2 texUv = uv * .5 + .5;
        if (any(greaterThan(abs(uv), vec2(1.0)))) discard;
        vec4 volume;
        bool smoke = effectShape < 1.5 || (effectShape > 2.5 && effectShape < 3.5) ||
                     (effectShape > 6.5 && effectShape < 7.5);
        if (smoke) {
            volume = effectShape > 6.5 ? baked(m_DustAtlas, texUv) : baked(m_SmokeAtlas, texUv);
            // A density gradient plus rounded lobe normal adds directional scene
            // lighting without another normal atlas or a light loop per fragment.
            vec2 offset = vec2(1.0 / 240.0, 0.0);
            float dx = (effectShape > 6.5 && effectShape < 7.5 ? baked(m_DustAtlas, clamp(texUv + offset.xy, 0.0, 1.0)).a : baked(m_SmokeAtlas, clamp(texUv + offset.xy, 0.0, 1.0)).a) - volume.a;
            float dy = (effectShape > 6.5 && effectShape < 7.5 ? baked(m_DustAtlas, clamp(texUv + offset.yx, 0.0, 1.0)).a : baked(m_SmokeAtlas, clamp(texUv + offset.yx, 0.0, 1.0)).a) - volume.a;
            vec3 normal = normalize(vec3(-uv.x - dx * 7.0, -uv.y - dy * 7.0, .8));
            vec3 light = m_FillLight.rgb * .8 + m_KeyLight.rgb * (.23 + .60 * max(0.0, dot(normal, -m_LightDirection)));
            color *= mix(vec3(.65), volume.rgb, .4) * light;
        } else if (effectShape > 8.5) {
            // The auxiliary atlas is baked orange. Cryogenic cores share its soft
            // silhouette only; multiplying its RGB would turn the ice yellow-green.
            volume = atlasSample(m_AuxiliaryAtlas, frameUv(texUv, floor(effectVariation.y * 7.99), 4.0, .03125),1024.0);
        } else if (effectShape > 7.5) {
            volume = baked(m_FlameAtlas, texUv);color *= fireRadiance(volume,1.0);
        } else if (effectShape > 3.5) {
            volume = baked(m_BlastAtlas, texUv);color *= fireRadiance(volume,1.0-smoothstep(4.5,15.0,mod(effectAnimation.x,21.0)));
        } else {
            volume = atlasSample(m_AuxiliaryAtlas, frameUv(texUv, floor(effectVariation.y * 7.99), 4.0, .03125),1024.0);
            color *= volume.rgb;
        }
        coverage = volume.a;
    }
    float alpha = effectColor.a * coverage * surfaceFade();
    if ((effectShape > 1.5 && effectShape < 2.5) || (effectShape > 3.5 && effectShape < 4.5) || effectShape > 8.5)
        alpha *= mix(0.25, 1.0, m_FlashIntensity);
    if (alpha < 0.003) discard;
    gl_FragColor = vec4(color, alpha);
}

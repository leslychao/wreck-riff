#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform mat4 g_WorldViewMatrix;
uniform mat4 g_ProjectionMatrix;
attribute vec3 inPosition;
attribute vec4 inColor;
attribute vec2 inTexCoord;
attribute vec2 inTexCoord2;
attribute vec2 inTexCoord3;
varying vec4 effectColor;
varying vec2 effectUv;
varying float effectShape;
varying vec2 effectVariation;

void main() {
    vec4 viewPosition = g_WorldViewMatrix * vec4(inPosition, 1.0);
    // Radius zero preserves the exact world-space geometry of authoritative hit tracers.
    viewPosition.xy += (inTexCoord * 2.0 - 1.0) * inTexCoord2.x;
    gl_Position = g_ProjectionMatrix * viewPosition;
    effectColor = inColor;
    effectUv = inTexCoord * 2.0 - 1.0;
    effectShape = inTexCoord2.y;
    effectVariation = inTexCoord3;
}

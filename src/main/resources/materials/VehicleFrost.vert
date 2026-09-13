#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/MorphAnim.glsllib"

uniform mat4 g_WorldViewProjectionMatrix;
attribute vec3 inPosition;
attribute vec3 inNormal;
attribute vec2 inTexCoord;
varying vec3 frostPosition;
varying vec3 frostRestNormal;
varying vec3 frostNormal;
varying vec2 frostUv;

void main() {
    vec4 position = vec4(inPosition, 1.0);
    vec3 normal = inNormal;
    #ifdef NUM_MORPH_TARGETS
        // The source damage mesh carries position, normal and tangent targets.
        // Use the full overload so jME preserves the morphed normal as well.
        vec3 tangent = vec3(0.0);
        Morph_Compute(position, normal, tangent);
    #endif
    gl_Position = g_WorldViewProjectionMatrix * position;
    // Rest coordinates keep each crystal attached while the shared mesh dents.
    frostPosition = inPosition;
    frostRestNormal = inNormal;
    frostNormal = normal;
    frostUv = inTexCoord;
}

// Derived from jMonkeyEngine 3.8.1-stable, BSD-3-Clause. See licenses/jme-BSD3.txt.
#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/Instancing.glsllib"
#import "Common/ShaderLib/Skinning.glsllib"
#import "materials/VehicleMorph.glsllib"

attribute vec3 inPosition;
attribute vec2 inTexCoord;

varying vec2 texCoord;

void main(){
    vec4 modelSpacePos = vec4(inPosition, 1.0);

   #ifdef NUM_MORPH_TARGETS
           Morph_Compute(modelSpacePos);
   #endif

   #ifdef NUM_BONES
       Skinning_Compute(modelSpacePos);
   #endif
    gl_Position = TransformWorldViewProjection(modelSpacePos);
    texCoord = inTexCoord;
}
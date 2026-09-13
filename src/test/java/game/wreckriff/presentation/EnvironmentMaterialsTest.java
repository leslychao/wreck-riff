package game.wreckriff.presentation;

import com.jme3.texture.Texture;
import com.jme3.texture.image.ColorSpace;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.extension.ExtendWith(PresentationTestAssets.class)
class EnvironmentMaterialsTest {
    @Test void stagedEnvironmentDiffuseUsesPreparedMipsAndOneOfflineFlip() {
        var textures=SurfaceMaterials.texturesFor(java.util.List.of("asphalt","blue","earth","wood"));
        for(var use:textures) {
            boolean diffuse=use.parameter().equals("DiffuseMap");
            assertEquals(diffuse,use.path().endsWith(".dds"));
            assertEquals(!diffuse,use.key().isFlipY(),"BC7 blocks have already been flipped offline");
            assertEquals(!diffuse,use.key().isGenerateMips(),"BC7 keeps its complete authored mip chain");
        }
    }
    @Test void capturedBrickAndTimberRetainMetreScaleInsteadOfBuildingScaleUvs() {
        assertEquals(1,SurfaceMaterials.metresPerTile("brick"));assertEquals(1.5f,SurfaceMaterials.metresPerTile("wood"));
        assertEquals(1.3f,SurfaceMaterials.metresPerTile("earth"));assertEquals(2,SurfaceMaterials.metresPerTile("district-earth"));
        assertEquals(2.51f,SurfaceMaterials.metresPerTile("grass"));assertEquals(2.51f,SurfaceMaterials.metresPerTile("district-garden"));
        assertEquals(1.8f,SurfaceMaterials.metresPerTile("cast-concrete"));assertEquals(1.8f,SurfaceMaterials.metresPerTile("park-paving"));
        assertEquals(2,SurfaceMaterials.metresPerTile("road-surface"));assertEquals(2,SurfaceMaterials.metresPerTile("road-wet"));
        assertEquals(4,SurfaceMaterials.metresPerTile("road-patch"));
    }
    @Test void naturalAndArchitecturalSurfacesUseTheirOwnLocalPhotographicMaterial() {
        var recipes=Map.ofEntries(Map.entry("grass","grass_ground"),Map.entry("district-garden","grass_ground"),
                Map.entry("park-ground","grass_ground"),Map.entry("park-leaf","leafy_grass"),Map.entry("earth","brown_mud"),
                Map.entry("district-earth","dirt"),Map.entry("gravel","gravelly_sand"),Map.entry("brick","red_brick_03"),
                Map.entry("wood","wood_planks_grey"),Map.entry("cast-concrete","concrete_wall_009"),Map.entry("park-paving","concrete_wall_009"),
                Map.entry("road-surface","asphalt_pit_lane"),Map.entry("road-wet","asphalt_pit_lane"),Map.entry("road-patch","asphalt_02"));
        var materials=new SurfaceMaterials(PresentationTestAssets.shared());
        for(var recipe:recipes.entrySet()) {
            var material=materials.material(recipe.getKey());
            for(String parameter:new String[]{"DiffuseMap","NormalMap","SpecularMap"}) {
                Texture texture=(Texture)material.getParam(parameter).getValue();
                assertTrue(texture.getKey().getName().contains(recipe.getValue()));
                assertEquals(2048,texture.getImage().getWidth());assertEquals(2048,texture.getImage().getHeight());
                assertEquals(Texture.MinFilter.Trilinear,texture.getMinFilter());assertEquals(8,texture.getAnisotropicFilter());
                boolean losslessDiffuse=parameter.equals("DiffuseMap")&&recipe.getValue().equals("leafy_grass");
                assertEquals(losslessDiffuse?ColorSpace.sRGB:ColorSpace.Linear,texture.getImage().getColorSpace(),recipe.getKey()+" "+parameter);
                if(parameter.equals("DiffuseMap")&&!losslessDiffuse)assertEquals(com.jme3.texture.Image.Format.BC7_UNORM_SRGB,texture.getImage().getFormat());
                if(losslessDiffuse){assertEquals("textures/materials/leafy_grass/diffuse.png",texture.getKey().getName());assertNotEquals(com.jme3.texture.Image.Format.BC7_UNORM_SRGB,texture.getImage().getFormat());}
            }
        }
    }
    @Test void compressedEnvironmentDefinitionDoesNotChangeThePngPaintColourContract() {
        var materials=new SurfaceMaterials(PresentationTestAssets.shared());
        var environment=materials.material("asphalt");var paint=materials.material("yellow");
        assertNotSame(environment.getMaterialDef(),paint.getMaterialDef());
        assertEquals(ColorSpace.Linear,environment.getTextureParam("DiffuseMap").getTextureValue().getImage().getColorSpace());
        assertEquals(ColorSpace.sRGB,paint.getTextureParam("DiffuseMap").getTextureValue().getImage().getColorSpace());
        assertEquals(ColorSpace.Linear,((com.jme3.material.MatParamTexture)environment.getMaterialDef().getMaterialParam("DiffuseMap")).getColorSpace());
        // Pinned Phong leaves the colour-map declaration unspecified and preserves the PNG image's sRGB.
        assertNull(((com.jme3.material.MatParamTexture)paint.getMaterialDef().getMaterialParam("DiffuseMap")).getColorSpace());
    }
}

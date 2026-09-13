package game.wreckriff.presentation;

import com.jme3.texture.Texture;
import com.jme3.texture.image.ColorSpace;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EnvironmentMaterialsTest {
    @Test void capturedBrickAndTimberRetainMetreScaleInsteadOfBuildingScaleUvs() {
        assertEquals(1,SurfaceMaterials.metresPerTile("brick"));assertEquals(1.5f,SurfaceMaterials.metresPerTile("wood"));
        assertEquals(1.3f,SurfaceMaterials.metresPerTile("earth"));assertEquals(2,SurfaceMaterials.metresPerTile("grass"));
        assertEquals(1.8f,SurfaceMaterials.metresPerTile("cast-concrete"));assertEquals(1.8f,SurfaceMaterials.metresPerTile("park-paving"));
    }
    @Test void naturalAndArchitecturalSurfacesUseTheirOwnLocalPhotographicMaterial() {
        var recipes=Map.of("grass","leafy_grass","earth","brown_mud","gravel","gravelly_sand","brick","red_brick_03","wood","wood_planks_grey",
                "cast-concrete","concrete_wall_009","park-paving","concrete_wall_009");
        var materials=new SurfaceMaterials(PresentationTestAssets.shared());
        for(var recipe:recipes.entrySet()) {
            var material=materials.material(recipe.getKey());
            for(String parameter:new String[]{"DiffuseMap","NormalMap","SpecularMap"}) {
                Texture texture=(Texture)material.getParam(parameter).getValue();
                assertTrue(texture.getKey().getName().contains(recipe.getValue()));
                assertEquals(2048,texture.getImage().getWidth());assertEquals(2048,texture.getImage().getHeight());
                assertEquals(Texture.MinFilter.Trilinear,texture.getMinFilter());assertEquals(8,texture.getAnisotropicFilter());
                assertEquals(parameter.equals("DiffuseMap")?ColorSpace.sRGB:ColorSpace.Linear,texture.getImage().getColorSpace());
            }
        }
    }
}

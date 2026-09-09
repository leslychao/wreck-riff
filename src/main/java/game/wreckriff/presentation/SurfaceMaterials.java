package game.wreckriff.presentation;

import com.jme3.asset.AssetManager;
import com.jme3.asset.TextureKey;
import com.jme3.material.Material;
import com.jme3.material.MatParamTexture;
import com.jme3.math.ColorRGBA;
import com.jme3.texture.Texture;
import com.jme3.texture.image.ColorSpace;
import java.util.HashMap;
import java.util.Map;

/** Local, lit materials; UV dimensions are authored in metres by SurfaceMesh. */
public final class SurfaceMaterials {
    private final AssetManager assets;
    private final Map<String,Material> cache=new HashMap<>();
    public SurfaceMaterials(AssetManager assets){this.assets=assets;}
    public Material material(String name) {
        return cache.computeIfAbsent(name,key->switch(key) {
            case "asphalt" -> textured("asphalt_02",new ColorRGBA(.8f,.81f,.83f,1),5,.11f);
            case "concrete" -> textured("cracked_concrete",new ColorRGBA(.84f,.83f,.78f,1),7,.12f);
            case "rust" -> textured("rusty_metal_03",new ColorRGBA(.76f,.65f,.53f,1),18,.32f);
            case "steel" -> textured("metal_plate_02",new ColorRGBA(.68f,.73f,.78f,1),38,.5f);
            case "blue" -> textured("blue_metal_plate",new ColorRGBA(.54f,.65f,.76f,1),24,.35f);
            case "yellow" -> paint(new ColorRGBA(.95f,.64f,.14f,1));
            case "red" -> paint(new ColorRGBA(.75f,.10f,.065f,1));
            case "cyan" -> paint(new ColorRGBA(.035f,.58f,.69f,1));
            case "repair" -> paint(new ColorRGBA(.17f,.8f,.24f,1));
            case "ivory" -> paint(new ColorRGBA(.84f,.82f,.7f,1));
            case "black" -> textured("metal_plate_02",new ColorRGBA(.09f,.10f,.12f,1),16,.22f);
            // Fresh tar uses the same local grain as the road. Untextured .15 linear grey
            // becomes a conspicuously bright plate after gamma correction in the real renderer.
            case "road-patch" -> textured("asphalt_02",new ColorRGBA(.19f,.20f,.21f,1),3,.018f);
            case "road-wet" -> textured("asphalt_02",new ColorRGBA(.13f,.18f,.22f,1),88,.44f);
            case "roof-seam" -> lit(assets,new ColorRGBA(.022f,.026f,.031f,1),3,.01f);
            case "lane-paint" -> lit(assets,new ColorRGBA(.46f,.34f,.16f,1),4,.03f);
            case "stone" -> textured("cracked_concrete",new ColorRGBA(.53f,.58f,.60f,1),9,.09f);
            case "dark-concrete" -> textured("cracked_concrete",new ColorRGBA(.34f,.39f,.44f,1),6,.08f);
            case "purple" -> paint(new ColorRGBA(.44f,.12f,.38f,1));
            case "faded-red" -> paint(new ColorRGBA(.42f,.19f,.17f,1));
            case "light-amber" -> emissive(new ColorRGBA(1,.57f,.16f,1));
            case "light-cyan" -> emissive(new ColorRGBA(.13f,.80f,.92f,1));
            case "light-magenta" -> emissive(new ColorRGBA(.88f,.19f,.55f,1));
            case "light-white" -> emissive(new ColorRGBA(.72f,.84f,1,1));
            default -> throw new IllegalArgumentException("Unknown material "+key);
        });
    }
    public Material paint(ColorRGBA color) {
        Material result=lit(assets,color,32,.4f);
        result.setTexture("DiffuseMap",texture("textures/vehicle/paint.png",true));
        // Paint sits on formed panels, not on the deep diamond-plate normal of structural metal.
        result.setTexture("SpecularMap",texture("textures/materials/blue_metal_plate/specular.png",false));
        return result;
    }
    public Material emissive(ColorRGBA color) {
        Material result=new Material(assets,"Common/MatDefs/Misc/Unshaded.j3md");
        result.setColor("Color",color);result.setColor("GlowColor",color.mult(.55f));return result;
    }
    public Material rubber() {
        Material result=lit(assets,new ColorRGBA(.38f,.39f,.41f,1),8,.08f);
        result.setTexture("DiffuseMap",texture("textures/vehicle/rubber.png",true));return result;
    }
    public Material textured(String source,ColorRGBA tint,float shininess,float specular) {
        Material result=lit(assets,tint,shininess,specular);
        String path="textures/materials/"+source+"/";
        result.setTexture("DiffuseMap",texture(path+"diffuse.png",true));
        result.setTexture("NormalMap",texture(path+"normal.png",false));
        result.setTexture("SpecularMap",texture(path+"specular.png",false));
        return result;
    }
    private Texture texture(String path,boolean color) {
        TextureKey key=new TextureKey(path,true);key.setGenerateMips(true);
        Texture texture=assets.loadTexture(key);texture.setWrap(Texture.WrapMode.Repeat);
        texture.setMinFilter(Texture.MinFilter.Trilinear);texture.setMagFilter(Texture.MagFilter.Bilinear);
        // The renderer clamps this requested level to the device's supported maximum.
        texture.setAnisotropicFilter(8);texture.getImage().setColorSpace(color?ColorSpace.sRGB:ColorSpace.Linear);
        return texture;
    }
    public static Material lit(AssetManager assets,ColorRGBA color,float shininess,float specular) {
        Material result=new Material(assets,"Common/MatDefs/Light/Lighting.j3md");
        // This application supplies scalar roughness-derived specular masks, never sRGB specular colours.
        // Declare that contract on the shared Phong definition before any texture is assigned, so jME
        // validates the map as data instead of converting it to its default colour-map interpretation.
        ((MatParamTexture)result.getMaterialDef().getMaterialParam("SpecularMap")).setColorSpace(ColorSpace.Linear);
        result.setFloat("NormalType",1); // Bundled maps use the OpenGL (+Y), not Phong's default DirectX convention.
        result.setBoolean("UseMaterialColors",true);result.setColor("Diffuse",color);
        result.setColor("Ambient",color);result.setColor("Specular",new ColorRGBA(specular,specular,specular,1));
        result.setFloat("Shininess",shininess);return result;
    }
}

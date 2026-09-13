package game.wreckriff.presentation;

import com.jme3.asset.AssetManager;
import com.jme3.asset.TextureKey;
import com.jme3.material.Material;
import com.jme3.material.MaterialDef;
import com.jme3.material.MatParamTexture;
import com.jme3.math.ColorRGBA;
import com.jme3.texture.Texture;
import com.jme3.texture.image.ColorSpace;
import java.util.HashMap;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Local, lit materials; UV dimensions are authored in metres by SurfaceMesh. */
public final class SurfaceMaterials {
    /** The same key and sampler configuration is used by staged decoding and material construction. */
    public record TextureUse(String parameter,String path,boolean color) {
        public TextureKey key() {boolean prepared=path.endsWith(".dds");TextureKey key=new TextureKey(path,!prepared);key.setGenerateMips(!prepared);return key;}
        public Texture load(AssetManager assets) {
            if(path.endsWith(".dds"))return EnvironmentDiffuseDds.load(assets,path);
            Texture texture=assets.loadTexture(key());texture.setWrap(Texture.WrapMode.Repeat);
            texture.setMinFilter(Texture.MinFilter.Trilinear);texture.setMagFilter(Texture.MagFilter.Bilinear);
            texture.setAnisotropicFilter(8);texture.getImage().setColorSpace(color?ColorSpace.sRGB:ColorSpace.Linear);
            return texture;
        }
    }
    private record Recipe(ColorRGBA color,float shininess,float specular,boolean emissive,List<TextureUse> textures) { }
    private final AssetManager assets;
    private final Map<String,Material> cache=new HashMap<>();
    public SurfaceMaterials(AssetManager assets){this.assets=assets;}
    /** Physical capture scale for the local material, shared by architecture and road meshes. */
    public static float metresPerTile(String name) {
        return switch(name) {
            case "cast-concrete", "park-paving" -> 1.8f;
            case "road-surface", "road-wet" -> 2;
            case "brick" -> 1;
            case "earth" -> 1.3f;
            case "district-earth" -> 2;
            case "grass", "park-ground", "district-garden" -> 2.51f;
            case "wood" -> 1.5f;
            case "asphalt", "concrete", "road-patch" -> 4;
            case "rust", "blue", "steel", "black" -> 3;
            default -> 2;
        };
    }
    public Material material(String name) {
        return cache.computeIfAbsent(name,key->create(recipe(key)));
    }
    public static List<TextureUse> texturesFor(Collection<String> materials) {
        var textures=new LinkedHashSet<TextureUse>();
        for(String material:materials)textures.addAll(recipe(material).textures);
        return List.copyOf(textures);
    }
    private static Recipe recipe(String key) {
        return switch(key) {
            case "asphalt" -> texturedRecipe("asphalt_02",new ColorRGBA(.8f,.81f,.83f,1),5,.11f);
            case "concrete" -> texturedRecipe("cracked_concrete",new ColorRGBA(.84f,.83f,.78f,1),7,.12f);
            case "cast-concrete" -> texturedRecipe("concrete_wall_009",new ColorRGBA(.86f,.85f,.82f,1),6,.055f);
            case "park-paving" -> texturedRecipe("concrete_wall_009",new ColorRGBA(.87f,.84f,.78f,1),6,.05f);
            case "rust" -> texturedRecipe("rusty_metal_03",new ColorRGBA(.76f,.65f,.53f,1),18,.32f);
            case "steel" -> texturedRecipe("metal_plate_02",new ColorRGBA(.68f,.73f,.78f,1),38,.5f);
            case "blue" -> texturedRecipe("blue_metal_plate",new ColorRGBA(.54f,.65f,.76f,1),24,.35f);
            // Trampled ridges remain local; repeating them over an entire kilometre
            // produces aligned stripes at grazing angles even with correct mipmaps.
            case "earth" -> texturedRecipe("brown_mud",new ColorRGBA(.82f,.77f,.69f,1),3,.035f);
            case "district-earth" -> texturedRecipe("dirt",new ColorRGBA(.82f,.77f,.69f,1),3,.035f);
            case "gravel", "road-shoulder" -> texturedRecipe("gravelly_sand",new ColorRGBA(.76f,.73f,.67f,1),3,.04f);
            case "grass", "park-ground", "district-garden" -> texturedRecipe("grass_ground",new ColorRGBA(.80f,.87f,.74f,1),3,.025f);
            case "park-leaf" -> texturedRecipe("leafy_grass",new ColorRGBA(.44f,.61f,.31f,1),3,.025f);
            case "brick" -> texturedRecipe("red_brick_03",new ColorRGBA(.82f,.79f,.75f,1),5,.05f);
            case "wood" -> texturedRecipe("wood_planks_grey",new ColorRGBA(.79f,.70f,.58f,1),8,.08f);
            case "glass" -> new Recipe(new ColorRGBA(.12f,.23f,.29f,1),70,.55f,false,List.of());
            case "rubber" -> new Recipe(new ColorRGBA(.30f,.31f,.33f,1),8,.06f,false,
                    List.of(new TextureUse("DiffuseMap","textures/vehicle/rubber.png",true)));
            case "yellow" -> paintRecipe(new ColorRGBA(.95f,.64f,.14f,1));
            case "red" -> paintRecipe(new ColorRGBA(.75f,.10f,.065f,1));
            case "cyan" -> paintRecipe(new ColorRGBA(.035f,.58f,.69f,1));
            case "repair" -> paintRecipe(new ColorRGBA(.17f,.8f,.24f,1));
            case "ivory" -> paintRecipe(new ColorRGBA(.84f,.82f,.7f,1));
            case "black" -> texturedRecipe("metal_plate_02",new ColorRGBA(.09f,.10f,.12f,1),16,.22f);
            // Local worn repair patches retain the preserved cracked source.
            case "road-patch" -> texturedRecipe("asphalt_02",new ColorRGBA(.19f,.20f,.21f,1),3,.018f);
            case "road-wet" -> texturedRecipe("asphalt_pit_lane",new ColorRGBA(.26f,.43f,.59f,1),88,.44f);
            // A two-metre photographed aggregate avoids repeating a large fracture on
            // every road tile. The tint keeps the established mean linear road luminance.
            case "road-surface" -> texturedRecipe("asphalt_pit_lane",new ColorRGBA(.60f,.72f,.86f,1),5,.07f);
            case "road-marking" -> new Recipe(new ColorRGBA(.70f,.65f,.46f,1),3,.025f,false,List.of());
            case "district-slate" -> texturedRecipe("cracked_concrete",new ColorRGBA(.32f,.39f,.47f,1),7,.08f);
            case "district-warm" -> texturedRecipe("cracked_concrete",new ColorRGBA(.58f,.44f,.29f,1),6,.055f);
            case "district-service" -> texturedRecipe("cracked_concrete",new ColorRGBA(.42f,.43f,.40f,1),5,.04f);
            case "district-fair" -> texturedRecipe("cracked_concrete",new ColorRGBA(.46f,.25f,.20f,1),5,.05f);
            case "roof-seam" -> new Recipe(new ColorRGBA(.022f,.026f,.031f,1),3,.01f,false,List.of());
            case "lane-paint" -> new Recipe(new ColorRGBA(.46f,.34f,.16f,1),4,.03f,false,List.of());
            case "stone" -> texturedRecipe("cracked_concrete",new ColorRGBA(.53f,.58f,.60f,1),9,.09f);
            case "dark-concrete" -> texturedRecipe("cracked_concrete",new ColorRGBA(.34f,.39f,.44f,1),6,.08f);
            case "purple" -> paintRecipe(new ColorRGBA(.44f,.12f,.38f,1));
            case "faded-red" -> paintRecipe(new ColorRGBA(.42f,.19f,.17f,1));
            case "light-amber" -> emissiveRecipe(new ColorRGBA(1,.57f,.16f,1));
            case "light-cyan" -> emissiveRecipe(new ColorRGBA(.13f,.80f,.92f,1));
            case "light-magenta" -> emissiveRecipe(new ColorRGBA(.88f,.19f,.55f,1));
            case "light-white" -> emissiveRecipe(new ColorRGBA(.72f,.84f,1,1));
            default -> throw new IllegalArgumentException("Unknown material "+key);
        };
    }
    public Material paint(ColorRGBA color) {return create(paintRecipe(color));}
    private static Recipe paintRecipe(ColorRGBA color) {
        // Paint sits on formed panels, not on the deep diamond-plate normal of structural metal.
        return new Recipe(color,32,.4f,false,List.of(new TextureUse("DiffuseMap","textures/vehicle/paint.png",true),
                new TextureUse("SpecularMap","textures/materials/blue_metal_plate/specular.png",false)));
    }
    public Material emissive(ColorRGBA color) {return create(emissiveRecipe(color));}
    private static Recipe emissiveRecipe(ColorRGBA color) {return new Recipe(color,0,0,true,List.of());}
    public Material rubber() {
        return create(new Recipe(new ColorRGBA(.38f,.39f,.41f,1),8,.08f,false,
                List.of(new TextureUse("DiffuseMap","textures/vehicle/rubber.png",true))));
    }
    public Material textured(String source,ColorRGBA tint,float shininess,float specular) {
        return create(texturedRecipe(source,tint,shininess,specular));
    }
    private static Recipe texturedRecipe(String source,ColorRGBA tint,float shininess,float specular) {
        String path="textures/materials/"+source+"/";
        return new Recipe(tint,shininess,specular,false,List.of(new TextureUse("DiffuseMap",path+(path.contains("/leafy_grass/")?"diffuse.png":"diffuse.dds"),true),
                new TextureUse("NormalMap",path+"normal.png",false),new TextureUse("SpecularMap",path+"specular.png",false)));
    }
    private Material create(Recipe recipe) {
        if(recipe.emissive) {
            Material result=new Material(assets,"Common/MatDefs/Misc/Unshaded.j3md");
            result.setColor("Color",recipe.color);result.setColor("GlowColor",recipe.color.mult(.55f));return result;
        }
        boolean compressed=recipe.textures.stream().anyMatch(texture->texture.parameter.equals("DiffuseMap")&&texture.path.endsWith(".dds"));
        Material result=lit(assets,recipe.color,recipe.shininess,recipe.specular,compressed);
        for(TextureUse texture:recipe.textures)result.setTexture(texture.parameter,texture.load(assets));
        return result;
    }
    public static Material lit(AssetManager assets,ColorRGBA color,float shininess,float specular) {
        return lit(assets,color,shininess,specular,false);
    }
    private static Material lit(AssetManager assets,ColorRGBA color,float shininess,float specular,boolean compressed) {
        // The explicit BC7 sRGB image format performs GPU decoding itself. Keep its
        // parameter declaration separate from ordinary sRGB PNG paint/rubber maps.
        Material result=compressed?new Material(assets,"materials/EnvironmentLighting.j3md"):new Material(lightingDefinition(assets));
        result.setFloat("NormalType",1); // Bundled maps use the OpenGL (+Y), not Phong's default DirectX convention.
        result.setBoolean("UseMaterialColors",true);result.setColor("Diffuse",color);
        result.setColor("Ambient",color);result.setColor("Specular",new ColorRGBA(specular,specular,specular,1));
        result.setFloat("Shininess",shininess);return result;
    }
    /** Prepare before importing j3o materials too: binary import applies the material definition's colour-space contract. */
    public static MaterialDef lightingDefinition(AssetManager assets) {
        MaterialDef definition=(MaterialDef)assets.loadAsset("Common/MatDefs/Light/Lighting.j3md");
        // This application supplies scalar roughness-derived specular masks, never sRGB specular colours.
        // Declare that contract on the shared Phong definition before any texture is assigned, so jME
        // validates the map as data instead of converting it to its default colour-map interpretation.
        ((MatParamTexture)definition.getMaterialParam("SpecularMap")).setColorSpace(ColorSpace.Linear);
        return definition;
    }
}

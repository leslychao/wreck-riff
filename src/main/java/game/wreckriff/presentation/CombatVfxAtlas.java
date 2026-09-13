package game.wreckriff.presentation;

import com.google.gson.*;
import com.jme3.asset.AssetManager;
import com.jme3.asset.TextureKey;
import com.jme3.material.Material;
import com.jme3.texture.Texture;
import com.jme3.texture.image.ColorSpace;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Locally baked original volume frames, shared by all particles and all matches. */
public final class CombatVfxAtlas {
    public record Explosion(float scale,float flashSeconds,float flameSeconds,float smokeSeconds,int flames,int fragments,int dust) {}
    private final Map<String,Explosion> explosions;
    private final Map<String,Texture> textures;
    private CombatVfxAtlas(Map<String,Explosion> explosions,Map<String,Texture> textures) {
        this.explosions=Map.copyOf(explosions);this.textures=Map.copyOf(textures);
    }
    public static CombatVfxAtlas load(AssetManager assets) {
        JsonObject recipes;
        try(var stream=CombatVfxAtlas.class.getResourceAsStream("/vfx/recipes.json")) {
            if(stream==null)throw new IllegalStateException("Missing offline VFX bake: vfx/recipes.json");
            recipes=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
        } catch(IOException exception) {throw new IllegalStateException("Cannot read VFX recipes",exception);}
        if(recipes.get("schemaVersion").getAsInt()!=1||recipes.get("frames").getAsInt()!=64||recipes.get("grid").getAsInt()!=8
                ||recipes.get("blastVariants").getAsInt()!=3||recipes.get("blastFramesPerVariant").getAsInt()!=21)
            throw new IllegalStateException("Unsupported VFX atlas layout");
        var definitions=new LinkedHashMap<String,Explosion>();
        for(var entry:recipes.getAsJsonObject("explosions").entrySet()) {
            var a=entry.getValue().getAsJsonArray();
            Explosion e=new Explosion(a.get(0).getAsFloat(),a.get(1).getAsFloat(),a.get(2).getAsFloat(),a.get(3).getAsFloat(),
                    a.get(4).getAsInt(),a.get(5).getAsInt(),a.get(6).getAsInt());
            if(e.scale<=0||e.flashSeconds<=0||e.flashSeconds>.11f||e.smokeSeconds<=e.flameSeconds||e.flames<1||e.fragments<1||e.dust<1)
                throw new IllegalStateException("Invalid explosion recipe: "+entry.getKey());
            definitions.put(entry.getKey(),e);
        }
        var textures=new LinkedHashMap<String,Texture>();
        for(String name:List.of("smoke","flame","blast","dust","auxiliary")) {
            TextureKey key=new TextureKey("textures/vfx/"+name+".png",false);key.setGenerateMips(true);
            Texture texture=assets.loadTexture(key);
            texture.setMinFilter(Texture.MinFilter.Trilinear);texture.setMagFilter(Texture.MagFilter.Bilinear);
            texture.setWrap(Texture.WrapMode.EdgeClamp);texture.getImage().setColorSpace(ColorSpace.sRGB);
            textures.put(name,texture);
        }
        return new CombatVfxAtlas(definitions,textures);
    }
    public Explosion explosion(String kind) {
        Explosion result=explosions.get(kind);
        if(result==null)throw new IllegalArgumentException("Unknown explosion recipe: "+kind);
        return result;
    }
    public void bind(Material material) {
        textures.forEach((name,texture)->material.setTexture(Character.toUpperCase(name.charAt(0))+name.substring(1)+"Atlas",texture));
    }
    public long residentBytes() {
        long bytes=0;
        for(Texture texture:textures.values()) {
            int width=texture.getImage().getWidth(),height=texture.getImage().getHeight();
            do {bytes+=(long)width*height*4;width=Math.max(1,width/2);height=Math.max(1,height/2);}while(width>1||height>1);
            bytes+=4;
        }
        return bytes;
    }
    static float frame(float age,float lifetime) {return Math.clamp(age/Math.max(.001f,lifetime),0,1)*63;}
    static float blastFrame(float age,float lifetime,float variation) {
        return Math.clamp((int)(variation*3),0,2)*21+Math.clamp(age/Math.max(.001f,lifetime),0,1)*20;
    }
}

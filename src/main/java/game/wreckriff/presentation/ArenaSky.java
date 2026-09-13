package game.wreckriff.presentation;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Sphere;
import game.wreckriff.arena.ArenaDefinition.Theme;

/** Original, local atmospheric backdrop. One shared sky draw, no physics or image downloads. */
public final class ArenaSky {
    private ArenaSky() {}
    public static Geometry create(AssetManager assets,Theme theme) {
        Material material=new Material(assets,"materials/ArenaSky.j3md");
        material.setVector3("NormalScale",new Vector3f(1,1,1));
        material.setVector3("SunDirection",new Vector3f(.65f,.16f,-.7f).normalizeLocal());
        switch(theme) {
            case CONSTRUCTION -> set(material,c(.43f,.47f,.49f),c(.17f,.23f,.29f),c(.55f,.57f,.56f),c(.65f,.62f,.55f),.80f);
            case NEON -> set(material,c(.12f,.13f,.23f),c(.012f,.023f,.065f),c(.09f,.12f,.20f),c(.30f,.15f,.10f),.36f);
            case CARNIVAL -> set(material,c(.42f,.20f,.12f),c(.035f,.047f,.12f),c(.34f,.23f,.26f),c(.85f,.38f,.12f),.48f);
            default -> throw new IllegalArgumentException("The classic yard retains its original backdrop");
        }
        Geometry sky=new Geometry("authored-atmosphere",new Sphere(16,32,10));sky.setMaterial(material);
        sky.setQueueBucket(RenderQueue.Bucket.Sky);sky.setCullHint(Spatial.CullHint.Never);
        sky.setShadowMode(RenderQueue.ShadowMode.Off);return sky;
    }
    private static void set(Material material,ColorRGBA horizon,ColorRGBA zenith,ColorRGBA cloud,ColorRGBA sun,float amount) {
        material.setColor("HorizonColor",horizon);material.setColor("ZenithColor",zenith);
        material.setColor("CloudColor",cloud);material.setColor("SunColor",sun);material.setFloat("CloudAmount",amount);
    }
    private static ColorRGBA c(float r,float g,float b){return new ColorRGBA(r,g,b,1);}
}

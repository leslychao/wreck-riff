package game.wreckriff.presentation;

import com.jme3.math.ColorRGBA;
import com.jme3.asset.AssetManager;
import com.jme3.scene.Node;
import java.util.List;

/** Presentation recipes only. Dimensions retain the established visible in-flight envelopes. */
public enum OrdnanceStyle {
    HOMING("homing",-.35f,new ColorRGBA(1,.42f,.07f,1)),
    POWER("power",-.50f,new ColorRGBA(1,.28f,.035f,1)),
    NAPALM("napalm",-.28f,new ColorRGBA(1,.52f,.08f,1)),
    BALLISTIC("ballistic",-.85f,new ColorRGBA(1,.47f,.12f,1)),
    BALLISTIC_FALL("ballistic-fall",-.42f,new ColorRGBA(1,.4f,.07f,1)),
    CANNON("cannon",-.25f,null),
    FREEZE("freeze",-.24f,new ColorRGBA(.1f,.85f,1,1)),
    MINE("mine",0,new ColorRGBA(1,.16f,.045f,1));

    private final String kind;
    private final float nozzleZ;
    private final ColorRGBA signal;
    OrdnanceStyle(String kind,float nozzleZ,ColorRGBA signal) {this.kind=kind;this.nozzleZ=nozzleZ;this.signal=signal;}
    public String kind(){return kind;}
    public float nozzleZ(){return nozzleZ;}
    public ColorRGBA signal(){return signal==null?null:signal.clone();}
    public String model(){return "models/ordnance/"+kind+".j3o";}
    /** Binary import must share the game's linear scalar-specular convention, including during staged loading. */
    public Node load(AssetManager assets) {
        SurfaceMaterials.lightingDefinition(assets);
        return (Node)assets.loadModel(model());
    }
    public String atlas(){return this==MINE?"mines":"projectiles";}
    public static OrdnanceStyle of(String kind) {
        for(var style:values())if(style.kind.equals(kind))return style;
        throw new IllegalArgumentException("Unknown ordnance kind "+kind);
    }
    public static List<SurfaceMaterials.TextureUse> textures(String atlas) {
        String prefix="textures/ordnance/"+atlas+"-";
        return List.of(new SurfaceMaterials.TextureUse("DiffuseMap",prefix+"diffuse.png",true),
                new SurfaceMaterials.TextureUse("NormalMap",prefix+"normal.png",false),
                new SurfaceMaterials.TextureUse("SpecularMap",prefix+"specular.png",false));
    }
}

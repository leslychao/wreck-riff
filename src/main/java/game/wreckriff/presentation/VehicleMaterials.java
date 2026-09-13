package game.wreckriff.presentation;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;

/** Prepared original vehicle textures use the engine's Phong lighting and native morph/shadow techniques. */
public final class VehicleMaterials {
    private VehicleMaterials() {}
    public static Material create(AssetManager assets,String profile,String part) {
        if(part.equals("headlights")||part.equals("taillights")||part.equals("service-core")) {
            var m=new Material(assets,"Common/MatDefs/Misc/Unshaded.j3md");ColorRGBA c=part.equals("taillights")?new ColorRGBA(1,.07f,.025f,1):part.equals("service-core")?new ColorRGBA(.08f,.9f,.8f,1):new ColorRGBA(1,.78f,.4f,1);
            m.setColor("Color",c);m.setColor("GlowColor",c.mult(.32f));m.setBoolean("VertexColor",true);return m;
        }
        Material material=new Material(assets,"materials/VehicleLighting.j3md");
        boolean glass=part.equals("glass"),rubber=part.equals("rubber-trim"),paint=part.equals("paint")||part.startsWith("panel-");
        ColorRGBA color=glass?new ColorRGBA(.07f,.16f,.21f,1):rubber?new ColorRGBA(.075f,.08f,.085f,1):paint?ColorRGBA.White:new ColorRGBA(.49f,.52f,.55f,1);
        material.setBoolean("UseMaterialColors",true);material.setColor("Diffuse",color);material.setColor("Ambient",color);
        material.setColor("Specular",new ColorRGBA(glass?.9f:rubber?.07f:.55f,glass?.9f:rubber?.07f:.55f,glass?.9f:rubber?.07f:.55f,1));
        material.setFloat("Shininess",glass?100:rubber?5:40);material.setFloat("NormalType",1);
        for(String type:new String[]{"diffuse","normal","specular"}) {
            String parameter=switch(type){case "diffuse"->"DiffuseMap";case "normal"->"NormalMap";default->"SpecularMap";};
            if(type.equals("diffuse")&&!paint)continue;
            material.setTexture(parameter,new SurfaceMaterials.TextureUse(parameter,"textures/vehicles/"+profile+"/"+type+".png",type.equals("diffuse")).load(assets));
        }
        material.setFloat("Damage",0);material.setFloat("Glass",glass?1:0);return material;
    }
}

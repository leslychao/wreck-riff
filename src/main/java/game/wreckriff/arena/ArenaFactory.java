package game.wreckriff.arena;

import com.jme3.asset.AssetManager;
import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.bullet.collision.shapes.MeshCollisionShape;
import com.jme3.material.Material;
import com.jme3.math.*;
import com.jme3.scene.*;
import com.jme3.scene.shape.*;
import com.jme3.util.BufferUtils;
import java.util.*;

/** Original, parameter-driven industrial yard. Every solid uses the rendered geometry. */
public final class ArenaFactory {
    private final AssetManager assets;
    private final Map<String,Material> materials=new HashMap<>();
    public ArenaFactory(AssetManager assets) { this.assets=Objects.requireNonNull(assets); }

    public ArenaContent build(ArenaDefinition definition) {
        Node root=new Node("Dead Air Yard");
        List<ArenaContent.StaticBody> bodies=new ArrayList<>();
        for (var part:definition.boxes()) {
            Vector3f half=part.size().vector().mult(.5f);
            Geometry visual=new Geometry(part.id(),new Box(half.x,half.y,half.z));
            visual.setLocalTranslation(part.center().vector());
            visual.setMaterial(material(part.material())); root.attachChild(visual);
            if (part.collision()) bodies.add(new ArenaContent.StaticBody(part.id(),new BoxCollisionShape(half),
                    part.center().vector(),new Quaternion()));
        }
        for (var ramp:definition.ramps()) {
            Mesh mesh=rampMesh(ramp);
            Geometry visual=new Geometry(ramp.id(),mesh); visual.setMaterial(material(ramp.material()));
            root.attachChild(visual);
            // The same exact top vertices provide both ramp contacts and visible seam.
            bodies.add(new ArenaContent.StaticBody(ramp.id(),new MeshCollisionShape(mesh),new Vector3f(),new Quaternion()));
        }
        addDecoration(root,definition);
        return new ArenaContent(root,bodies,definition.spawns(),definition.pickups(),new NavGraph(definition));
    }
    public Material material(String name) {
        return materials.computeIfAbsent(name,key->{
            ColorRGBA color=switch(key) {
                case "rust" -> new ColorRGBA(.42f,.20f,.10f,1);
                case "concrete" -> new ColorRGBA(.32f,.34f,.35f,1);
                case "steel" -> new ColorRGBA(.20f,.25f,.29f,1);
                case "yellow" -> new ColorRGBA(.90f,.62f,.13f,1);
                case "red" -> new ColorRGBA(.72f,.12f,.08f,1);
                case "cyan" -> new ColorRGBA(.05f,.72f,.83f,1);
                case "repair" -> new ColorRGBA(.28f,.91f,.43f,1);
                case "ivory" -> new ColorRGBA(.69f,.66f,.54f,1);
                case "black" -> new ColorRGBA(.045f,.055f,.07f,1);
                case "asphalt" -> new ColorRGBA(.11f,.13f,.15f,1);
                default -> throw new IllegalArgumentException("Unknown arena material " + key);
            };
            Material result=new Material(assets,"Common/MatDefs/Light/Lighting.j3md");
            result.setBoolean("UseMaterialColors",true);
            result.setColor("Diffuse",color); result.setColor("Ambient",color.mult(.68f));
            result.setColor("Specular",new ColorRGBA(.15f,.15f,.15f,1)); result.setFloat("Shininess",8);
            return result;
        });
    }
    private static Mesh rampMesh(ArenaDefinition.Ramp ramp) {
        Vector3f[] v={new Vector3f(ramp.minX(),ramp.startY(),ramp.minZ()),
                new Vector3f(ramp.maxX(),ramp.startY(),ramp.minZ()),
                new Vector3f(ramp.maxX(),ramp.endY(),ramp.maxZ()),
                new Vector3f(ramp.minX(),ramp.endY(),ramp.maxZ()),
                new Vector3f(ramp.minX(),-.5f,ramp.minZ()),new Vector3f(ramp.maxX(),-.5f,ramp.minZ()),
                new Vector3f(ramp.maxX(),-.5f,ramp.maxZ()),new Vector3f(ramp.minX(),-.5f,ramp.maxZ())};
        int[] faces={0,3,2,0,2,1, 4,5,6,4,6,7, 0,1,5,0,5,4,
                1,2,6,1,6,5, 2,3,7,2,7,6, 3,0,4,3,4,7};
        float[] position=new float[faces.length*3],normals=new float[faces.length*3];
        int[] indices=new int[faces.length];
        for (int i=0;i<faces.length;i+=3) {
            Vector3f normal=v[faces[i+1]].subtract(v[faces[i]]).cross(v[faces[i+2]].subtract(v[faces[i]])).normalizeLocal();
            for (int j=0;j<3;j++) {
                int offset=(i+j)*3; Vector3f point=v[faces[i+j]];
                position[offset]=point.x; position[offset+1]=point.y; position[offset+2]=point.z;
                normals[offset]=normal.x; normals[offset+1]=normal.y; normals[offset+2]=normal.z; indices[i+j]=i+j;
            }
        }
        Mesh mesh=new Mesh();
        mesh.setBuffer(VertexBuffer.Type.Position,3,BufferUtils.createFloatBuffer(position));
        mesh.setBuffer(VertexBuffer.Type.Normal,3,BufferUtils.createFloatBuffer(normals));
        mesh.setBuffer(VertexBuffer.Type.Index,3,BufferUtils.createIntBuffer(indices));
        mesh.updateBound(); return mesh;
    }
    private void addDecoration(Node root,ArenaDefinition definition) {
        // Markings have no collision: these thin surfaces cannot turn into invisible curbs.
        for (int z=-60;z<=60;z+=12) {
            box(root,"west-lane-"+z,new Vector3f(-64,.008f,z),new Vector3f(.16f,.008f,2.2f),"ivory");
            box(root,"east-lane-"+z,new Vector3f(74,.008f,z),new Vector3f(.16f,.008f,2.2f),"ivory");
        }
        for (int x=-70;x<=70;x+=14) {
            box(root,"south-lane-"+x,new Vector3f(x,.008f,-59),new Vector3f(2.2f,.008f,.16f),"yellow");
            box(root,"north-lane-"+x,new Vector3f(x,.008f,59),new Vector3f(2.2f,.008f,.16f),"yellow");
        }
        for (int level=0;level<5;level++) {
            float y=14+level*4;
            for (int x:new int[]{-3,3}) for (int z:new int[]{-3,3})
                box(root,"mast-upright",new Vector3f(x,y,z),new Vector3f(.18f,2,.18f),"rust");
            box(root,"mast-cross-x",new Vector3f(0,y+1.8f,-3),new Vector3f(3.2f,.16f,.16f),"steel");
            box(root,"mast-cross-z",new Vector3f(-3,y+1.8f,0),new Vector3f(.16f,.16f,3.2f),"steel");
        }
        box(root,"antenna",new Vector3f(0,35,0),new Vector3f(.12f,3,.12f),"cyan");
        box(root,"dead-air-board",new Vector3f(0,8,-8.03f),new Vector3f(6.7f,1.6f,.08f),"black");
        // Original bar/equalizer emblem remains legible without dependence on a font.
        for (int i=0;i<9;i++) box(root,"signal-bars",new Vector3f(-5.2f+i*1.3f,7.7f,-8.16f),
                new Vector3f(.30f,.28f+Math.abs(4-i)*.17f,.05f),i%2==0?"yellow":"cyan");
        for (int x:new int[]{-57,-31}) {
            box(root,"garage-speaker",new Vector3f(x,3,33.45f),new Vector3f(1.8f,1.8f,.20f),"black");
            for (int y:new int[]{2,4}) {
                Geometry speaker=new Geometry("speaker-cone",new Cylinder(2,16,.65f,.10f,true));
                speaker.setLocalTranslation(x,y,33.17f); speaker.setMaterial(material("steel")); root.attachChild(speaker);
            }
        }
        var hazard=definition.hazard();
        box(root,"hazard-surface",new Vector3f((hazard.minX()+hazard.maxX())*.5f,.012f,
                (hazard.minZ()+hazard.maxZ())*.5f),new Vector3f((hazard.maxX()-hazard.minX())*.5f,.012f,
                (hazard.maxZ()-hazard.minZ())*.5f),"black");
        for (int x=-13;x<=13;x+=2) box(root,"hazard-stripe",new Vector3f(x,.03f,-20),new Vector3f(.32f,.02f,3.8f),"yellow");
        for (var pickup:definition.pickups()) {
            Geometry stand=new Geometry("stand-"+pickup.id(),new Cylinder(2,16,1.2f,.12f,true));
            stand.rotate(FastMath.HALF_PI,0,0); stand.setLocalTranslation(pickup.position().vector().add(0,.06f,0));
            stand.setMaterial(material("steel")); root.attachChild(stand);
            Node item=new Node("pickup-"+pickup.id());item.setLocalTranslation(pickup.position().vector().add(0,1.1f,0));
            switch (pickup.type()) {
                case REPAIR -> {
                    box(item,"cross-x",new Vector3f(),new Vector3f(.65f,.19f,.19f),"repair");
                    box(item,"cross-y",new Vector3f(),new Vector3f(.19f,.65f,.19f),"repair");
                    box(item,"cross-z",new Vector3f(),new Vector3f(.19f,.19f,.65f),"repair");
                }
                case HOMING_AMMO -> {
                    pickupRocket(item,-.34f,.17f,.9f,"yellow");pickupRocket(item,.34f,.17f,.9f,"yellow");
                }
                case POWER_AMMO -> pickupRocket(item,0,.31f,1.2f,"red");
                case TURBO_CELL -> {
                    Geometry cell=new Geometry("energy-cell",new Cylinder(2,10,.46f,1.0f,true));
                    cell.rotate(FastMath.HALF_PI,0,0);cell.setMaterial(material("cyan"));item.attachChild(cell);
                    box(item,"terminal",new Vector3f(0,.65f,0),new Vector3f(.22f,.15f,.22f),"ivory");
                    box(item,"charge-mark",new Vector3f(0,0,-.47f),new Vector3f(.30f,.08f,.035f),"black");
                    box(item,"charge-plus",new Vector3f(0,0,-.48f),new Vector3f(.08f,.30f,.035f),"black");
                }
            }
            root.attachChild(item);
        }
    }
    private void pickupRocket(Node root,float x,float radius,float height,String color) {
        Geometry body=new Geometry("ammo-shell",new Cylinder(2,8,radius,height,true));
        body.rotate(FastMath.HALF_PI,0,0);body.setLocalTranslation(x,0,0);body.setMaterial(material(color));root.attachChild(body);
        Geometry nose=new Geometry("ammo-nose",new Sphere(5,8,radius));
        nose.setLocalTranslation(x,height*.5f,0);nose.setLocalScale(1,1.7f,1);nose.setMaterial(material("ivory"));root.attachChild(nose);
        box(root,"ammo-fin-x",new Vector3f(x,-height*.4f,0),new Vector3f(radius*1.7f,.16f,.05f),"steel");
        box(root,"ammo-fin-z",new Vector3f(x,-height*.4f,0),new Vector3f(.05f,.16f,radius*1.7f),"steel");
    }
    private void box(Node root,String id,Vector3f position,Vector3f half,String material) {
        Geometry visual=new Geometry(id,new Box(half.x,half.y,half.z));
        visual.setLocalTranslation(position); visual.setMaterial(material(material)); root.attachChild(visual);
    }
}

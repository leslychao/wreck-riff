package game.wreckriff.presentation;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.*;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.*;
import com.jme3.scene.shape.*;
import game.wreckriff.arena.ArenaDefinition;
import game.wreckriff.config.Configs;
import jme3tools.optimize.GeometryBatchFactory;
import java.util.*;

/** Loads local authored scenery. Layout/randomisation belongs to the offline authoring source. */
public final class ArenaArt {
    public enum Motion { STATIC, ROTATE_Z, SWAY_Z, STEAM, PULSE, SCREEN }
    public enum Shape { BOX, CYLINDER, SPHERE }
    public record Group(String id,ArenaDefinition.Vec3 position,Motion motion,float period,float phase) {
        public Group {Objects.requireNonNull(position);Objects.requireNonNull(motion);
            if(id==null||id.isBlank()||!Float.isFinite(period)||period<=0||!Float.isFinite(phase))throw new IllegalArgumentException("Invalid art group");}
    }
    public record Part(String id,String group,String anchor,Shape shape,String material,
            ArenaDefinition.Vec3 position,ArenaDefinition.Vec3 size,ArenaDefinition.Vec3 rotation) {
        public Part {Objects.requireNonNull(shape);Objects.requireNonNull(position);Objects.requireNonNull(rotation);
            if(id==null||id.isBlank()||group==null||anchor==null||material==null||size==null
                    ||size.x()<=0||size.y()<=0||size.z()<=0)throw new IllegalArgumentException("Invalid art part");}
    }
    public record Scene(int schemaVersion,String arenaId,String source,String license,List<Group> groups,List<Part> parts) {
        public Scene {
            if(schemaVersion!=1||source==null||license==null||arenaId==null)throw new IllegalArgumentException("Invalid art scene");
            groups=List.copyOf(groups);parts=List.copyOf(parts);
            Set<String> groupIds=new HashSet<>(),partIds=new HashSet<>();
            for(var group:groups)if(!groupIds.add(group.id()))throw new IllegalArgumentException("Duplicate art group");
            for(var part:parts)if(!partIds.add(part.id())||!part.group().isEmpty()&&!groupIds.contains(part.group()))
                throw new IllegalArgumentException("Invalid art part identity: "+part.id());
        }
    }
    private ArenaArt() {}
    public static Scene load(ArenaDefinition definition) {
        Scene scene=Configs.load("arena-art-"+definition.id().replace('_','-'),Scene.class);
        if(!scene.arenaId().equals(definition.id()))throw new IllegalArgumentException("Art/arena identity mismatch");
        return scene;
    }
    public static void attach(AssetManager assets,Node root,ArenaDefinition definition,SurfaceMaterials materials) {
        Scene scene=load(definition);Node art=new Node("authored-arena-art");
        art.setUserData("source",scene.source());art.setUserData("arenaId",scene.arenaId());
        art.setUserData("authoredPartCount",scene.parts().size());
        Map<String,Node> groups=new HashMap<>(),cells=new LinkedHashMap<>();
        for(var group:scene.groups()) {
            Node node=new Node("art-motion-"+group.id());node.setLocalTranslation(group.position().vector());
            node.setUserData("artMotion",group.motion().name());node.setUserData("artPeriod",group.period());
            node.setUserData("artPhase",group.phase());groups.put(group.id(),node);art.attachChild(node);
        }
        Map<String,Mesh> meshes=new HashMap<>();
        for(var part:scene.parts()) {
            var size=part.size();
            String key=part.shape()+":"+size;
            Mesh mesh=meshes.computeIfAbsent(key,ignored->switch(part.shape()) {
                case BOX -> SurfaceMesh.box(size.x()/2,size.y()/2,size.z()/2,4);
                case CYLINDER -> new Cylinder(2,12,.5f,1,true);
                case SPHERE -> new Sphere(6,10,.5f);
            });
            Geometry visual=new Geometry(part.id(),mesh);
            if(part.shape()!=Shape.BOX)visual.setLocalScale(size.vector());
            visual.setLocalTranslation(part.position().vector());
            visual.setLocalRotation(new Quaternion().fromAngles(part.rotation().vector().mult(FastMath.DEG_TO_RAD).toArray(null)));
            Material material;
            if(part.material().equals("steam")) {
                material=new Material(assets,"Common/MatDefs/Misc/Unshaded.j3md");
                material.setColor("Color",new ColorRGBA(.40f,.45f,.49f,.10f));
                material.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
                material.getAdditionalRenderState().setDepthWrite(false);
                visual.setQueueBucket(RenderQueue.Bucket.Transparent);visual.setShadowMode(RenderQueue.ShadowMode.Off);
            } else {
                material=materials.material(part.material());
                if(!part.group().isEmpty())material=material.clone();
                if(part.material().startsWith("light-")||size.y()<.1f)visual.setShadowMode(RenderQueue.ShadowMode.Off);
            }
            visual.setMaterial(material);
            if(material.getParam("NormalMap")!=null&&mesh.getBuffer(VertexBuffer.Type.Tangent)==null)
                com.jme3.util.mikktspace.MikktspaceTangentGenerator.generate(mesh);
            if(part.group().isEmpty()) {
                String cell=(int)Math.floor(part.position().x()/40)+":"+(int)Math.floor(part.position().z()/40);
                Node node=cells.computeIfAbsent(cell,ignored->new Node("art-cell-"+cell));node.attachChild(visual);
            } else groups.get(part.group()).attachChild(visual);
        }
        for(var cell:cells.values()) {cell.updateGeometricState();GeometryBatchFactory.optimize(cell,false);art.attachChild(cell);}
        root.attachChild(art);
    }
}

package game.wreckriff.presentation;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.*;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.*;
import com.jme3.scene.shape.*;
import com.jme3.scene.control.AbstractControl;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import game.wreckriff.arena.ArenaDefinition;
import game.wreckriff.config.Configs;
import jme3tools.optimize.GeometryBatchFactory;
import java.util.*;

/** Loads local authored scenery. Layout/randomisation belongs to the offline authoring source. */
public final class ArenaArt {
    public enum Motion { STATIC, ROTATE_Z, SWAY_Z, STEAM, PULSE, SCREEN }
    public enum Shape { BOX, CYLINDER, SPHERE, PATCH }
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
        attach(assets,root,definition,materials,load(definition));
    }
    public static List<String> surfaceMaterials(Scene scene) {
        return scene.parts().stream().filter(ArenaArt::usesSurfaceMaterial).map(Part::material).distinct().toList();
    }
    private static boolean usesSurfaceMaterial(Part part) {return !part.material().equals("steam");}
    public static void attach(AssetManager assets,Node root,ArenaDefinition definition,SurfaceMaterials materials,Scene scene) {
        if(!scene.arenaId().equals(definition.id()))throw new IllegalArgumentException("Art/arena identity mismatch");
        Node art=new Node("authored-arena-art");
        art.setUserData("source",scene.source());art.setUserData("arenaId",scene.arenaId());
        art.setUserData("authoredPartCount",scene.parts().size());
        Map<String,Node> groups=new HashMap<>(),cells=new LinkedHashMap<>(),anchors=new LinkedHashMap<>(),anchorStatics=new LinkedHashMap<>();
        Map<String,Group> definitions=new HashMap<>();for(var group:scene.groups())definitions.put(group.id(),group);
        Set<String> dynamic=new HashSet<>();
        definition.destructibles().forEach(object->dynamic.add(object.geometryId()));
        definition.barriers().forEach(barrier->dynamic.add(barrier.geometryId()));
        Map<String,Mesh> meshes=new HashMap<>();Map<String,Material> overlayMaterials=new HashMap<>();
        for(var part:scene.parts()) {
            var size=part.size();
            String key=part.shape()+":"+size;
            Mesh mesh=meshes.computeIfAbsent(key,ignored->switch(part.shape()) {
                case BOX -> SurfaceMesh.box(size.x()/2,size.y()/2,size.z()/2,4);
                case CYLINDER -> new Cylinder(2,12,.5f,1,true);
                case SPHERE -> new Sphere(6,10,.5f);
                case PATCH -> patchMesh(size);
            });
            Geometry visual=new Geometry(part.id(),mesh);
            if(part.shape()==Shape.CYLINDER||part.shape()==Shape.SPHERE)visual.setLocalScale(size.vector());
            visual.setLocalTranslation(part.position().vector());
            visual.setLocalRotation(new Quaternion().fromAngles(part.rotation().vector().mult(FastMath.DEG_TO_RAD).toArray(null)));
            Material material;
            if(!usesSurfaceMaterial(part)) {
                material=new Material(assets,"Common/MatDefs/Misc/Unshaded.j3md");
                material.setColor("Color",new ColorRGBA(.40f,.45f,.49f,.10f));
                material.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
                material.getAdditionalRenderState().setDepthWrite(false);
                visual.setQueueBucket(RenderQueue.Bucket.Transparent);visual.setShadowMode(RenderQueue.ShadowMode.Off);
            } else {
                material=materials.material(part.material());
                int layer=overlayLayer(part);
                if(layer>0)material=overlayMaterials.computeIfAbsent(part.material()+":"+layer,ignored->{
                    Material overlay=materials.material(part.material()).clone();
                    // Metre-scale worlds need polygon depth bias for flush road paint at distant viewpoints.
                    // Physical surface height, vehicle support and the scene's lit material stay unchanged.
                    overlay.getAdditionalRenderState().setPolyOffset(-1,-layer*3);return overlay;
                });
                if(!part.group().isEmpty())material=material.clone();
                if(part.material().startsWith("light-")||size.y()<.1f)visual.setShadowMode(RenderQueue.ShadowMode.Off);
            }
            visual.setMaterial(material);
            if(material.getParam("NormalMap")!=null&&mesh.getBuffer(VertexBuffer.Type.Tangent)==null)
                com.jme3.util.mikktspace.MikktspaceTangentGenerator.generate(mesh);
            Node anchor=art;
            if(dynamic.contains(part.anchor()))anchor=anchors.computeIfAbsent(part.anchor(),id->{
                Node node=new Node("art-anchor-"+id);node.setUserData("artAnchor",id);art.attachChild(node);return node;
            });
            if(part.group().isEmpty()&&anchor==art) {
                int cellSize=definition.layoutRevision()>=2?160:40;
                boolean detail=definition.layoutRevision()>=2&&Math.max(size.x(),Math.max(size.y(),size.z()))<=8;
                String cell=(int)Math.floor(part.position().x()/cellSize)+":"+(int)Math.floor(part.position().z()/cellSize)+(detail?":detail":"");
                Node node=cells.computeIfAbsent(cell,ignored->new Node("art-cell-"+cell));node.attachChild(visual);
            } else if(part.group().isEmpty()) {
                Node statics=anchorStatics.computeIfAbsent(part.anchor(),id->new Node("art-anchor-static-"+id));
                statics.attachChild(visual);
            }
            else {
                // A motion group may contain parts belonging to independent breakable objects.
                // Split it by lifetime owner so hiding one gate cannot hide a neighbouring facade.
                String groupKey=part.group()+":"+(anchor==art?"static":part.anchor());
                Node parent=anchor;
                Node node=groups.computeIfAbsent(groupKey,ignored->{
                    Group group=definitions.get(part.group());Node motion=new Node("art-motion-"+group.id());
                    motion.setLocalTranslation(group.position().vector());motion.setUserData("artMotion",group.motion().name());
                    motion.setUserData("artPeriod",group.period());motion.setUserData("artPhase",group.phase());
                    parent.attachChild(motion);return motion;
                });node.attachChild(visual);
            }
        }
        for(var entry:cells.entrySet()) {
            Node cell=entry.getValue();cell.updateGeometricState();GeometryBatchFactory.optimize(cell,false);
            if(entry.getKey().endsWith(":detail"))cell.addControl(new DetailDistance());
            art.attachChild(cell);
        }
        for(var entry:anchorStatics.entrySet()) {
            Node statics=entry.getValue();statics.updateGeometricState();GeometryBatchFactory.optimize(statics,false);
            anchors.get(entry.getKey()).attachChild(statics);
        }
        root.attachChild(art);
    }
    private static int overlayLayer(Part part) {
        if(part.id().startsWith("district-paving"))return 1;
        if(part.id().startsWith("road-shoulder"))return 2;
        if(part.id().startsWith("road-asphalt"))return 3;
        if(part.id().startsWith("road-edge-line")||part.id().startsWith("lane-dash")||part.id().startsWith("asphalt-repair"))return 4;
        return 0;
    }
    /** Keep silhouettes, structural surfaces and simulation at distance; omit tiny facade/road details. */
    private static final class DetailDistance extends AbstractControl {
        private Boolean visible;
        @Override protected void controlUpdate(float tpf) {}
        @Override protected void controlRender(RenderManager manager,ViewPort view) {
            if(spatial.getWorldBound()==null)return;
            float distance=spatial.getWorldBound().distanceToEdge(view.getCamera().getLocation());
            boolean next=distance<(Boolean.TRUE.equals(visible)?420:360);
            if(visible!=null&&next==visible)return;visible=next;
            // Cull the children, preserving this parent's bounds/render callback for re-entry.
            for(Spatial child:((Node)spatial).getChildren())child.setCullHint(next?Spatial.CullHint.Inherit:Spatial.CullHint.Always);
        }
    }
    private static Mesh patchMesh(ArenaDefinition.Vec3 size) {
        // Flush saw-cut repair, with clipped and imperfect edges rather than a floating rectangular plate.
        float[][] outline={{-.50f,-.34f},{-.37f,-.50f},{.28f,-.50f},{.48f,-.29f},{.50f,.27f},
                {.33f,.50f},{-.30f,.47f},{-.48f,.28f}};
        List<Vector3f> triangles=new ArrayList<>();Vector3f center=new Vector3f(0,size.y()*.5f,0);
        for(int i=0;i<outline.length;i++) {
            var a=outline[i];var b=outline[(i+1)%outline.length];
            // Top-facing winding; no side walls and no collision owner for a painted/tar surface.
            Collections.addAll(triangles,center,new Vector3f(b[0]*size.x(),center.y,b[1]*size.z()),
                    new Vector3f(a[0]*size.x(),center.y,a[1]*size.z()));
        }
        return SurfaceMesh.triangles(triangles,4);
    }
}

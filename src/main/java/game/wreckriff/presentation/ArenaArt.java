package game.wreckriff.presentation;

import com.jme3.asset.AssetManager;
import com.jme3.bounding.BoundingBox;
import com.jme3.font.BitmapFont;
import com.jme3.font.BitmapText;
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
import java.io.*;
import java.nio.charset.StandardCharsets;
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
    public record ModelInstance(String id,String group,String anchor,String asset,String distantAsset,
            ArenaDefinition.Vec3 position,ArenaDefinition.Vec3 size,ArenaDefinition.Vec3 rotation,float lodDistance,List<String> collisionGeometryIds) {
        public ModelInstance {
            Objects.requireNonNull(position);Objects.requireNonNull(size);Objects.requireNonNull(rotation);
            collisionGeometryIds=List.copyOf(collisionGeometryIds);
            if(id==null||id.isBlank()||group==null||anchor==null||!localModel(asset)||distantAsset==null
                    ||!distantAsset.isEmpty()&&!localModel(distantAsset)||size.x()<=0||size.y()<=0||size.z()<=0
                    ||!Float.isFinite(lodDistance)||lodDistance<=0)throw new IllegalArgumentException("Invalid authored model instance");
            if(!collisionGeometryIds.isEmpty()&&!group.isEmpty())throw new IllegalArgumentException("Structural model collision must be static");
        }
        private static boolean localModel(String asset) {
            return asset!=null&&asset.startsWith("models/arenas/")&&asset.endsWith(".j3o")&&!asset.contains("..")&&!asset.contains(":")&&!asset.contains("\\");
        }
    }
    public record LocalLight(String id,ArenaDefinition.Vec3 position,ArenaDefinition.Vec3 color,float radius) {
        public LocalLight {
            Objects.requireNonNull(position);Objects.requireNonNull(color);
            if(id==null||id.isBlank()||color.x()<0||color.y()<0||color.z()<0||Math.max(color.x(),Math.max(color.y(),color.z()))>4
                    ||!Float.isFinite(radius)||radius<1||radius>80)throw new IllegalArgumentException("Invalid authored local light");
        }
    }
    /** World-space text centre; local +Z is the front, +Y is up. Dimensions bound the visible glyphs in metres. */
    public record Sign(String id,String anchor,String text,ArenaDefinition.Vec3 position,float yawDegrees,float width,float height) {
        public Sign {
            Objects.requireNonNull(position);
            if(id==null||id.isBlank()||anchor==null||text==null||text.isBlank()
                    ||!Float.isFinite(yawDegrees)||!Float.isFinite(width)||width<=0||!Float.isFinite(height)||height<=0)
                throw new IllegalArgumentException("Invalid authored sign");
        }
    }
    public record Scene(int schemaVersion,String arenaId,String source,String license,List<Group> groups,List<Part> parts,List<ModelInstance> models,List<LocalLight> lights,List<Sign> signs) {
        public Scene(int version,String arenaId,String source,String license,List<Group> groups,List<Part> parts) {
            this(version,arenaId,source,license,groups,parts,List.of(),List.of(),List.of());
        }
        public Scene(int version,String arenaId,String source,String license,List<Group> groups,List<Part> parts,List<ModelInstance> models,List<LocalLight> lights) {
            this(version,arenaId,source,license,groups,parts,models,lights,List.of());
        }
        public Scene {
            if((schemaVersion!=1&&schemaVersion!=2)||source==null||license==null||arenaId==null)throw new IllegalArgumentException("Invalid art scene");
            groups=List.copyOf(groups);parts=List.copyOf(parts);models=models==null?List.of():List.copyOf(models);
            lights=lights==null?List.of():List.copyOf(lights);
            signs=signs==null?List.of():List.copyOf(signs);
            Set<String> groupIds=new HashSet<>(),partIds=new HashSet<>();
            for(var group:groups)if(!groupIds.add(group.id()))throw new IllegalArgumentException("Duplicate art group");
            for(var part:parts)if(!partIds.add(part.id())||!part.group().isEmpty()&&!groupIds.contains(part.group()))
                throw new IllegalArgumentException("Invalid art part identity: "+part.id());
            for(var model:models)if(!partIds.add(model.id())||!model.group().isEmpty()&&!groupIds.contains(model.group()))
                throw new IllegalArgumentException("Invalid model identity: "+model.id());
            for(var sign:signs)if(!partIds.add(sign.id()))throw new IllegalArgumentException("Duplicate authored sign identity: "+sign.id());
            Set<String> lightIds=new HashSet<>();for(var light:lights)if(!lightIds.add(light.id()))throw new IllegalArgumentException("Duplicate authored light");
        }
    }
    private ArenaArt() {}
    public static Scene load(ArenaDefinition definition) {
        String name="arena-art-"+definition.id().replace('_','-');
        try(Reader reader=Configs.open(name)) {
            Scene scene=read(reader);validateAnchors(scene,definition);return scene;
        } catch(IOException|RuntimeException exception) {
            throw new IllegalArgumentException("Invalid config "+name+": "+exception.getMessage(),exception);
        }
    }
    static Scene read(Reader reader) {
        var tree=com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
        // Signs are an additive scene field. Existing local scenes keep strict validation for every other field.
        if(!tree.has("signs"))tree.add("signs",new com.google.gson.JsonArray());
        Configs.validate(tree,Scene.class,"arena-art");return Configs.gson().fromJson(tree,Scene.class);
    }
    private static void validateAnchors(Scene scene,ArenaDefinition definition) {
        if(!scene.arenaId().equals(definition.id()))throw new IllegalArgumentException("Art/arena identity mismatch");
        if(scene.signs().isEmpty())return;
        Set<String> geometryIds=new HashSet<>();
        definition.boxes().forEach(box->geometryIds.add(box.id()));
        definition.ramps().forEach(ramp->geometryIds.add(ramp.id()));
        definition.meshes().forEach(mesh->geometryIds.add(mesh.id()));
        for(var sign:scene.signs())if(!sign.anchor().isEmpty()&&!sign.anchor().equals("exterior")&&!geometryIds.contains(sign.anchor()))
            throw new IllegalArgumentException("Unknown authored sign anchor: "+sign.id()+" -> "+sign.anchor());
    }
    public static void attach(AssetManager assets,Node root,ArenaDefinition definition,SurfaceMaterials materials) {
        attach(assets,root,definition,materials,load(definition));
    }
    public static List<String> surfaceMaterials(Scene scene) {
        LinkedHashSet<String> materials=new LinkedHashSet<>();
        scene.parts().stream().filter(ArenaArt::usesSurfaceMaterial).map(Part::material).forEach(materials::add);
        for(String asset:modelAssets(scene)) {
            var modelMaterials=ModelMaterials.BY_ASSET.get(asset);
            if(modelMaterials==null)throw new IllegalArgumentException("Missing authored model material manifest: "+asset);
            materials.addAll(modelMaterials);
        }
        return List.copyOf(materials);
    }
    /** Exported metadata lets staged loading enumerate real model textures before decoding any j3o. */
    private static final class ModelMaterials {
        private static final Map<String,List<String>> BY_ASSET=load();
        private static Map<String,List<String>> load() {
            try(InputStream stream=ArenaArt.class.getResourceAsStream("/models/arenas/provenance.json")) {
                if(stream==null)throw new IOException("Missing local architecture manifest");
                var json=com.google.gson.JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
                Map<String,List<String>> result=new LinkedHashMap<>();
                for(var element:json.getAsJsonArray("assets")) {
                    var entry=element.getAsJsonObject();List<String> materials=new ArrayList<>();
                    for(var material:entry.getAsJsonArray("materials"))materials.add(material.getAsString());
                    if(result.put(entry.get("path").getAsString(),List.copyOf(materials))!=null)
                        throw new IOException("Duplicate architecture manifest entry");
                }
                return Map.copyOf(result);
            } catch(IOException exception) {throw new UncheckedIOException(exception);}
        }
    }
    public static List<String> modelAssets(Scene scene) {
        LinkedHashSet<String> paths=new LinkedHashSet<>();
        for(var model:scene.models()){paths.add(model.asset());if(!model.distantAsset().isEmpty())paths.add(model.distantAsset());}
        return List.copyOf(paths);
    }
    private static boolean usesSurfaceMaterial(Part part) {return !part.material().equals("steam");}
    public static void attach(AssetManager assets,Node root,ArenaDefinition definition,SurfaceMaterials materials,Scene scene) {
        validateAnchors(scene,definition);
        Node art=new Node("authored-arena-art");
        art.setUserData("source",scene.source());art.setUserData("arenaId",scene.arenaId());
        art.setUserData("authoredPartCount",scene.parts().size());
        art.setUserData("authoredSignCount",scene.signs().size());
        Map<String,Node> groups=new HashMap<>(),cells=new LinkedHashMap<>(),anchors=new LinkedHashMap<>(),anchorStatics=new LinkedHashMap<>();
        Map<String,Group> definitions=new HashMap<>();for(var group:scene.groups())definitions.put(group.id(),group);
        Set<String> dynamic=new HashSet<>();
        definition.destructibles().forEach(object->dynamic.add(object.geometryId()));
        definition.barriers().forEach(barrier->dynamic.add(barrier.geometryId()));
        Map<String,Mesh> meshes=new HashMap<>();
        for(var part:scene.parts()) {
            var size=part.size();
            float tileSize=SurfaceMaterials.metresPerTile(part.material());
            String key=part.shape()+":"+size+":"+tileSize;
            Mesh mesh=meshes.computeIfAbsent(key,ignored->switch(part.shape()) {
                case BOX -> SurfaceMesh.box(size.x()/2,size.y()/2,size.z()/2,tileSize);
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
                for(Geometry chunk:SpatialChunks.split(visual,cellSize)) {
                    String cell=chunk.getUserData("spatialCell");
                    if(cell==null)cell=(int)Math.floor(part.position().x()/cellSize)+":"+(int)Math.floor(part.position().z()/cellSize);
                    String cellKey=cell;Node node=cells.computeIfAbsent(cellKey,ignored->new Node("art-cell-"+cellKey));node.attachChild(chunk);
                }
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
            art.attachChild(cell);
        }
        for(var entry:anchorStatics.entrySet()) {
            Node statics=entry.getValue();statics.updateGeometricState();GeometryBatchFactory.optimize(statics,false);
            anchors.get(entry.getKey()).attachChild(statics);
        }
        Map<String,Spatial> prototypes=new HashMap<>();
        SurfaceMaterials.lightingDefinition(assets);
        for(var model:scene.models()) {
            Node instance=new Node(model.id());instance.setLocalTranslation(model.position().vector());instance.setLocalScale(model.size().vector());
            instance.setLocalRotation(new Quaternion().fromAngles(model.rotation().vector().mult(FastMath.DEG_TO_RAD).toArray(null)));
            Spatial detailed=prototypes.computeIfAbsent(model.asset(),assets::loadModel).clone(!model.group().isEmpty());
            instance.attachChild(detailed);
            if(!model.distantAsset().isEmpty()) {
                Spatial distant=prototypes.computeIfAbsent(model.distantAsset(),assets::loadModel).clone(!model.group().isEmpty());
                distant.setCullHint(Spatial.CullHint.Always);instance.attachChild(distant);
                instance.addControl(new ModelDistance(detailed,distant,model.lodDistance()));
            }
            Node parent=art;
            if(dynamic.contains(model.anchor()))parent=anchors.computeIfAbsent(model.anchor(),id->{
                Node node=new Node("art-anchor-"+id);node.setUserData("artAnchor",id);art.attachChild(node);return node;
            });
            if(!model.group().isEmpty()) {
                String groupKey=model.group()+":"+(parent==art?"static":model.anchor());Node owner=parent;
                parent=groups.computeIfAbsent(groupKey,ignored->{
                    Group group=definitions.get(model.group());Node motion=new Node("art-motion-"+group.id());
                    motion.setLocalTranslation(group.position().vector());motion.setUserData("artMotion",group.motion().name());
                    motion.setUserData("artPeriod",group.period());motion.setUserData("artPhase",group.phase());owner.attachChild(motion);return motion;
                });
            }
            parent.attachChild(instance);
        }
        BitmapFont font=scene.signs().isEmpty()?null:assets.loadFont("fonts/wreck-bold.fnt");
        for(var sign:scene.signs()) {
            Node parent=art;
            if(dynamic.contains(sign.anchor()))parent=anchors.computeIfAbsent(sign.anchor(),id->{
                Node node=new Node("art-anchor-"+id);node.setUserData("artAnchor",id);art.attachChild(node);return node;
            });
            parent.attachChild(sign(sign,font));
        }
        root.attachChild(art);
        if(!scene.lights().isEmpty())root.addControl(new ArenaLocalLights(root,scene.lights()));
    }
    private static Node sign(Sign definition,BitmapFont font) {
        Node sign=new Node(definition.id());sign.setLocalTranslation(definition.position().vector());
        sign.setLocalRotation(new Quaternion().fromAngleAxis(definition.yawDegrees()*FastMath.DEG_TO_RAD,Vector3f.UNIT_Y));
        BitmapText text=new BitmapText(font);text.setName(definition.id()+"-text");text.setText(definition.text());text.setSize(1);
        text.setColor(new ColorRGBA(.96f,.92f,.82f,1));
        text.setQueueBucket(RenderQueue.Bucket.Transparent);text.setShadowMode(RenderQueue.ShadowMode.Off);
        // Assemble once during loading. Centre actual glyph geometry, including accents and multiple lines.
        text.updateLogicalState(0);text.updateGeometricState();
        if(!(text.getWorldBound() instanceof BoundingBox bounds)||bounds.getXExtent()<=0||bounds.getYExtent()<=0)
            throw new IllegalArgumentException("Sign has no visible glyphs: "+definition.id());
        float scale=Math.min(definition.width()/(2*bounds.getXExtent()),definition.height()/(2*bounds.getYExtent()));
        text.setLocalScale(scale);text.setLocalTranslation(bounds.getCenter().mult(-scale));
        text.depthFirstTraversal(spatial->{if(spatial instanceof Geometry geometry) {
            Material material=geometry.getMaterial().clone();
            material.getAdditionalRenderState().setDepthTest(true);material.getAdditionalRenderState().setDepthWrite(false);
            material.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Back);geometry.setMaterial(material);
        }});
        sign.attachChild(text);return sign;
    }
    /** Each authored building swaps to its silhouette mesh independently; no district-wide detail blackout. */
    private static final class ModelDistance extends AbstractControl {
        private final Spatial detailed,distant;
        private final float distance;
        private boolean near=true;
        ModelDistance(Spatial detailed,Spatial distant,float distance) {this.detailed=detailed;this.distant=distant;this.distance=distance;}
        @Override protected void controlUpdate(float tpf) {}
        @Override protected void controlRender(RenderManager manager,ViewPort view) {
            if(spatial.getWorldBound()==null)return;
            float range=spatial.getWorldBound().distanceToEdge(view.getCamera().getLocation());
            boolean next=range<distance*(near?1.08f:.92f);
            if(next==near)return;near=next;
            detailed.setCullHint(near?Spatial.CullHint.Inherit:Spatial.CullHint.Always);
            distant.setCullHint(near?Spatial.CullHint.Always:Spatial.CullHint.Inherit);
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

package game.wreckriff.diagnostics;

import com.google.gson.*;
import com.jme3.asset.DesktopAssetManager;
import com.jme3.bounding.BoundingBox;
import com.jme3.math.Vector3f;
import com.jme3.scene.*;
import game.wreckriff.arena.ArenaRegistry;
import game.wreckriff.presentation.ArenaArt;
import game.wreckriff.presentation.SurfaceMaterials;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Offline integrity of authored architecture. Geometry checks do not grant creative acceptance. */
public final class ArenaModelVerifier {
    private static final String MANIFEST="models/arenas/provenance.json";
    private ArenaModelVerifier() { }
    public static List<VerifyAssets.Asset> verify()throws Exception {
        List<VerifyAssets.Asset> result=new ArrayList<>();byte[] manifest=resource(MANIFEST);
        JsonObject evidence=JsonParser.parseString(new String(manifest,StandardCharsets.UTF_8)).getAsJsonObject();
        if(evidence.get("schemaVersion").getAsInt()!=1||!"ORIGINAL_PROJECT_CONTENT".equals(evidence.get("origin").getAsString())
                ||!"NEEDS_CREATIVE_REVIEW".equals(evidence.get("artisticStatus").getAsString()))throw new IOException("Invalid architecture provenance");
        for(String field:List.of("converter","generator")) {
            String expectedSource=field.equals("converter")?"src/tools/java/game/wreckriff/tools/PrepareArenaModels.java":"src/tools/author_arena_models.py";
            if(!expectedSource.equals(evidence.get(field).getAsString()))throw new IOException("Unknown architecture authoring source");
            byte[] bytes=Files.readAllBytes(Path.of(expectedSource));
            if(!hash(bytes).equals(evidence.get(field+"Sha256").getAsString()))throw new IOException("Architecture recipe changed after export");
            result.add(asset(expectedSource,"procedural-source",bytes,"Offline architecture authoring and conversion"));
        }
        Set<String> required=new TreeSet<>();List<ArenaArt.ModelInstance> instances=new ArrayList<>();var registry=ArenaRegistry.load();
        for(var entry:registry.entries()) {
            var arena=registry.definition(entry.id());var scene=ArenaArt.load(arena);required.addAll(ArenaArt.modelAssets(scene));instances.addAll(scene.models());
            if(arena.layoutRevision()>=3&&scene.models().size()<3)throw new IOException("Missing authored architecture for "+arena.id());
        }
        Set<String> expected=Set.copyOf(required);Map<String,Spatial> models=new HashMap<>();Map<String,Integer> counts=new HashMap<>();
        var manager=new DesktopAssetManager(true);SurfaceMaterials.lightingDefinition(manager);
        for(var element:evidence.getAsJsonArray("assets")) {
            var item=element.getAsJsonObject();String path=item.get("path").getAsString();
            if(!required.remove(path))throw new IOException("Unused or duplicate architecture: "+path);
            byte[] output=resource(path);if(!hash(output).equals(item.get("sha256").getAsString()))throw new IOException("Architecture output checksum: "+path);
            for(String field:List.of("source","sourceBlend")) {
                if(!item.has(field))throw new IOException("Missing editable architecture source: "+path+" / "+field);
                String source=item.get(field).getAsString();
                if(!source.startsWith("src/tools/assets/architecture/")||source.contains("..")||source.contains("\\"))throw new IOException("Invalid model source "+source);
                byte[] bytes=Files.readAllBytes(Path.of(source));
                String digest=item.get(field.equals("source")?"sourceSha256":"sourceBlendSha256").getAsString();
                if(!hash(bytes).equals(digest))throw new IOException("Architecture source checksum: "+source);
                String magic=new String(bytes,0,Math.min(bytes.length,7),StandardCharsets.US_ASCII);
                if(field.equals("source")?!magic.startsWith("glTF"):!magic.equals("BLENDER"))throw new IOException("Invalid authored source format: "+source);
                result.add(asset(source,"model-source",bytes,"Editable original architecture source; "+MANIFEST));
            }
            Spatial model=manager.loadModel(path);int count=validate(model);
            if(count!=item.get("triangles").getAsInt())throw new IOException("Architecture triangle evidence mismatch: "+path);
            models.put(path,model);counts.put(path,count);result.add(asset(path,"arena-model",output,item.get("source").getAsString()));
        }
        if(!required.isEmpty())throw new IOException("Architecture missing from provenance: "+required);
        for(var instance:instances) {
            if(instance.distantAsset().isEmpty())throw new IOException("Architecture needs an authored distance mesh: "+instance.id());
            if(counts.get(instance.distantAsset())>=counts.get(instance.asset()))throw new IOException("Architecture LOD has no reduction: "+instance.id());
            BoundingBox near=(BoundingBox)models.get(instance.asset()).getWorldBound(),far=(BoundingBox)models.get(instance.distantAsset()).getWorldBound();
            for(int axis=0;axis<3;axis++) {
                float a=near.getExtent(null).get(axis),b=far.getExtent(null).get(axis);
                if(Math.abs(a-b)>Math.max(2,a*.15f))throw new IOException("Architecture LOD loses silhouette: "+instance.id());
            }
            if(near.getCenter().distance(far.getCenter())>Math.max(2,near.getExtent(null).length()*.1f))throw new IOException("Architecture LOD origin changes: "+instance.id());
        }
        if(expected.isEmpty())throw new IOException("No architecture resources referenced");
        result.add(asset(MANIFEST,"arena-model-provenance",manifest,"PrepareArenaModels.java; authored GLB and Blender sources"));return List.copyOf(result);
    }
    static int validate(Spatial model) {
        int[] triangles={0};model.depthFirstTraversal(spatial->{
            if(spatial.getNumControls()!=0)throw new IllegalArgumentException("Authored model contains a runtime control");
            if(spatial instanceof Geometry geometry) {
                for(var attribute:List.of(VertexBuffer.Type.Position,VertexBuffer.Type.Normal,VertexBuffer.Type.TexCoord,VertexBuffer.Type.Tangent)) {
                    var buffer=geometry.getMesh().getFloatBuffer(attribute);
                    if(buffer==null||buffer.limit()==0)throw new IllegalArgumentException("Missing architecture attribute "+attribute);
                    for(int i=0;i<buffer.limit();i++)if(!Float.isFinite(buffer.get(i)))throw new IllegalArgumentException("Non-finite architecture attribute "+attribute);
                    if(attribute==VertexBuffer.Type.Normal)for(int i=0;i<buffer.limit();i+=3) {
                        float length=buffer.get(i)*buffer.get(i)+buffer.get(i+1)*buffer.get(i+1)+buffer.get(i+2)*buffer.get(i+2);
                        if(length<.95f||length>1.05f)throw new IllegalArgumentException("Architecture requires unit normals");
                    }
                    if(attribute==VertexBuffer.Type.TexCoord) {
                        float low=Float.POSITIVE_INFINITY,high=Float.NEGATIVE_INFINITY;
                        for(int i=0;i<buffer.limit();i++){low=Math.min(low,buffer.get(i));high=Math.max(high,buffer.get(i));}
                        if(high-low<.00001f)throw new IllegalArgumentException("Architecture requires usable UV coordinates");
                    }
                }
                String definition=geometry.getMaterial().getMaterialDef().getName();
                if(!definition.equals("Phong Lighting")&&!definition.equals("Unshaded"))throw new IllegalArgumentException("Unsupported architecture material: "+definition);
                triangles[0]+=geometry.getMesh().getTriangleCount();
            }
        });
        model.updateGeometricState();
        if(triangles[0]==0||!(model.getWorldBound() instanceof BoundingBox bound)||!Vector3f.isValidVector(bound.getCenter()))
            throw new IllegalArgumentException("Empty or invalid architecture bounds");
        return triangles[0];
    }
    private static byte[] resource(String path)throws IOException {
        try(var stream=ArenaModelVerifier.class.getClassLoader().getResourceAsStream(path)) {
            if(stream==null)throw new FileNotFoundException(path);byte[] bytes=stream.readNBytes(64*1024*1024+1);
            if(bytes.length>64*1024*1024)throw new IOException("Oversized architecture resource");return bytes;
        }
    }
    private static String hash(byte[] bytes)throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static VerifyAssets.Asset asset(String path,String category,byte[] bytes,String source)throws Exception {
        return new VerifyAssets.Asset(path,category,bytes.length,hash(bytes),source,"SOURCE_MODEL_UV_LOD_VERIFIED",null,null,null,null);
    }
}

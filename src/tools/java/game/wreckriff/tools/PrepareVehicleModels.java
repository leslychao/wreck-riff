package game.wreckriff.tools;

import com.google.gson.*;
import com.jme3.asset.DesktopAssetManager;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.math.*;
import com.jme3.scene.*;
import com.jme3.util.mikktspace.MikktspaceTangentGenerator;
import game.wreckriff.presentation.VehicleMaterials;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Explicit offline Blender mesh bundle -> immutable jME stage banks. Never invoked by a normal build. */
public final class PrepareVehicleModels {
    private static final String GENERATOR="src/tools/author_vehicle_models.py",CONVERTER="src/tools/java/game/wreckriff/tools/PrepareVehicleModels.java";
    private static final List<String> MAPS=List.of("diffuse","normal","specular","metal-diffuse","metal-normal","metal-specular","damage-ownership");
    private PrepareVehicleModels() {}
    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("source vehicle directory and resource directory required");
        Path source=Path.of(args[0]),resources=Path.of(args[1]);
        var manager=new DesktopAssetManager(true);manager.registerLocator(resources.toAbsolutePath().toString(),FileLocator.class);
        List<Map<String,Object>> records=new ArrayList<>();
        for(String id:List.of("rivet","grinder","spark","boss_foreman","boss_prefect","boss_emcee")) {
            Path input=source.resolve(id);JsonObject data;
            JsonObject bake=JsonParser.parseString(Files.readString(input.resolve("source.json"))).getAsJsonObject();
            if(!hash(Path.of(GENERATOR)).equals(bake.get("generatorSha256").getAsString()))throw new IOException("Vehicle source was baked by a different authoring script: "+id+"; run Blender bake or explicit --reexport after reviewing script changes");
            for(var entry:bake.getAsJsonObject("exports").entrySet())if(!hash(input.resolve(entry.getKey())).equals(entry.getValue().getAsString()))throw new IOException("Edited Blender/export companion requires --reexport before conversion: "+id+"/"+entry.getKey());
            if(bake.getAsJsonObject("exports").size()!=10)throw new IOException("Incomplete vehicle source exports "+id);
            manager.registerLocator(input.toAbsolutePath().toString(),FileLocator.class);
            Spatial authored=manager.loadModel("source.glb");int[] sourceTriangles={0};authored.depthFirstTraversal(s->{if(s instanceof Geometry g)sourceTriangles[0]+=g.getMesh().getTriangleCount();});
            manager.unregisterLocator(input.toAbsolutePath().toString(),FileLocator.class);manager.clearCache();
            try(var stream=new GZIPInputStream(Files.newInputStream(input.resolve("mesh.json.gz")))) {
                data=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
            }
            Path textures=resources.resolve("textures/vehicles/"+id);Files.createDirectories(textures);
            for(String texture:MAPS)Files.copy(input.resolve(texture+".png"),textures.resolve(texture+".png"),StandardCopyOption.REPLACE_EXISTING);
            Node bank=new Node(id+"-asset-bank");List<Integer> counts=new ArrayList<>();
            var sparse=new JsonObject();sparse.addProperty("schemaVersion",1);sparse.add("lods",new JsonArray());
            for(int lod=0;lod<3;lod++) {
                Node lodBank=new Node("lod"+lod);bank.attachChild(lodBank);JsonObject lodRegions=new JsonObject();sparse.getAsJsonArray("lods").add(lodRegions);
                var sourceParts=data.getAsJsonArray("lods").get(lod).getAsJsonArray();int triangles=0;
                Map<String,Mesh> immutable=new HashMap<>();
                for(int stage=0;stage<5;stage++) {
                    Node stageBank=new Node("stage"+stage);lodBank.attachChild(stageBank);
                    for(var item:sourceParts) {
                        var part=item.getAsJsonObject();String name=part.get("name").getAsString();
                        float[] position=floats(part.getAsJsonArray("stages").get(stage).getAsJsonArray());
                        float[] uv=floats(part.getAsJsonArray("uv"));
                        String key=name+":"+Arrays.hashCode(position);Mesh mesh=immutable.get(key);
                        if(mesh==null) {mesh=mesh(position,uv);immutable.put(key,mesh);}
                        Geometry geometry=new Geometry(name,mesh);geometry.setUserData("damageIslandId",part.get("islandId").getAsInt());geometry.setMaterial(VehicleMaterials.create(manager,id,name));
                        stageBank.attachChild(geometry);if(stage==0){mesh.createCollisionData();triangles+=mesh.getTriangleCount();lodRegions.add(name,part.get("regional"));}
                    }
                }
                int budget=id.startsWith("boss_")?new int[]{32000,12000,3000}[lod]:new int[]{24000,8000,2000}[lod];
                if(triangles>budget)throw new IllegalArgumentException(id+" LOD"+lod+" exceeds budget "+triangles+">"+budget);
                counts.add(triangles);
            }
            if(sourceTriangles[0]!=counts.getFirst())throw new IllegalArgumentException("GLB and canonical topology companion disagree for "+id);
            Path output=resources.resolve("models/vehicles/"+id);Files.createDirectories(output);
            bank.setUserData("profileId",id);bank.setUserData("assetOrigin","ORIGINAL_BLENDER_CONTENT");bank.updateGeometricState();
            Path prepared=output.resolve("vehicle.j3o.prepared");BinaryExporter.getInstance().save(bank,prepared.toFile());
            Files.move(prepared,output.resolve("vehicle.j3o"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            try(var zip=new java.util.zip.GZIPOutputStream(Files.newOutputStream(output.resolve("regions.json.gz.prepared")))) {zip.write(sparse.toString().getBytes(StandardCharsets.UTF_8));}
            Files.move(output.resolve("regions.json.gz.prepared"),output.resolve("regions.json.gz"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            var record=new LinkedHashMap<String,Object>();record.put("id",id);record.put("stages",5);record.put("regions",8);record.put("lodTriangles",counts);
            record.put("model","models/vehicles/"+id+"/vehicle.j3o");record.put("modelSha256",hash(output.resolve("vehicle.j3o")));
            record.put("regional","models/vehicles/"+id+"/regions.json.gz");record.put("regionalSha256",hash(output.resolve("regions.json.gz")));
            record.put("sourceBlend","src/tools/assets/vehicles/"+id+"/source.blend");record.put("sourceBlendSha256",hash(input.resolve("source.blend")));
            record.put("sourceGlb","src/tools/assets/vehicles/"+id+"/source.glb");record.put("sourceGlbSha256",hash(input.resolve("source.glb")));
            record.put("meshBundle","src/tools/assets/vehicles/"+id+"/mesh.json.gz");record.put("meshBundleSha256",hash(input.resolve("mesh.json.gz")));
            record.put("sourceManifest","src/tools/assets/vehicles/"+id+"/source.json");record.put("sourceManifestSha256",hash(input.resolve("source.json")));
            var textureRecords=new LinkedHashMap<String,String>();for(String map:MAPS)textureRecords.put("textures/vehicles/"+id+"/"+map+".png",hash(textures.resolve(map+".png")));
            record.put("textures",textureRecords);records.add(record);System.out.println("Prepared vehicle "+id+" "+counts);
        }
        Path shared=resources.resolve("textures/vehicles/shared");Files.createDirectories(shared);Files.copy(source.resolve("shared/damage-atlas.png"),shared.resolve("damage-atlas.png"),StandardCopyOption.REPLACE_EXISTING);
        var manifest=new LinkedHashMap<String,Object>();manifest.put("damageAtlas","textures/vehicles/shared/damage-atlas.png");manifest.put("damageAtlasSha256",hash(shared.resolve("damage-atlas.png")));manifest.put("schemaVersion",1);manifest.put("origin","ORIGINAL_BLENDER_CONTENT");
        manifest.put("generator",GENERATOR);manifest.put("generatorSha256",hash(Path.of(GENERATOR)));manifest.put("converter",CONVERTER);manifest.put("converterSha256",hash(Path.of(CONVERTER)));
        manifest.put("blenderVersion","4.5.9 LTS");manifest.put("licensePermission","Original Wreck Riff models and procedural texture recipes; no external geometry or bitmap sources.");
        manifest.put("coordinateSystem","metres, +Y up, +Z forward, +X right; immutable profile sockets");manifest.put("uv","Shared nonoverlapping packed canonical UV islands; lower LOD triangle projection transferred offline; 512 ownership map clips brush footprints to their part");
        manifest.put("textureContract","Paint diffuse/normal 2048; specular and metal diffuse/normal/specular 1024, RGB(A)8; diffuse sRGB, normal/specular linear");manifest.put("sourceAuthority","Saved Blender scene with three LODs, five HP poses, eight regional shape keys; --reexport PROFILE writes GLB and canonical topology companion from edited scene");manifest.put("artisticStatus","NEEDS_CREATIVE_REVIEW");manifest.put("profiles",records);
        Files.writeString(resources.resolve("models/vehicles/provenance.json"),new GsonBuilder().setPrettyPrinting().create().toJson(manifest)+"\n",StandardCharsets.UTF_8);
    }
    private static float[] floats(JsonArray data) {float[] result=new float[data.size()];for(int i=0;i<result.length;i++)result[i]=data.get(i).getAsFloat();return result;}
    private static Mesh mesh(float[] points,float[] uv) {
        Mesh mesh=new Mesh();mesh.setBuffer(VertexBuffer.Type.Position,3,points);mesh.setBuffer(VertexBuffer.Type.TexCoord,2,uv);
        float[] normal=new float[points.length];Vector3f a=new Vector3f(),b=new Vector3f(),c=new Vector3f();
        for(int i=0;i<points.length;i+=9){a.set(points[i],points[i+1],points[i+2]);b.set(points[i+3],points[i+4],points[i+5]);c.set(points[i+6],points[i+7],points[i+8]);
            Vector3f n=b.subtractLocal(a).crossLocal(c.subtractLocal(a)).normalizeLocal();for(int k=0;k<3;k++){normal[i+k*3]=n.x;normal[i+k*3+1]=n.y;normal[i+k*3+2]=n.z;}}
        mesh.setBuffer(VertexBuffer.Type.Normal,3,normal);MikktspaceTangentGenerator.generate(mesh);mesh.updateBound();mesh.setStatic();return mesh;
    }
    private static String hash(Path path)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
}

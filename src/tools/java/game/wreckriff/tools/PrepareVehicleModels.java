package game.wreckriff.tools;

import com.google.gson.*;
import com.jme3.asset.DesktopAssetManager;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.math.*;
import com.jme3.scene.*;
import com.jme3.util.mikktspace.MikktspaceTangentGenerator;
import game.wreckriff.presentation.VehicleMaterials;
import game.wreckriff.presentation.VehicleDiffuseDds;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Explicit offline Blender mesh bundle -> immutable jME stage banks. Never invoked by a normal build. */
public final class PrepareVehicleModels {
    private static final String GENERATOR="src/tools/author_vehicle_models.py",CONVERTER="src/tools/java/game/wreckriff/tools/PrepareVehicleModels.java";
    private static final List<String> MAPS=List.of("diffuse","normal","specular","damage-ownership");
    private PrepareVehicleModels() {}
    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("source vehicle directory and resource directory required");
        Path source=Path.of(args[0]),resources=Path.of(args[1]);
        Path diffuseManifest=resources.resolve("textures/vehicles/diffuse-provenance.json");
        var compression=JsonParser.parseString(Files.readString(diffuseManifest)).getAsJsonObject();
        if(!hash(Path.of(compression.get("converter").getAsString())).equals(compression.get("converterSha256").getAsString()))throw new IOException("Stale vehicle diffuse preparation recipe");
        var diffuseIds=new HashSet<>(List.of("rivet","grinder","spark","boss_foreman","boss_prefect","boss_emcee","shared"));
        for(var item:compression.getAsJsonArray("textures")){var entry=item.getAsJsonObject();Path runtime=resources.resolve(entry.get("runtime").getAsString());String id=runtime.getParent().getFileName().toString();
            if(!diffuseIds.remove(id)||!hash(runtime).equals(entry.get("runtimeSha256").getAsString())||!hash(Path.of(entry.get("source").getAsString())).equals(entry.get("sourceSha256").getAsString()))throw new IOException("Stale or duplicate prepared vehicle diffuse: "+runtime);
            VehicleDiffuseDds.read(Files.readAllBytes(runtime),id.equals("shared")?1024:2048);}
        if(!diffuseIds.isEmpty())throw new IOException("Missing prepared vehicle diffuse "+diffuseIds);
        var manager=new DesktopAssetManager(true);manager.registerLocator(resources.toAbsolutePath().toString(),FileLocator.class);
        List<Map<String,Object>> records=new ArrayList<>();
        Path shared=resources.resolve("textures/vehicles/shared");Files.createDirectories(shared);
        JsonObject sharedSource=JsonParser.parseString(Files.readString(source.resolve("shared/source.json"))).getAsJsonObject();
        if(!hash(Path.of(GENERATOR)).equals(sharedSource.get("generatorSha256").getAsString()))throw new IOException("Shared vehicle texture recipe is stale");
        for(var entry:sharedSource.getAsJsonObject("exports").entrySet()){Path file=source.resolve("shared/"+entry.getKey());if(!hash(file).equals(entry.getValue().getAsString()))throw new IOException("Shared vehicle texture was edited outside the prepared source pipeline: "+file);if(!entry.getKey().equals("metal-diffuse.png"))publishTexture(file,shared.resolve(entry.getKey()));}
        for(String id:List.of("rivet","grinder","spark","boss_foreman","boss_prefect","boss_emcee")) {
            Path input=source.resolve(id);JsonObject data;
            JsonObject bake=JsonParser.parseString(Files.readString(input.resolve("source.json"))).getAsJsonObject();
            if(!hash(Path.of(GENERATOR)).equals(bake.get("generatorSha256").getAsString()))throw new IOException("Vehicle source was baked by a different authoring script: "+id+"; run Blender bake or explicit --reexport after reviewing script changes");
            for(var entry:bake.getAsJsonObject("exports").entrySet())if(!hash(input.resolve(entry.getKey())).equals(entry.getValue().getAsString()))throw new IOException("Edited Blender/export companion requires --reexport before conversion: "+id+"/"+entry.getKey());
            if(bake.getAsJsonObject("exports").size()!=7)throw new IOException("Incomplete vehicle source exports "+id);
            manager.registerLocator(input.toAbsolutePath().toString(),FileLocator.class);
            Spatial authored=manager.loadModel("source.glb");int[] sourceTriangles={0};authored.depthFirstTraversal(s->{if(s instanceof Geometry g)sourceTriangles[0]+=g.getMesh().getTriangleCount();});
            manager.unregisterLocator(input.toAbsolutePath().toString(),FileLocator.class);manager.clearCache();
            try(var stream=new GZIPInputStream(Files.newInputStream(input.resolve("mesh.json.gz")))) {
                data=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
            }
            Path textures=resources.resolve("textures/vehicles/"+id);Files.createDirectories(textures);
            for(String texture:MAPS)if(!texture.equals("diffuse"))publishTexture(input.resolve(texture+".png"),textures.resolve(texture+".png"));
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
            movePrepared(prepared,output.resolve("vehicle.j3o"));
            try(var zip=new java.util.zip.GZIPOutputStream(Files.newOutputStream(output.resolve("regions.json.gz.prepared")))) {zip.write(sparse.toString().getBytes(StandardCharsets.UTF_8));}
            movePrepared(output.resolve("regions.json.gz.prepared"),output.resolve("regions.json.gz"));
            var record=new LinkedHashMap<String,Object>();record.put("id",id);record.put("stages",5);record.put("regions",8);record.put("lodTriangles",counts);
            record.put("model","models/vehicles/"+id+"/vehicle.j3o");record.put("modelSha256",hash(output.resolve("vehicle.j3o")));
            record.put("regional","models/vehicles/"+id+"/regions.json.gz");record.put("regionalSha256",hash(output.resolve("regions.json.gz")));
            record.put("sourceBlend","src/tools/assets/vehicles/"+id+"/source.blend");record.put("sourceBlendSha256",hash(input.resolve("source.blend")));
            record.put("sourceGlb","src/tools/assets/vehicles/"+id+"/source.glb");record.put("sourceGlbSha256",hash(input.resolve("source.glb")));
            record.put("meshBundle","src/tools/assets/vehicles/"+id+"/mesh.json.gz");record.put("meshBundleSha256",hash(input.resolve("mesh.json.gz")));
            record.put("sourceManifest","src/tools/assets/vehicles/"+id+"/source.json");record.put("sourceManifestSha256",hash(input.resolve("source.json")));
            var textureRecords=new LinkedHashMap<String,String>();for(String map:MAPS){String name=map+(map.equals("diffuse")?".dds":".png");textureRecords.put("textures/vehicles/"+id+"/"+name,hash(textures.resolve(name)));}
            record.put("textures",textureRecords);var textureFormats=new LinkedHashMap<String,String>();for(String path:textureRecords.keySet())textureFormats.put(path,textureFormat(Path.of(path)));record.put("textureFormats",textureFormats);records.add(record);System.out.println("Prepared vehicle "+id+" "+counts);
        }
        var manifest=new LinkedHashMap<String,Object>();manifest.put("damageAtlas","textures/vehicles/shared/damage-atlas.png");manifest.put("damageAtlasSha256",hash(shared.resolve("damage-atlas.png")));manifest.put("sharedSource","src/tools/assets/vehicles/shared/source.json");manifest.put("sharedSourceSha256",hash(source.resolve("shared/source.json")));
        var sharedTextures=new LinkedHashMap<String,String>();for(String name:List.of("metal-diffuse.dds","metal-normal.png","metal-specular.png"))sharedTextures.put("textures/vehicles/shared/"+name,hash(shared.resolve(name)));manifest.put("sharedTextures",sharedTextures);var sharedFormats=new LinkedHashMap<String,String>();for(String path:sharedTextures.keySet())sharedFormats.put(path,textureFormat(Path.of(path)));manifest.put("sharedTextureFormats",sharedFormats);manifest.put("schemaVersion",1);manifest.put("origin","ORIGINAL_BLENDER_CONTENT");
        manifest.put("diffuseCompression","textures/vehicles/diffuse-provenance.json");manifest.put("diffuseCompressionSha256",hash(diffuseManifest));
        manifest.put("generator",GENERATOR);manifest.put("generatorSha256",hash(Path.of(GENERATOR)));manifest.put("converter",CONVERTER);manifest.put("converterSha256",hash(Path.of(CONVERTER)));
        manifest.put("blenderVersion","4.5.9 LTS");manifest.put("licensePermission","Original Wreck Riff models and procedural texture recipes; no external geometry or bitmap sources.");
        manifest.put("coordinateSystem","metres, +Y up, +Z forward, +X right; immutable profile sockets");manifest.put("uv","Shared nonoverlapping packed canonical UV islands; lower LOD triangle projection transferred offline; 512 ownership map clips brush footprints to their part");
        manifest.put("textureContract","Paint diffuse/normal 2048; specular and metal maps 1024. Diffuse BC7_UNORM_SRGB with complete CPU-prepared mips and one offline Y flip; DX10 selects GL sRGB directly (Image/Material Linear). Normal RGB8 and specular L8 preserve every original sample; ownership/atlas RGBA8. Editable RGBA sources retained, no resize.");manifest.put("sourceAuthority","Saved Blender scene with three LODs, five HP poses, eight regional shape keys; --reexport PROFILE writes GLB and canonical topology companion from edited scene");manifest.put("artisticStatus","NEEDS_CREATIVE_REVIEW");manifest.put("profiles",records);
        Path provenance=resources.resolve("models/vehicles/provenance.json");Path temporary=provenance.resolveSibling("provenance.json.prepared");
        Files.writeString(temporary,new GsonBuilder().setPrettyPrinting().create().toJson(manifest)+"\n",StandardCharsets.UTF_8);
        movePrepared(temporary,provenance);
        manager.clearCache();
        for(String id:List.of("rivet","grinder","spark","boss_foreman","boss_prefect","boss_emcee","shared")){String name=id.equals("shared")?"metal-diffuse.png":"diffuse.png";Path old=resources.resolve("textures/vehicles/"+id+"/"+name);if(Files.exists(old)){requirePreservedDiffuse(source.resolve(id+"/"+name),old);Files.delete(old);}}
    }
    private static void publish(Path source,Path target)throws IOException {Path temporary=target.resolveSibling(target.getFileName()+".prepared");Files.copy(source,temporary,StandardCopyOption.REPLACE_EXISTING);movePrepared(temporary,target);}
    private static String textureFormat(Path path){String name=path.getFileName().toString();return name.endsWith(".dds")?"BC7_UNORM_SRGB":name.endsWith("specular.png")?"L8":name.endsWith("normal.png")?"RGB8":"RGBA8";}
    private static void requirePreservedDiffuse(Path source,Path old)throws IOException {var a=ImageIO.read(source.toFile());var b=ImageIO.read(old.toFile());if(a==null||b==null||a.getWidth()!=b.getWidth()||a.getHeight()!=b.getHeight())throw new IOException("Cannot retire unpreserved diffuse "+old);for(int y=0;y<a.getHeight();y++)for(int x=0;x<a.getWidth();x++)if(a.getRGB(x,y)!=b.getRGB(x,y))throw new IOException("Runtime diffuse was edited outside source: "+old);}
    static void publishTexture(Path source,Path target)throws IOException {
        String format=textureFormat(target);if(format.equals("RGBA8")){publish(source,target);return;}
        BufferedImage original=ImageIO.read(source.toFile());if(original==null)throw new IOException("Cannot decode vehicle texture "+source);
        boolean scalar=format.equals("L8");BufferedImage prepared=new BufferedImage(original.getWidth(),original.getHeight(),scalar?BufferedImage.TYPE_BYTE_GRAY:BufferedImage.TYPE_3BYTE_BGR);
        int[] row=new int[original.getWidth()];
        for(int y=0;y<original.getHeight();y++) {
            original.getRGB(0,y,row.length,1,row,0,row.length);
            for(int x=0;x<row.length;x++) {int pixel=row[x];if((pixel>>>24)!=255)throw new IOException("Cannot remove meaningful alpha from "+source);
                if(scalar){int value=pixel&255;if((pixel>>>8&255)!=value||(pixel>>>16&255)!=value)throw new IOException("Cannot collapse colored specular channels in "+source);row[x]=value;}}
            // Write raw scalar samples: setRGB on TYPE_BYTE_GRAY would apply AWT's gray color-space transfer.
            if(scalar)prepared.getRaster().setSamples(0,y,row.length,1,0,row);else prepared.setRGB(0,y,row.length,1,row,0,row.length);
        }
        Path temporary=target.resolveSibling(target.getFileName()+".prepared");if(!ImageIO.write(prepared,"png",temporary.toFile()))throw new IOException("PNG writer unavailable");movePrepared(temporary,target);
    }
    private static void movePrepared(Path temporary,Path target)throws IOException {
        // Windows thumbnailers and asset readers can briefly hold a sharing lock after a bake.
        // Keep the previous complete output until replacement succeeds; never truncate it.
        for(int attempt=0;;attempt++)try {
            Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);return;
        } catch(FileSystemException error) {
            if(error instanceof AtomicMoveNotSupportedException||attempt==6)throw error;
            try {Thread.sleep(100L*(attempt+1));}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("Interrupted publishing "+target,interrupted);}
        }
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

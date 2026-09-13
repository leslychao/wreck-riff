package game.wreckriff.presentation;

import com.google.gson.*;
import com.jme3.asset.DesktopAssetManager;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.scene.*;
import game.wreckriff.diagnostics.VerifyAssets;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;
import javax.imageio.ImageIO;

/** Offline source/export validation. Passing proves contracts, not artistic acceptance. */
public final class VehicleAssetsVerifier {
    private VehicleAssetsVerifier(){}
    public static void verify() throws Exception {verify(new ArrayList<>());}
    public static void verify(List<VerifyAssets.Asset> assets) throws Exception {
        long resident=16L*512*512*4; // Peak visible vehicles plus preview damage masks; no mipmaps on dynamic masks.
        Path resources=Path.of("src/main/resources"),file=resources.resolve("models/vehicles/provenance.json");
        var manifest=JsonParser.parseString(new String(read(file),StandardCharsets.UTF_8)).getAsJsonObject();add(assets,file,"SOURCE_EXPORT_PROVENANCE_VERIFIED");
        if(manifest.get("schemaVersion").getAsInt()!=1||!manifest.get("origin").getAsString().equals("ORIGINAL_BLENDER_CONTENT"))throw new IOException("Invalid authored vehicle provenance");
        check(assets,Path.of(manifest.get("generator").getAsString()),manifest.get("generatorSha256").getAsString());
        check(assets,Path.of(manifest.get("converter").getAsString()),manifest.get("converterSha256").getAsString());
        var manager=new DesktopAssetManager(true);manager.registerLocator(resources.toAbsolutePath().toString(),FileLocator.class);
        Set<String> required=new HashSet<>(List.of("rivet","grinder","spark","boss_foreman","boss_prefect","boss_emcee"));
        for(var item:manifest.getAsJsonArray("profiles")) {
            var record=item.getAsJsonObject();String id=record.get("id").getAsString();if(!required.remove(id)||record.get("stages").getAsInt()!=5||record.get("regions").getAsInt()!=8)throw new IOException("Missing/duplicate vehicle profile "+id);
            for(String name:List.of("sourceBlend","sourceGlb","meshBundle","sourceManifest"))check(assets,Path.of(record.get(name).getAsString()),record.get(name+"Sha256").getAsString());
            Path sourceManifest=Path.of(record.get("sourceManifest").getAsString());var bake=JsonParser.parseString(new String(read(sourceManifest),StandardCharsets.UTF_8)).getAsJsonObject();
            if(!bake.get("generatorSha256").getAsString().equals(manifest.get("generatorSha256").getAsString()))throw new IOException("Stale authoring recipe in vehicle bake "+id);
            for(var entry:bake.getAsJsonObject("exports").entrySet())if(entry.getKey().endsWith(".png"))check(assets,sourceManifest.getParent().resolve(entry.getKey()),entry.getValue().getAsString());
            for(String name:List.of("model","regional"))check(assets,resources.resolve(record.get(name).getAsString()),record.get(name+"Sha256").getAsString());
            for(var texture:record.getAsJsonObject("textures").entrySet()) {
                Path path=resources.resolve(texture.getKey());check(assets,path,texture.getValue().getAsString());var image=ImageIO.read(path.toFile());
                int size=path.getFileName().toString().equals("damage-ownership.png")?512:path.getFileName().toString().equals("diffuse.png")||path.getFileName().toString().equals("normal.png")?2048:1024;
                if(image==null||image.getWidth()!=size||image.getHeight()!=size)throw new IOException("Vehicle texture violates 2Kpaint/normal, 1Kspec/metal contract "+path);
                for(int level=size;level>=1;level/=2)resident+=(long)level*level*4;
                byte[] header=Files.readAllBytes(path);if(header[24]!=8)throw new IOException("Vehicle runtime PNG must be 8 bit "+path);
                Set<Integer> colors=new HashSet<>();for(int y=7;y<size;y+=31)for(int x=11;x<size;x+=29)colors.add(image.getRGB(x,y));if(!path.getFileName().toString().equals("damage-ownership.png")&&colors.size()<40)throw new IOException("Flat placeholder vehicle texture "+path);
            }
            Node bank=(Node)manager.loadModel(record.get("model").getAsString());JsonObject regions;
            try(var stream=new GZIPInputStream(Files.newInputStream(resources.resolve(record.get("regional").getAsString())))){regions=JsonParser.parseReader(new InputStreamReader(stream,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();}
            for(int lod=0;lod<3;lod++) {
                Node level=(Node)bank.getChild("lod"+lod),base=(Node)level.getChild("stage0");int total=0;
                for(Spatial spatial:base.getChildren()) {
                    Geometry geometry=(Geometry)spatial;Mesh original=geometry.getMesh();total+=original.getTriangleCount();
                    for(int stage=0;stage<5;stage++) {
                        var mesh=((Geometry)((Node)level.getChild("stage"+stage)).getChild(spatial.getName())).getMesh();
                        if(spatial.getName().startsWith("panel-")&&lod==2&&mesh.getTriangleCount()>96)throw new IOException("Detached panel exceeds bounded batch budget "+id);
                        if(mesh.getVertexCount()!=original.getVertexCount()||mesh.getTriangleCount()!=original.getTriangleCount()||mesh.hasMorphTargets())throw new IOException("Invalid immutable stage topology "+id+"/"+spatial.getName());
                        for(var attribute:List.of(VertexBuffer.Type.Position,VertexBuffer.Type.Normal,VertexBuffer.Type.Tangent,VertexBuffer.Type.TexCoord)) {
                            var data=mesh.getFloatBuffer(attribute);if(data==null)throw new IOException("Missing vehicle attribute "+attribute);
                            for(int i=0;i<data.limit();i++)if(!Float.isFinite(data.get(i))||(attribute==VertexBuffer.Type.TexCoord&&(data.get(i)<0||data.get(i)>1)))throw new IOException("Invalid vehicle vertex data");
                        }
                        var uv=mesh.getFloatBuffer(VertexBuffer.Type.TexCoord);if(!uv.equals(original.getFloatBuffer(VertexBuffer.Type.TexCoord)))throw new IOException("HP stage changes canonical UV "+id);
                    }
                    var local=regions.getAsJsonArray("lods").get(lod).getAsJsonObject().getAsJsonArray(spatial.getName());if(local.size()!=8)throw new IOException("Missing regional vehicle deltas");
                    for(var entry:local){var delta=entry.getAsJsonObject();var indices=delta.getAsJsonArray("indices");if(delta.getAsJsonArray("delta").size()!=indices.size()*3)throw new IOException("Sparse delta length mismatch");for(var index:indices)if(index.getAsInt()<0||index.getAsInt()>=original.getVertexCount())throw new IOException("Sparse delta vertex out of range");}
                }
                int limit=id.startsWith("boss_")?new int[]{32000,12000,3000}[lod]:new int[]{24000,8000,2000}[lod];
                if(total!=record.getAsJsonArray("lodTriangles").get(lod).getAsInt()||total>limit||total<300)throw new IOException("Vehicle LOD budget mismatch "+id+"/"+lod);
            }
        }
        Path sharedSource=Path.of(manifest.get("sharedSource").getAsString());check(assets,sharedSource,manifest.get("sharedSourceSha256").getAsString());var shared=JsonParser.parseString(new String(read(sharedSource),StandardCharsets.UTF_8)).getAsJsonObject();
        if(!shared.get("generatorSha256").getAsString().equals(manifest.get("generatorSha256").getAsString()))throw new IOException("Shared metal/damage recipe is stale");
        for(var entry:shared.getAsJsonObject("exports").entrySet())check(assets,sharedSource.getParent().resolve(entry.getKey()),entry.getValue().getAsString());
        for(var entry:manifest.getAsJsonObject("sharedTextures").entrySet()){Path path=resources.resolve(entry.getKey());check(assets,path,entry.getValue().getAsString());var image=ImageIO.read(path.toFile());if(image.getWidth()!=1024||image.getHeight()!=1024)throw new IOException("Shared metal maps must be1K");for(int size=1024;size>=1;size/=2)resident+=(long)size*size*4;}
        check(assets,resources.resolve(manifest.get("damageAtlas").getAsString()),manifest.get("damageAtlasSha256").getAsString());resident+=1024*1024;
        if(!required.isEmpty())throw new IOException("Missing vehicle profiles "+required);
        try(var paths=Files.list(resources.resolve("textures/vfx"))){for(Path texture:paths.filter(p->p.toString().endsWith(".png")).toList()){var image=ImageIO.read(new ByteArrayInputStream(read(texture)));for(int size=Math.max(image.getWidth(),image.getHeight());size>=1;size/=2)resident+=(long)size*size*4;}}
        if(resident>512L*1024*1024)throw new IOException("Vehicle + VFX decoded textures and damage masks exceed 512MiB: "+resident);
        for(String shader:List.of("VehicleLighting.j3md","VehicleLighting.frag","VehicleSPLighting.frag","VehicleFrost.j3md","VehicleFrost.vert","VehicleFrost.frag"))add(assets,resources.resolve("materials/"+shader),"SHADER_SOURCE_PRESENT");
        add(assets,resources.resolve("licenses/jme-BSD3.txt"),"UPSTREAM_LIGHTING_LICENSE_PRESENT");
        for(String name:List.of("VehicleVisual","PlayerVehicleVisual","BossVehicleVisual","VehicleDamageVisual")){Path history=Path.of("src/tools/assets/vehicles/history/"+name+"-before-authored.java.txt");if(!Files.isRegularFile(history))throw new IOException("Original vehicle source history missing");add(assets,history,"ORIGINAL_SOURCE_RETAINED");}
    }
    private static byte[] read(Path path)throws IOException {
        byte[] bytes=Files.readAllBytes(path);
        if(new String(bytes,0,Math.min(bytes.length,64),StandardCharsets.US_ASCII).startsWith("version https://git-lfs.github.com/spec/"))throw new IOException("Unresolved Git LFS pointer: "+path+"; checkout prepared assets before build");
        return bytes;
    }
    private static void check(List<VerifyAssets.Asset> assets,Path path,String expected)throws Exception {byte[] bytes=read(path);if(!hash(bytes).equals(expected))throw new IOException("Vehicle asset/source checksum mismatch "+path);add(assets,path,"SOURCE_AND_EXPORT_HASH_VERIFIED");}
    private static void add(List<VerifyAssets.Asset> assets,Path path,String status)throws Exception {
        byte[] bytes=read(path);String name=path.toString().replace('\\','/').replace("src/main/resources/","");
        String source=name.startsWith("materials/VehicleLighting")||name.endsWith("VehicleSPLighting.frag")?"jMonkeyEngine 3.8.1 Lighting, BSD-3; original damage/heat additions":"src/tools/author_vehicle_models.py; original editable Blender vehicle source";
        assets.add(new VerifyAssets.Asset(name,"authored-vehicle",bytes.length,hash(bytes),source,status,null,null,null,null));
    }
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}

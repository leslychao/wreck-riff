package game.wreckriff.presentation;

import com.google.gson.*;
import com.jme3.asset.DesktopAssetManager;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.scene.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;
import javax.imageio.ImageIO;

/** Offline source/export validation. Passing proves contracts, not artistic acceptance. */
public final class VehicleAssetsVerifier {
    private VehicleAssetsVerifier(){}
    public static void verify() throws Exception {
        Path resources=Path.of("src/main/resources"),file=resources.resolve("models/vehicles/provenance.json");
        var manifest=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        if(manifest.get("schemaVersion").getAsInt()!=1||!manifest.get("origin").getAsString().equals("ORIGINAL_BLENDER_CONTENT"))throw new IOException("Invalid authored vehicle provenance");
        check(Path.of(manifest.get("generator").getAsString()),manifest.get("generatorSha256").getAsString());
        check(Path.of(manifest.get("converter").getAsString()),manifest.get("converterSha256").getAsString());
        var manager=new DesktopAssetManager(true);manager.registerLocator(resources.toAbsolutePath().toString(),FileLocator.class);
        Set<String> required=new HashSet<>(List.of("rivet","grinder","spark","boss_foreman","boss_prefect","boss_emcee"));
        for(var item:manifest.getAsJsonArray("profiles")) {
            var record=item.getAsJsonObject();String id=record.get("id").getAsString();if(!required.remove(id)||record.get("stages").getAsInt()!=5||record.get("regions").getAsInt()!=8)throw new IOException("Missing/duplicate vehicle profile "+id);
            for(String name:List.of("sourceBlend","sourceGlb","meshBundle"))check(Path.of(record.get(name).getAsString()),record.get(name+"Sha256").getAsString());
            for(String name:List.of("model","regional"))check(resources.resolve(record.get(name).getAsString()),record.get(name+"Sha256").getAsString());
            for(var texture:record.getAsJsonObject("textures").entrySet()) {
                Path path=resources.resolve(texture.getKey());check(path,texture.getValue().getAsString());var image=ImageIO.read(path.toFile());
                if(image==null||image.getWidth()!=2048||image.getHeight()!=2048)throw new IOException("Vehicle texture must be 2K "+path);
                byte[] header=Files.readAllBytes(path);if(header[24]!=8)throw new IOException("Vehicle runtime PNG must be 8 bit "+path);
                Set<Integer> colors=new HashSet<>();for(int y=7;y<2048;y+=31)for(int x=11;x<2048;x+=29)colors.add(image.getRGB(x,y));if(colors.size()<40)throw new IOException("Flat placeholder vehicle texture "+path);
            }
            Node bank=(Node)manager.loadModel(record.get("model").getAsString());JsonObject regions;
            try(var stream=new GZIPInputStream(Files.newInputStream(resources.resolve(record.get("regional").getAsString())))){regions=JsonParser.parseReader(new InputStreamReader(stream,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();}
            for(int lod=0;lod<3;lod++) {
                Node level=(Node)bank.getChild("lod"+lod),base=(Node)level.getChild("stage0");int total=0;
                for(Spatial spatial:base.getChildren()) {
                    Geometry geometry=(Geometry)spatial;Mesh original=geometry.getMesh();total+=original.getTriangleCount();
                    for(int stage=0;stage<5;stage++) {
                        var mesh=((Geometry)((Node)level.getChild("stage"+stage)).getChild(spatial.getName())).getMesh();
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
        if(!required.isEmpty())throw new IOException("Missing vehicle profiles "+required);
        for(String name:List.of("VehicleVisual","PlayerVehicleVisual","BossVehicleVisual","VehicleDamageVisual"))if(!Files.isRegularFile(Path.of("src/tools/assets/vehicles/history/"+name+"-before-authored.java.txt")))throw new IOException("Original vehicle source history missing");
    }
    private static void check(Path path,String expected)throws Exception {byte[] bytes=Files.readAllBytes(path);if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(expected))throw new IOException("Vehicle asset/source checksum mismatch "+path);}
}

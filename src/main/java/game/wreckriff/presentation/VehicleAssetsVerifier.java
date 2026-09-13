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
        verifyOwned(assets,new DesktopAssetManager(true));
    }
    /** The supplied manager belongs only to this verification run, including failure paths. */
    static void verifyOwned(List<VerifyAssets.Asset> assets,DesktopAssetManager manager) throws Exception {
        try {verifyContents(assets,manager);} finally {manager.clearCache();}
    }
    private static void verifyContents(List<VerifyAssets.Asset> assets,DesktopAssetManager manager) throws Exception {
        long resident=16L*512*512*4; // Peak visible vehicles plus preview damage masks; no mipmaps on dynamic masks.
        Path resources=Path.of("src/main/resources"),file=resources.resolve("models/vehicles/provenance.json");
        var manifest=JsonParser.parseString(new String(read(file),StandardCharsets.UTF_8)).getAsJsonObject();add(assets,file,"SOURCE_EXPORT_PROVENANCE_VERIFIED");
        if(manifest.get("schemaVersion").getAsInt()!=1||!manifest.get("origin").getAsString().equals("ORIGINAL_BLENDER_CONTENT"))throw new IOException("Invalid authored vehicle provenance");
        check(assets,Path.of(manifest.get("generator").getAsString()),manifest.get("generatorSha256").getAsString());
        check(assets,Path.of(manifest.get("converter").getAsString()),manifest.get("converterSha256").getAsString());
        Path compressed=resources.resolve(manifest.get("diffuseCompression").getAsString());check(assets,compressed,manifest.get("diffuseCompressionSha256").getAsString());verifyDiffusePreparation(assets,resources,compressed);
        manager.registerLocator(resources.toAbsolutePath().toString(),FileLocator.class);
        Set<String> required=new HashSet<>(List.of("rivet","grinder","spark","boss_foreman","boss_prefect","boss_emcee"));
        for(var item:manifest.getAsJsonArray("profiles")) {
            var record=item.getAsJsonObject();String id=record.get("id").getAsString();if(!required.remove(id)||record.get("stages").getAsInt()!=5||record.get("regions").getAsInt()!=8)throw new IOException("Missing/duplicate vehicle profile "+id);
            for(String name:List.of("sourceBlend","sourceGlb","meshBundle","sourceManifest"))check(assets,Path.of(record.get(name).getAsString()),record.get(name+"Sha256").getAsString());
            Path sourceManifest=Path.of(record.get("sourceManifest").getAsString());var bake=JsonParser.parseString(new String(read(sourceManifest),StandardCharsets.UTF_8)).getAsJsonObject();
            if(!bake.get("generatorSha256").getAsString().equals(manifest.get("generatorSha256").getAsString()))throw new IOException("Stale authoring recipe in vehicle bake "+id);
            for(var entry:bake.getAsJsonObject("exports").entrySet())if(entry.getKey().endsWith(".png"))check(assets,sourceManifest.getParent().resolve(entry.getKey()),entry.getValue().getAsString());
            for(String name:List.of("model","regional"))check(assets,resources.resolve(record.get(name).getAsString()),record.get(name+"Sha256").getAsString());
            for(var texture:record.getAsJsonObject("textures").entrySet()) {
                Path path=resources.resolve(texture.getKey());check(assets,path,texture.getValue().getAsString());
                if(path.toString().endsWith(".dds")){if(!record.getAsJsonObject("textureFormats").get(texture.getKey()).getAsString().equals("BC7_UNORM_SRGB"))throw new IOException("Diffuse format provenance mismatch "+path);resident+=VehicleDiffuseDds.read(read(path),2048).payloadBytes();continue;}
                var image=ImageIO.read(path.toFile());
                int size=path.getFileName().toString().equals("damage-ownership.png")?512:path.getFileName().toString().equals("diffuse.png")||path.getFileName().toString().equals("normal.png")?2048:1024;
                if(image==null||image.getWidth()!=size||image.getHeight()!=size)throw new IOException("Vehicle texture violates 2Kpaint/normal, 1Kspec/metal contract "+path);
                String format=runtimeTextureFormat(path);if(!record.getAsJsonObject("textureFormats").get(texture.getKey()).getAsString().equals(format))throw new IOException("Vehicle texture format provenance mismatch "+path);
                resident+=verifyTextureChannels(path,sourceManifest.getParent().resolve(path.getFileName()),format);
                Set<Integer> colors=new HashSet<>();for(int y=7;y<size;y+=31)for(int x=11;x<size;x+=29)colors.add(format.equals("L8")?image.getRaster().getSample(x,y,0):image.getRGB(x,y));if(!path.getFileName().toString().equals("damage-ownership.png")&&colors.size()<40)throw new IOException("Flat placeholder vehicle texture "+path);
            }
            Node bank=(Node)manager.loadModel(record.get("model").getAsString());JsonObject regions;
            try(var stream=new GZIPInputStream(Files.newInputStream(resources.resolve(record.get("regional").getAsString())))){regions=JsonParser.parseReader(new InputStreamReader(stream,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();}
            for(int lod=0;lod<3;lod++) {
                Node level=(Node)bank.getChild("lod"+lod),base=(Node)level.getChild("stage0");int total=0;
                for(Spatial spatial:base.getChildren()) {
                    Geometry geometry=(Geometry)spatial;Mesh original=geometry.getMesh();total+=original.getTriangleCount();
                    for(int stage=0;stage<5;stage++) {
                        var preparedGeometry=(Geometry)((Node)level.getChild("stage"+stage)).getChild(spatial.getName());var mesh=preparedGeometry.getMesh();
                        var diffuse=preparedGeometry.getMaterial().getTextureParam("DiffuseMap");if(diffuse!=null){var texture=diffuse.getTextureValue();if(!texture.getKey().getName().endsWith(".dds")||texture.getImage().getFormat()!=com.jme3.texture.Image.Format.BC7_UNORM_SRGB||texture.getImage().getColorSpace()!=com.jme3.texture.image.ColorSpace.Linear)throw new IOException("Vehicle bank retained an uncompressed or double-converted diffuse: "+id+"/"+spatial.getName());}
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
        for(var entry:manifest.getAsJsonObject("sharedTextures").entrySet()){Path path=resources.resolve(entry.getKey());check(assets,path,entry.getValue().getAsString());String format=runtimeTextureFormat(path);if(!manifest.getAsJsonObject("sharedTextureFormats").get(entry.getKey()).getAsString().equals(format))throw new IOException("Shared texture format provenance mismatch "+path);if(format.equals("BC7_UNORM_SRGB")){resident+=VehicleDiffuseDds.read(read(path),1024).payloadBytes();continue;}var image=ImageIO.read(path.toFile());if(image.getWidth()!=1024||image.getHeight()!=1024)throw new IOException("Shared metal maps must be1K");resident+=verifyTextureChannels(path,sharedSource.getParent().resolve(path.getFileName()),format);}
        Path atlas=resources.resolve(manifest.get("damageAtlas").getAsString());check(assets,atlas,manifest.get("damageAtlasSha256").getAsString());resident+=verifyTextureChannels(atlas,sharedSource.getParent().resolve(atlas.getFileName()),"RGBA8");
        if(!required.isEmpty())throw new IOException("Missing vehicle profiles "+required);
        for(String family:List.of("smoke","flame","blast","dust"))resident+=CombatVfxDds.read(read(resources.resolve("textures/vfx/"+family+".dds")),2048).payloadBytes();
        {var image=ImageIO.read(new ByteArrayInputStream(read(resources.resolve("textures/vfx/auxiliary.png"))));resident+=mipBytes(image.getWidth(),image.getHeight(),4);}
        if(resident>512L*1024*1024)throw new IOException("Vehicle + VFX decoded textures and damage masks exceed 512MiB: "+resident);
        Path morphFile=resources.resolve("materials/vehicle-morph-provenance.json");var morph=JsonParser.parseString(new String(read(morphFile),StandardCharsets.UTF_8)).getAsJsonObject();add(assets,morphFile,"UPSTREAM_TRANSFORMATION_PROVENANCE_VERIFIED");
        if(!morph.get("engineVersion").getAsString().equals("3.8.1-stable"))throw new IOException("Vehicle morph fork requires the pinned engine version");
        check(assets,Path.of(morph.get("generator").getAsString()),morph.get("generatorSha256").getAsString());
        check(assets,resources.resolve(morph.get("license").getAsString()),morph.get("licenseSha256").getAsString());
        Set<String> morphPaths=new HashSet<>(List.of("VehicleMorph.glsllib","VehicleLighting.vert","VehicleSPLighting.vert","VehiclePreShadow.vert","VehiclePostShadow.vert","VehicleNormal.vert","VehicleUnshaded.vert","VehicleUnshaded.j3md"));
        for(var item:morph.getAsJsonArray("files")){var record=item.getAsJsonObject();Path path=resources.resolve(record.get("path").getAsString());if(!morphPaths.remove(path.getFileName().toString()))throw new IOException("Unexpected or duplicate vehicle morph shader "+path);check(assets,path,record.get("sha256").getAsString());check(assets,Path.of(record.get("sourcePath").getAsString()),record.get("sourceSha256").getAsString());}
        if(!morphPaths.isEmpty())throw new IOException("Missing vehicle morph/shadow techniques "+morphPaths);
        for(String shader:List.of("VehicleLighting.j3md","VehicleLighting.frag","VehicleSPLighting.frag","VehicleFrost.j3md","VehicleFrost.vert","VehicleFrost.frag"))add(assets,resources.resolve("materials/"+shader),"SHADER_SOURCE_PRESENT");
        for(String name:List.of("VehicleVisual","PlayerVehicleVisual","BossVehicleVisual","VehicleDamageVisual")){Path history=Path.of("src/tools/assets/vehicles/history/"+name+"-before-authored.java.txt");if(!Files.isRegularFile(history))throw new IOException("Original vehicle source history missing");add(assets,history,"ORIGINAL_SOURCE_RETAINED");}
    }
    private static byte[] read(Path path)throws IOException {
        byte[] bytes=Files.readAllBytes(path);
        if(new String(bytes,0,Math.min(bytes.length,64),StandardCharsets.US_ASCII).startsWith("version https://git-lfs.github.com/spec/"))throw new IOException("Unresolved Git LFS pointer: "+path+"; checkout prepared assets before build");
        return bytes;
    }
    private static void verifyDiffusePreparation(List<VerifyAssets.Asset> assets,Path resources,Path path)throws Exception {
        var data=JsonParser.parseString(new String(read(path),StandardCharsets.UTF_8)).getAsJsonObject();
        if(data.get("schemaVersion").getAsInt()!=1||!data.get("format").getAsString().equals("BC7_UNORM_SRGB")||!data.get("offlineFlipY").getAsBoolean()||data.get("loaderFlipY").getAsBoolean())throw new IOException("Invalid vehicle diffuse encoding contract");
        check(assets,Path.of(data.get("converter").getAsString()),data.get("converterSha256").getAsString());check(assets,resources.resolve(data.get("license").getAsString()),data.get("licenseSha256").getAsString());
        if(!data.get("toolSha256").getAsString().equals("dcfdec10244e02cf5037fba089c55fb7e1326b1c8181742d77d15fa5cb5eef06"))throw new IOException("Unpinned vehicle diffuse encoder");
        Set<String> expected=new HashSet<>(List.of("rivet","grinder","spark","boss_foreman","boss_prefect","boss_emcee","shared"));
        for(var item:data.getAsJsonArray("textures")){var entry=item.getAsJsonObject();Path runtime=resources.resolve(entry.get("runtime").getAsString());String id=runtime.getParent().getFileName().toString();
            if(!expected.remove(id))throw new IOException("Unexpected or duplicate compressed vehicle diffuse "+runtime);
            check(assets,Path.of(entry.get("source").getAsString()),entry.get("sourceSha256").getAsString());check(assets,runtime,entry.get("runtimeSha256").getAsString());
            var dds=VehicleDiffuseDds.read(read(runtime),id.equals("shared")?1024:2048);
            if(dds.payloadBytes()!=entry.get("payloadBytes").getAsLong()||dds.levels()!=entry.get("mipLevels").getAsInt()||!entry.get("repeatSha256").getAsString().equals(entry.get("runtimeSha256").getAsString())||!entry.get("status").getAsString().equals("PASS")||entry.get("rgbRmse8bit").getAsDouble()>1.2||entry.get("rgbMax8bit").getAsInt()>16||entry.get("decodedAlphaMin").getAsInt()<254)throw new IOException("Vehicle diffuse quality/repeat evidence invalid "+runtime);
            if(Files.exists(runtime.resolveSibling(runtime.getFileName().toString().replace(".dds",".png"))))throw new IOException("Retired runtime vehicle diffuse PNG still exists "+runtime);
        }
        if(!expected.isEmpty())throw new IOException("Missing compressed vehicle diffuse "+expected);
    }
    private static String runtimeTextureFormat(Path path){String name=path.getFileName().toString();return name.endsWith(".dds")?"BC7_UNORM_SRGB":name.endsWith("specular.png")?"L8":name.endsWith("normal.png")?"RGB8":"RGBA8";}
    private static long mipBytes(int width,int height,int channels){long bytes=0;for(;;){bytes+=(long)width*height*channels;if(width==1&&height==1)return bytes;width=Math.max(1,width/2);height=Math.max(1,height/2);}}
    static long verifyTextureChannels(Path runtime,Path source,String format)throws IOException {
        byte[] bytes=read(runtime);int channels=switch(format){case "L8"->1;case "RGB8"->3;case "RGBA8"->4;default->throw new IOException("Unknown vehicle texture format "+format);};
        int colorType=channels==1?0:channels==3?2:6;
        if(bytes.length<26||bytes[24]!=8||bytes[25]!=colorType)throw new IOException("Vehicle runtime PNG violates "+format+" contract "+runtime);
        var prepared=ImageIO.read(new ByteArrayInputStream(bytes));var original=ImageIO.read(source.toFile());
        if(prepared==null||original==null||prepared.getWidth()!=original.getWidth()||prepared.getHeight()!=original.getHeight())throw new IOException("Lossless vehicle texture dimensions differ "+runtime);
        int width=prepared.getWidth();int[] before=new int[width],after=new int[width];
        for(int y=0;y<prepared.getHeight();y++) {
            original.getRGB(0,y,width,1,before,0,width);
            if(channels==1)prepared.getRaster().getSamples(0,y,width,1,0,after);else prepared.getRGB(0,y,width,1,after,0,width);
            for(int x=0;x<width;x++) {int pixel=before[x];
                if(channels<4&&(pixel>>>24)!=255)throw new IOException("Vehicle alpha was discarded "+runtime);
                if(channels==1){int value=pixel&255;if((pixel>>>8&255)!=value||(pixel>>>16&255)!=value||after[x]!=value)throw new IOException("Vehicle scalar samples changed "+runtime);}
                else if(after[x]!=pixel)throw new IOException("Vehicle color samples changed "+runtime);
            }
        }
        return mipBytes(width,prepared.getHeight(),channels);
    }
    private static void check(List<VerifyAssets.Asset> assets,Path path,String expected)throws Exception {byte[] bytes=read(path);if(!hash(bytes).equals(expected))throw new IOException("Vehicle asset/source checksum mismatch "+path);add(assets,path,"SOURCE_AND_EXPORT_HASH_VERIFIED");}
    private static void add(List<VerifyAssets.Asset> assets,Path path,String status)throws Exception {
        byte[] bytes=read(path);String name=path.toString().replace('\\','/').replace("src/main/resources/","");
        String source=(name.startsWith("materials/Vehicle")&&!name.startsWith("materials/VehicleFrost"))||name.contains("vehicle-morph/")?"jMonkeyEngine 3.8.1, BSD-3; vehicle morph fixes and original damage/heat additions":"src/tools/author_vehicle_models.py; original editable Blender vehicle source";
        assets.add(new VerifyAssets.Asset(name,"authored-vehicle",bytes.length,hash(bytes),source,status,null,null,null,null));
    }
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}

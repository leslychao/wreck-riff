package game.wreckriff.diagnostics;

import com.google.gson.*;
import com.jme3.asset.DesktopAssetManager;
import com.jme3.bounding.BoundingBox;
import com.jme3.material.MatParamTexture;
import com.jme3.math.Vector3f;
import com.jme3.scene.*;
import com.jme3.texture.Texture;
import com.jme3.texture.image.ColorSpace;
import game.wreckriff.presentation.OrdnanceStyle;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Exact exported geometry and texture checks, deliberately independent of owner art acceptance. */
public final class OrdnanceAssetsVerifier {
    private static final String SOURCE="src/tools/java/game/wreckriff/tools/GenerateOrdnance.java";
    private static final String HISTORY="src/tools/assets/ordnance-history/CombatVisuals-before-textured-20260913.java.txt";
    private OrdnanceAssetsVerifier() { }
    public static void verify(List<VerifyAssets.Asset> assets)throws Exception {
        String manifest="models/ordnance/provenance.json";byte[] json=resource(manifest);
        JsonObject provenance=JsonParser.parseString(new String(json,StandardCharsets.UTF_8)).getAsJsonObject();
        if(provenance.get("schemaVersion").getAsInt()!=1||!"ORIGINAL_PROJECT_CONTENT".equals(provenance.get("origin").getAsString())
                ||!SOURCE.equals(provenance.get("generator").getAsString())
                ||!hash(Files.readAllBytes(Path.of(SOURCE))).equals(provenance.get("generatorSha256").getAsString())
                ||provenance.get("externalImages").getAsBoolean()||provenance.get("atlasSize").getAsInt()!=2048
                ||!"src/tools/assets/fonts/RobotoCondensed-Bold.ttf".equals(provenance.get("fontSource").getAsString())
                ||!hash(Files.readAllBytes(Path.of("src/tools/assets/fonts/RobotoCondensed-Bold.ttf"))).equals(provenance.get("fontSourceSha256").getAsString())
                ||!"OFL-1.1".equals(provenance.get("fontLicense").getAsString())||!"licenses/assets/Roboto-OFL.txt".equals(provenance.get("fontLicensePath").getAsString())
                ||!"NEEDS_CREATIVE_REVIEW".equals(provenance.get("artisticStatus").getAsString())
                ||provenance.get("licensePermission").getAsString().isBlank()||provenance.get("transformation").getAsString().isBlank())
            throw new IOException("Ordnance source/provenance mismatch");
        Map<String,OrdnanceStyle> models=new HashMap<>();Set<String> required=new HashSet<>();
        for(var style:OrdnanceStyle.values()){models.put(style.model(),style);required.add(style.model());}
        for(String atlas:List.of("projectiles","mines"))for(var use:OrdnanceStyle.textures(atlas))required.add(use.path());
        Set<String> hashes=new HashSet<>();var manager=new DesktopAssetManager(true);
        for(JsonElement element:provenance.getAsJsonArray("assets")) {
            var entry=element.getAsJsonObject();String path=entry.get("path").getAsString();
            if(!required.remove(path))throw new IOException("Unexpected/duplicate ordnance asset: "+path);
            byte[] bytes=resource(path);String digest=hash(bytes);
            if(!digest.equals(entry.get("sha256").getAsString())||!hashes.add(digest))throw new IOException("Ordnance asset hash mismatch or duplicated content: "+path);
            if(models.containsKey(path))verifyModel(models.get(path).load(manager),models.get(path),entry);
            else verifyTexture(path,bytes);
            assets.add(asset(path,models.containsKey(path)?"ordnance-model":"ordnance-texture",bytes,"MODEL_UV_MATERIAL_HASH_VERIFIED"));
        }
        if(!required.isEmpty())throw new IOException("Missing ordnance assets: "+required);
        assets.add(asset(manifest,"ordnance-provenance",json,"VERIFIED"));
        assets.add(asset(SOURCE,"procedural-source",Files.readAllBytes(Path.of(SOURCE)),"SOURCE_PRESENT"));
        byte[] historic=Files.readAllBytes(Path.of(HISTORY));
        if(!new String(historic,StandardCharsets.UTF_8).contains("rocketBatch"))throw new IOException("Original ordnance recipe missing from history");
        assets.add(asset(HISTORY,"historical-source",historic,"SOURCE_PRESENT"));
    }
    static void verifyTexture(String path,byte[] bytes)throws IOException {
        var image=ImageIO.read(new ByteArrayInputStream(bytes));
        if(image==null||image.getWidth()!=2048||image.getHeight()!=2048)throw new IOException("Expected a 2048x2048 ordnance atlas: "+path);
        Set<Integer> samples=new HashSet<>();for(int y=11;y<2048;y+=17)for(int x=13;x<2048;x+=19)samples.add(image.getRGB(x,y));
        if(samples.size()<80)throw new IOException("Ordnance atlas is flat/placeholder: "+path);
    }
    static void verifyModel(Node model,OrdnanceStyle style,JsonObject evidence)throws IOException {
        if(!style.kind().equals(model.getUserData("ordnanceKind"))||!style.atlas().equals(model.getUserData("atlas"))
                ||!"ORIGINAL_PROJECT_CONTENT".equals(model.getUserData("assetOrigin"))
                ||Math.abs((Float)model.getUserData("nozzleZ")-style.nozzleZ())>.00001f)
            throw new IOException("Ordnance model identity/anchor mismatch: "+style.kind());
        if(!(model.getChild("detail") instanceof Geometry high)||!(model.getChild("distance") instanceof Geometry low))
            throw new IOException("Missing ordnance detail levels: "+style.kind());
        if(high.getMesh().getTriangleCount()!=evidence.get("detailTriangles").getAsInt()
                ||low.getMesh().getTriangleCount()!=evidence.get("distanceTriangles").getAsInt()
                ||high.getMesh().getTriangleCount()<250||high.getMesh().getTriangleCount()>5000
                ||low.getMesh().getTriangleCount()>=high.getMesh().getTriangleCount()*.65f)
            throw new IOException("Ordnance detail budgets mismatch: "+style.kind());
        model.depthFirstTraversal(spatial->{
            if(spatial.getNumControls()!=0)throw new IllegalArgumentException("Exported ordnance cannot contain runtime controls");
            if(spatial instanceof Geometry geometry)verifyGeometry(geometry);
        });
        for(var geometry:List.of(high,low)) {
            if(!geometry.getMaterial().getMaterialDef().getName().equals("Phong Lighting"))throw new IOException("Body must receive scene lighting: "+style.kind());
            for(var use:OrdnanceStyle.textures(style.atlas())) {
                var param=(MatParamTexture)geometry.getMaterial().getParam(use.parameter());
                if(param==null||!param.getTextureValue().getKey().getName().equals(use.path()))throw new IOException("Wrong/missing ordnance texture: "+style.kind());
                var texture=param.getTextureValue();
                if(texture.getMinFilter()!=Texture.MinFilter.Trilinear||texture.getMagFilter()!=Texture.MagFilter.Bilinear||texture.getAnisotropicFilter()<4
                        ||texture.getImage().getColorSpace()!=(use.color()?ColorSpace.sRGB:ColorSpace.Linear))throw new IOException("Invalid texture sampler/data colour space: "+use.path());
            }
        }
        if((model.getChild("signal")!=null)!=(style.signal()!=null))throw new IOException("Missing/extra ordnance active signal: "+style.kind());
        model.updateGeometricState();if(!(model.getWorldBound() instanceof BoundingBox box)||!Vector3f.isValidVector(box.getCenter())
                ||box.getXExtent()>1||box.getYExtent()>1||box.getZExtent()>1)throw new IOException("Ordnance exceeds established visual envelope: "+style.kind());
        if(style==OrdnanceStyle.MINE&&Math.abs(box.getCenter().y-box.getYExtent())>.001f)throw new IOException("Mine origin is not on the contact plane");
    }
    private static void verifyGeometry(Geometry geometry) {
        for(var type:List.of(VertexBuffer.Type.Position,VertexBuffer.Type.Normal,VertexBuffer.Type.TexCoord,VertexBuffer.Type.Tangent)) {
            var values=geometry.getMesh().getFloatBuffer(type);
            if(values==null||values.limit()==0)throw new IllegalArgumentException("Missing ordnance vertex attribute "+type);
            for(int i=0;i<values.limit();i++)if(!Float.isFinite(values.get(i)))throw new IllegalArgumentException("Invalid ordnance vertex attribute "+type);
            if(type==VertexBuffer.Type.TexCoord)for(int i=0;i<values.limit();i++)if(values.get(i)<0||values.get(i)>1)throw new IllegalArgumentException("Ordnance UV outside atlas");
        }
    }
    private static VerifyAssets.Asset asset(String path,String category,byte[] bytes,String status)throws Exception {
        return new VerifyAssets.Asset(path,category,bytes.length,hash(bytes),SOURCE+"; original project content, retained recipe",status,null,null,null,null);
    }
    private static byte[] resource(String path)throws IOException {
        var urls=Collections.list(OrdnanceAssetsVerifier.class.getClassLoader().getResources(path));
        if(urls.size()!=1)throw new IOException("Expected exactly one resource "+path+", got "+urls.size());
        try(var stream=urls.getFirst().openStream()){return stream.readAllBytes();}
    }
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}

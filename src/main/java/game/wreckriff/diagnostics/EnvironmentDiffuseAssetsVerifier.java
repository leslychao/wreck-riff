package game.wreckriff.diagnostics;

import com.google.gson.*;
import game.wreckriff.presentation.EnvironmentDiffuseDds;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Verifies local DDS payloads and the offline source/encoder/repeat evidence without running an encoder. */
public final class EnvironmentDiffuseAssetsVerifier {
    private static final String ENCODER_SHA="dcfdec10244e02cf5037fba089c55fb7e1326b1c8181742d77d15fa5cb5eef06";
    private EnvironmentDiffuseAssetsVerifier(){}
    public static List<VerifyAssets.Asset> verify()throws Exception {
        Path runtime=Path.of("src/main/resources"),file=runtime.resolve("textures/materials/diffuse-provenance.json");
        var manifest=JsonParser.parseString(Files.readString(file)).getAsJsonObject();var assets=new ArrayList<VerifyAssets.Asset>();
        if(manifest.get("schemaVersion").getAsInt()!=1||!manifest.get("format").getAsString().equals("BC7_UNORM_SRGB")
                ||!manifest.get("toolSha256").getAsString().equals(ENCODER_SHA)||!manifest.get("offlineFlipY").getAsBoolean()||manifest.get("loaderFlipY").getAsBoolean())throw new IOException("Invalid environment BC7 preparation contract");
        if(!manifest.get("converter").getAsString().equals("src/tools/java/game/wreckriff/tools/PrepareEnvironmentDiffuse.java")||!manifest.get("license").getAsString().equals("licenses/DirectXTex-MIT.txt"))throw new IOException("Unexpected environment converter/license path");
        if(!manifest.get("gpuDevice").getAsString().equals("NVIDIA GeForce RTX 5080")||!manifest.get("gpuDriver").getAsString().equals("610.62"))throw new IOException("Unverified environment GPU preparation device");
        if(!manifest.get("arguments").equals(new Gson().toJsonTree(List.of("-nologo","-f","BC7_UNORM_SRGB","-srgb","-m","0","-aw","100","-gpu","0","-if","BOX","-vflip","-y"))))throw new IOException("Unexpected environment encoding arguments");
        check(Path.of(manifest.get("converter").getAsString()),manifest.get("converterSha256").getAsString());
        check(runtime.resolve(manifest.get("license").getAsString()),manifest.get("licenseSha256").getAsString());
        var expected=new HashSet<>(EnvironmentDiffuseDds.MATERIALS);long bytes=0;
        for(var element:manifest.getAsJsonArray("textures")){
            var entry=element.getAsJsonObject();String path=entry.get("runtime").getAsString();String[] parts=path.split("/");
            if(parts.length!=4||!parts[0].equals("textures")||!parts[1].equals("materials")||!parts[3].equals("diffuse.dds")||!expected.remove(parts[2]))throw new IOException("Duplicate or unexpected environment diffuse "+path);
            String source="src/tools/assets/materials/"+parts[2]+"/runtime-diffuse.png";if(!entry.get("source").getAsString().equals(source))throw new IOException("Unexpected environment intermediate "+path);
            check(Path.of(source),entry.get("sourceSha256").getAsString());Path texture=runtime.resolve(path);
            if(Files.size(texture)!=5_592_580)throw new IOException("Missing complete BC7 mip payload "+path);
            byte[] data=Files.readAllBytes(texture);var dds=EnvironmentDiffuseDds.read(data);String sha=hash(data);
            if(!sha.equals(entry.get("runtimeSha256").getAsString())||!sha.equals(entry.get("repeatSha256").getAsString())
                    ||entry.get("size").getAsInt()!=2048||entry.get("mipLevels").getAsInt()!=12||entry.get("payloadBytes").getAsLong()!=dds.payloadBytes())throw new IOException("Environment payload/repeat evidence differs "+path);
            if(!entry.get("status").getAsString().equals("PASS")||!finiteAtMost(entry,"rgbRmse8bit",3)||!finiteAtMost(entry,"rgbP99Absolute8bit",8)||!finiteAtMost(entry,"rgbMax8bit",48)
                    ||entry.get("decodedAlphaMin").getAsInt()<254||entry.get("decodedAlphaMax").getAsInt()>255)throw new IOException("Environment decoded quality failed "+path);
            bytes+=dds.payloadBytes();assets.add(new VerifyAssets.Asset(source,"environment-diffuse-source",Files.size(Path.of(source)),entry.get("sourceSha256").getAsString(),entry.get("originalSource").getAsString(),"LOSSLESS_INTERMEDIATE_AND_REPEAT_VERIFIED",null,null,null,null));
        }
        if(!expected.isEmpty()||bytes!=72_701_616)throw new IOException("Incomplete environment BC7 inventory "+expected);
        byte[] manifestBytes=Files.readAllBytes(file);assets.add(new VerifyAssets.Asset("textures/materials/diffuse-provenance.json","environment-bc7",manifestBytes.length,hash(manifestBytes),manifest.get("converter").getAsString(),"PREPARATION_PROVENANCE_VERIFIED",null,null,null,null));return assets;
    }
    private static boolean finiteAtMost(JsonObject object,String key,double maximum){double value=object.get(key).getAsDouble();return Double.isFinite(value)&&value>=0&&value<=maximum;}
    private static void check(Path path,String expected)throws Exception{if(!hash(Files.readAllBytes(path)).equals(expected))throw new IOException("Environment source/preparation checksum mismatch "+path);}
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}

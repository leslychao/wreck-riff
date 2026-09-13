package game.wreckriff.diagnostics;

import com.google.gson.*;
import game.wreckriff.presentation.CombatVfxDds;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Offline bake/provenance integrity. This does not assert creative acceptance. */
public final class CombatVfxAssetsVerifier {
    private static final String SOURCE="src/tools/author_combat_vfx.py";
    private CombatVfxAssetsVerifier() {}
    public static void verify(List<VerifyAssets.Asset> assets)throws Exception {
        byte[] manifest=read("vfx/provenance.json");
        JsonObject provenance=JsonParser.parseString(new String(manifest,StandardCharsets.UTF_8)).getAsJsonObject();
        if(provenance.get("schemaVersion").getAsInt()!=2||!provenance.get("generator").getAsString().equals(SOURCE)
                ||!hash(Files.readAllBytes(Path.of(SOURCE))).equals(provenance.get("generatorSha256").getAsString())
                ||!"ORIGINAL_PROJECT_CONTENT".equals(provenance.get("origin").getAsString())||provenance.get("externalImages").getAsBoolean()
                ||!"NEEDS_CREATIVE_REVIEW".equals(provenance.get("artisticStatus").getAsString()))
            throw new IOException("VFX bake source or provenance mismatch");
        String scene=provenance.get("sourceScene").getAsString();
        if(!scene.equals("src/tools/assets/combat-vfx/combat-volumes.blend")||!hash(Files.readAllBytes(Path.of(scene))).equals(provenance.get("sourceSceneSha256").getAsString()))
            throw new IOException("VFX editable Blender source is missing or changed");
        verifyPreparation(assets,provenance);
        Set<String> remaining=new HashSet<>(List.of("textures/vfx/smoke.dds","textures/vfx/flame.dds","textures/vfx/blast.dds","textures/vfx/dust.dds","textures/vfx/auxiliary.png","vfx/recipes.json"));
        long resident=0;
        for(JsonElement item:provenance.getAsJsonArray("assets")) {
            var entry=item.getAsJsonObject();String path=entry.get("path").getAsString();
            if(!remaining.remove(path))throw new IOException("Unexpected or duplicated VFX asset: "+path);
            byte[] bytes=read(path);
            if(!hash(bytes).equals(entry.get("sha256").getAsString())||bytes.length!=entry.get("bytes").getAsLong())throw new IOException("Changed VFX bake: "+path);
            if(path.endsWith(".dds")) {
                var dds=CombatVfxDds.read(bytes,2048);resident+=dds.payloadBytes();
                int visible=0,transparent=0;
                for(int y=0;y<2048;y++)for(int x=0;x<2048;x++) {
                    int alpha=dds.argb(x,y)>>>24,tx=x%256,ty=y%256;
                    if((tx<8||ty<8||tx>=248||ty>=248)&&alpha!=0)throw new IOException("Nontransparent decoded BC3 gutter: "+path);
                    if(x%4==0&&y%4==0){if(alpha>16)visible++;if(alpha==0)transparent++;}
                }
                if(visible<2000||transparent<2000)throw new IOException("Empty or opaque BC3 VFX atlas "+path);
                if(CombatVfxAssetsVerifier.class.getResource("/"+path.replace(".dds",".png"))!=null)throw new IOException("Retired runtime PNG fallback remains: "+path);
            } else if(path.endsWith(".png")) {
                int size=1024;
                var image=ImageIO.read(new ByteArrayInputStream(bytes));
                if(image==null||image.getWidth()!=size||image.getHeight()!=size||!image.getColorModel().hasAlpha())throw new IOException("Invalid RGBA VFX atlas: "+path);
                int visible=0,transparent=0;
                for(int y=0;y<size;y+=4)for(int x=0;x<size;x+=4){int alpha=image.getRGB(x,y)>>>24;if(alpha>16)visible++;if(alpha==0)transparent++;}
                if(visible<2000||transparent<2000)throw new IOException("Empty or opaque rectangular VFX atlas: "+path);
                for(int level=size;level>=1;level/=2)resident+=(long)level*level*4;
            }
            assets.add(asset(path,bytes,"HASH_LAYOUT_ALPHA_VERIFIED"));
        }
        if(!remaining.isEmpty()||resident>128L*1024*1024)throw new IOException("Incomplete or oversized VFX atlas set: "+remaining+", resident="+resident);
        assets.add(asset("vfx/provenance.json",manifest,"PROVENANCE_VERIFIED"));
        assets.add(asset(SOURCE,Files.readAllBytes(Path.of(SOURCE)),"SOURCE_PRESENT"));
        assets.add(asset(scene,Files.readAllBytes(Path.of(scene)),"EDITABLE_SOURCE_VERIFIED"));
    }
    private static void verifyPreparation(List<VerifyAssets.Asset> assets,JsonObject provenance)throws Exception {
        String bakePath="src/tools/assets/combat-vfx/bake-provenance.json";
        if(!provenance.get("bakeManifest").getAsString().equals(bakePath))throw new IOException("Invalid VFX source manifest path");
        byte[] bakeBytes=Files.readAllBytes(Path.of(bakePath));
        if(!hash(bakeBytes).equals(provenance.get("bakeManifestSha256").getAsString()))throw new IOException("VFX bake changed after compression");
        var bake=JsonParser.parseString(new String(bakeBytes,StandardCharsets.UTF_8)).getAsJsonObject();
        for(String key:List.of("generatorSha256","sourceSceneSha256"))if(!bake.get(key).equals(provenance.get(key)))throw new IOException("VFX compression used a stale bake "+key);
        var expected=new HashSet<String>();for(String family:List.of("smoke","flame","blast","dust"))expected.add("src/tools/assets/combat-vfx/"+family+".png");
        expected.addAll(List.of("src/main/resources/textures/vfx/auxiliary.png","src/main/resources/vfx/recipes.json"));
        var sourceHashes=new HashMap<String,String>();
        for(var item:bake.getAsJsonArray("assets")) {
            var entry=item.getAsJsonObject();String path=entry.get("path").getAsString();if(!expected.remove(path))throw new IOException("Unexpected source VFX bake "+path);
            byte[] bytes=Files.readAllBytes(Path.of(path));if(!hash(bytes).equals(entry.get("sha256").getAsString())||bytes.length!=entry.get("bytes").getAsInt())throw new IOException("Source VFX bake changed "+path);
            sourceHashes.put(path,hash(bytes));assets.add(asset(path,bytes,"SOURCE_BAKE_HASH_VERIFIED"));
        }
        if(!expected.isEmpty())throw new IOException("Missing source VFX bake "+expected);
        var compression=provenance.getAsJsonObject("compression");String converter="src/tools/java/game/wreckriff/tools/PrepareCombatVfx.java";
        if(!compression.get("converter").getAsString().equals(converter)||!hash(Files.readAllBytes(Path.of(converter))).equals(compression.get("converterSha256").getAsString())
                ||!compression.get("toolSha256").getAsString().equals("dcfdec10244e02cf5037fba089c55fb7e1326b1c8181742d77d15fa5cb5eef06")
                ||compression.get("flipY").getAsBoolean()||compression.get("offlineMipLevels").getAsInt()!=12||compression.get("payloadBytesPerAtlas").getAsLong()!=CombatVfxDds.mipBytes(2048))throw new IOException("VFX CPU encoder provenance mismatch");
        var flags=new ArrayList<String>();for(var flag:compression.getAsJsonArray("arguments"))flags.add(flag.getAsString());
        if(!flags.equals(List.of("-nologo","-f","BC3_UNORM_SRGB","-srgb","-dx9","-m","0","-nogpu","-if","BOX","-sepalpha","-y")))throw new IOException("Unexpected VFX compression settings");
        var quality=compression.getAsJsonObject("quality");if(quality.size()!=4)throw new IOException("Incomplete VFX compression quality evidence");
        for(String family:List.of("smoke","flame","blast","dust")) {
            var result=quality.getAsJsonObject(family);String path="textures/vfx/"+family+".dds",source="src/tools/assets/combat-vfx/"+family+".png";
            if(result==null||!result.get("source").getAsString().equals(source)||!result.get("runtime").getAsString().equals(path)
                    ||!result.get("sourceSha256").getAsString().equals(sourceHashes.get(source))||!result.get("runtimeSha256").getAsString().equals(hash(read(path)))
                    ||!result.get("runtimeSha256").equals(result.get("repeatSha256"))||result.get("baseMipGutterNonzeroAlphaPixels").getAsLong()!=0
                    ||!result.get("status").getAsString().equals("PASS")||result.get("alphaRmse8bit").getAsDouble()>1.5||result.get("alphaMax8bit").getAsInt()>20
                    ||result.get("visibleRgbRmse8bit").getAsDouble()>8||result.get("linearCompositeOver18PercentGreyRmse").getAsDouble()>.009)throw new IOException("VFX compression quality/repeat evidence mismatch "+family);
        }
        String license="licenses/DirectXTex-MIT.txt";byte[] licenseBytes=read(license);
        if(!compression.get("license").getAsString().equals(license)||!hash(licenseBytes).equals(compression.get("licenseSha256").getAsString()))throw new IOException("Missing encoder MIT license");
        assets.add(asset(license,licenseBytes,"ENCODER_LICENSE_VERIFIED"));assets.add(asset(converter,Files.readAllBytes(Path.of(converter)),"OFFLINE_ENCODER_SOURCE_VERIFIED"));assets.add(asset(bakePath,bakeBytes,"SOURCE_BAKE_MANIFEST_VERIFIED"));
    }
    private static byte[] read(String path)throws IOException {
        try(var input=CombatVfxAssetsVerifier.class.getResourceAsStream("/"+path)) {
            if(input==null)throw new IOException("Missing offline VFX asset: "+path+"; prepare assets before Gradle");
            byte[] bytes=input.readAllBytes();
            if(new String(bytes,0,Math.min(bytes.length,64),StandardCharsets.US_ASCII).startsWith("version https://git-lfs.github.com/spec/"))
                throw new IOException("Unresolved Git LFS pointer: "+path+"; run LFS checkout before Gradle");
            return bytes;
        }
    }
    private static VerifyAssets.Asset asset(String path,byte[] bytes,String status)throws Exception {
        return new VerifyAssets.Asset(path,"combat-vfx",bytes.length,hash(bytes),SOURCE+"; original volume bake",status,null,null,null,null);
    }
    private static String hash(byte[] bytes)throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}

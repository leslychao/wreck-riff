package game.wreckriff.diagnostics;

import com.google.gson.*;
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
        if(provenance.get("schemaVersion").getAsInt()!=1||!provenance.get("generator").getAsString().equals(SOURCE)
                ||!hash(Files.readAllBytes(Path.of(SOURCE))).equals(provenance.get("generatorSha256").getAsString())
                ||!"ORIGINAL_PROJECT_CONTENT".equals(provenance.get("origin").getAsString())||provenance.get("externalImages").getAsBoolean()
                ||!"NEEDS_CREATIVE_REVIEW".equals(provenance.get("artisticStatus").getAsString()))
            throw new IOException("VFX bake source or provenance mismatch");
        String scene=provenance.get("sourceScene").getAsString();
        if(!scene.equals("src/tools/assets/combat-vfx/combat-volumes.blend")||!hash(Files.readAllBytes(Path.of(scene))).equals(provenance.get("sourceSceneSha256").getAsString()))
            throw new IOException("VFX editable Blender source is missing or changed");
        Set<String> remaining=new HashSet<>(List.of("textures/vfx/smoke.png","textures/vfx/flame.png","textures/vfx/blast.png","textures/vfx/dust.png","textures/vfx/auxiliary.png","vfx/recipes.json"));
        long resident=0;
        for(JsonElement item:provenance.getAsJsonArray("assets")) {
            var entry=item.getAsJsonObject();String path=entry.get("path").getAsString();
            if(!remaining.remove(path))throw new IOException("Unexpected or duplicated VFX asset: "+path);
            byte[] bytes=read(path);
            if(!hash(bytes).equals(entry.get("sha256").getAsString())||bytes.length!=entry.get("bytes").getAsLong())throw new IOException("Changed VFX bake: "+path);
            if(path.endsWith(".png")) {
                int size=path.endsWith("auxiliary.png")?1024:2048;
                var image=ImageIO.read(new ByteArrayInputStream(bytes));
                if(image==null||image.getWidth()!=size||image.getHeight()!=size||!image.getColorModel().hasAlpha())throw new IOException("Invalid RGBA VFX atlas: "+path);
                int visible=0,transparent=0;
                for(int y=0;y<size;y+=4)for(int x=0;x<size;x+=4){int alpha=image.getRGB(x,y)>>>24;if(alpha>16)visible++;if(alpha==0)transparent++;}
                if(visible<2000||transparent<2000)throw new IOException("Empty or opaque rectangular VFX atlas: "+path);
                if(size==2048)for(int y=0;y<size;y++)for(int x=0;x<size;x++) {
                    int tx=x%256,ty=y%256;
                    if((tx<8||ty<8||tx>=248||ty>=248)&&(image.getRGB(x,y)>>>24)!=0)throw new IOException("Nontransparent flipbook gutter: "+path);
                }
                for(int level=size;level>=1;level/=2)resident+=(long)level*level*4;
            }
            assets.add(asset(path,bytes,"HASH_LAYOUT_ALPHA_VERIFIED"));
        }
        if(!remaining.isEmpty()||resident>128L*1024*1024)throw new IOException("Incomplete or oversized VFX atlas set: "+remaining+", resident="+resident);
        assets.add(asset("vfx/provenance.json",manifest,"PROVENANCE_VERIFIED"));
        assets.add(asset(SOURCE,Files.readAllBytes(Path.of(SOURCE)),"SOURCE_PRESENT"));
        assets.add(asset(scene,Files.readAllBytes(Path.of(scene)),"EDITABLE_SOURCE_VERIFIED"));
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

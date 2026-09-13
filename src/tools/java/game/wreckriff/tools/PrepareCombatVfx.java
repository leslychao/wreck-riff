package game.wreckriff.tools;

import com.google.gson.*;
import game.wreckriff.presentation.CombatVfxDds;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Explicit, local CPU conversion. Neither normal Gradle builds nor the game call texconv. */
public final class PrepareCombatVfx {
    private static final String SOURCE="src/tools/java/game/wreckriff/tools/PrepareCombatVfx.java";
    private static final String ENCODER_SHA="dcfdec10244e02cf5037fba089c55fb7e1326b1c8181742d77d15fa5cb5eef06";
    private static final List<String> FAMILIES=List.of("smoke","flame","blast","dust");
    private static final List<String> FLAGS=List.of("-nologo","-f","BC3_UNORM_SRGB","-srgb","-dx9","-m","0","-nogpu","-if","BOX","-sepalpha","-y");
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private PrepareCombatVfx(){}
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("Repository and local may2026 texconv.exe paths required");
        Path root=Path.of(args[0]).toAbsolutePath().normalize(),encoder=Path.of(args[1]).toAbsolutePath();
        if(!Files.isRegularFile(encoder)||!hash(encoder).equals(ENCODER_SHA))throw new IOException("Missing or unverified DirectXTex may2026 texconv.exe. Install the pinned tool locally for explicit preparation; Gradle will never download it.");
        Path source=root.resolve("src/tools/assets/combat-vfx"),runtime=root.resolve("src/main/resources"),bakePath=source.resolve("bake-provenance.json");
        JsonObject bake=JsonParser.parseString(Files.readString(bakePath)).getAsJsonObject();
        requireHash(root.resolve(bake.get("generator").getAsString()),bake.get("generatorSha256").getAsString());
        requireHash(root.resolve(bake.get("sourceScene").getAsString()),bake.get("sourceSceneSha256").getAsString());
        var expected=new HashSet<String>();for(String family:FAMILIES)expected.add("src/tools/assets/combat-vfx/"+family+".png");
        expected.addAll(List.of("src/main/resources/textures/vfx/auxiliary.png","src/main/resources/vfx/recipes.json"));
        for(var item:bake.getAsJsonArray("assets")){var entry=item.getAsJsonObject();String path=entry.get("path").getAsString();if(!expected.remove(path))throw new IOException("Unexpected source bake "+path);requireHash(root.resolve(path),entry.get("sha256").getAsString());}
        if(!expected.isEmpty())throw new IOException("Incomplete source VFX bake "+expected);
        // A former runtime PNG can only be removed when the exact original bytes
        // have been preserved as editable source; never silently discard an edit.
        for(String family:FAMILIES){Path old=runtime.resolve("textures/vfx/"+family+".png");if(Files.exists(old)&&!hash(old).equals(hash(source.resolve(family+".png"))))throw new IOException("Former runtime PNG differs from preserved source: "+old);}
        Path work=root.resolve("build/combat-vfx-compression").resolve(UUID.randomUUID().toString());Files.createDirectories(work);
        var entries=new ArrayList<Map<String,Object>>();var quality=new LinkedHashMap<String,Object>();
        for(String family:FAMILIES) {
            Path original=source.resolve(family+".png"),first=encode(encoder,original,work.resolve("first")),second=encode(encoder,original,work.resolve("repeat"));
            if(!hash(first).equals(hash(second)))throw new IOException("CPU compression is not byte-reproducible for "+family);
            byte[] bytes=Files.readAllBytes(first);var dds=CombatVfxDds.read(bytes,2048);
            Map<String,Object> metrics=compare(original,dds);
            metrics.put("sourceSha256",hash(original));metrics.put("runtimeSha256",hash(bytes));metrics.put("repeatSha256",hash(second));
            metrics.put("source",root.relativize(original).toString().replace('\\','/'));metrics.put("runtime","textures/vfx/"+family+".dds");
            quality.put(family,metrics);entries.add(entry("textures/vfx/"+family+".dds",bytes));
            System.out.println(family+": "+JSON.toJson(metrics));
        }
        // No runtime output has changed before all four quality and repeat gates pass.
        var provenance=bake.deepCopy();provenance.addProperty("schemaVersion",2);
        provenance.addProperty("bakeManifest","src/tools/assets/combat-vfx/bake-provenance.json");provenance.addProperty("bakeManifestSha256",hash(bakePath));
        var compression=new LinkedHashMap<String,Object>();compression.put("converter",SOURCE);compression.put("converterSha256",hash(root.resolve(SOURCE)));
        compression.put("tool","Microsoft DirectXTex may2026");compression.put("toolSha256",ENCODER_SHA);
        compression.put("toolUrl","https://github.com/microsoft/DirectXTex/releases/download/may2026/texconv.exe");
        compression.put("license","licenses/DirectXTex-MIT.txt");compression.put("licenseSha256",hash(runtime.resolve("licenses/DirectXTex-MIT.txt")));
        compression.put("arguments",FLAGS);compression.put("format","legacy DDS DXT5 / BC3, straight alpha, sRGB RGB, linear alpha");
        compression.put("flipY",false);compression.put("offlineMipLevels",12);compression.put("payloadBytesPerAtlas",CombatVfxDds.mipBytes(2048));compression.put("quality",quality);
        provenance.add("compression",JSON.toJsonTree(compression));
        for(String path:List.of("textures/vfx/auxiliary.png","vfx/recipes.json"))entries.add(entry(path,Files.readAllBytes(runtime.resolve(path))));
        provenance.add("assets",JSON.toJsonTree(entries));
        for(String family:FAMILIES)publish(runtime.resolve("textures/vfx/"+family+".dds"),Files.readAllBytes(work.resolve("first/"+family+".dds")));
        for(String family:FAMILIES)Files.deleteIfExists(runtime.resolve("textures/vfx/"+family+".png"));
        publish(runtime.resolve("vfx/provenance.json"),(JSON.toJson(provenance)+"\n").getBytes(StandardCharsets.UTF_8));
        publish(work.resolve("complete.json"),(JSON.toJson(compression)+"\n").getBytes(StandardCharsets.UTF_8));
        System.out.println("Prepared four reproducible BC3 atlases; source PNG retained, no runtime PNG fallback. Evidence: "+work);
    }
    private static Path encode(Path encoder,Path source,Path output)throws Exception {
        Files.createDirectories(output);var command=new ArrayList<String>();command.add(encoder.toString());command.addAll(FLAGS);command.add("-o");command.add(output.toString());command.add(source.toString());
        Path log=output.resolve(source.getFileName()+".log");var process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if(!process.waitFor(30,TimeUnit.SECONDS)){process.destroyForcibly().waitFor();throw new IOException("CPU VFX compression exceeded 30 seconds; "+log);}
        if(process.exitValue()!=0)throw new IOException("CPU VFX compression failed; "+log);
        Path result=output.resolve(source.getFileName().toString().replace(".png",".dds"));if(!Files.isRegularFile(result))throw new IOException("Encoder did not produce "+result);return result;
    }
    private static Map<String,Object> compare(Path source,CombatVfxDds dds)throws Exception {
        var original=ImageIO.read(source.toFile());if(original==null||original.getWidth()!=2048||original.getHeight()!=2048||!original.getColorModel().hasAlpha())throw new IOException("Invalid source VFX RGBA bake "+source);
        long count=2048L*2048,visible=0,gutters=0;double alphaSq=0,rgbSq=0,compositeSq=0;int alphaMax=0,rgbMax=0;
        double[] linear=new double[256];for(int i=0;i<256;i++){double value=i/255.0;linear[i]=value<=.04045?value/12.92:Math.pow((value+.055)/1.055,2.4);}
        for(int y=0;y<2048;y++)for(int x=0;x<2048;x++) {
            int a=original.getRGB(x,y),b=dds.argb(x,y),aa=a>>>24,ba=b>>>24,delta=ba-aa;alphaSq+=(double)delta*delta;alphaMax=Math.max(alphaMax,Math.abs(delta));
            if(x%256<8||x%256>=248||y%256<8||y%256>=248){if(aa!=0||ba!=0)gutters++;}
            if(aa>=8)visible++;
            for(int shift=0;shift<=16;shift+=8){int av=a>>>shift&255,bv=b>>>shift&255,error=bv-av;if(aa>=8){rgbSq+=(double)error*error;rgbMax=Math.max(rgbMax,Math.abs(error));}double composite=linear[bv]*ba/255.0-linear[av]*aa/255.0+.18*(aa-ba)/255.0;compositeSq+=composite*composite;}
        }
        double alphaRmse=Math.sqrt(alphaSq/count),rgbRmse=Math.sqrt(rgbSq/Math.max(1,visible*3)),compositeRmse=Math.sqrt(compositeSq/(count*3));
        if(gutters!=0||visible<2000||alphaRmse>1.5||alphaMax>20||rgbRmse>8||compositeRmse>.009)
            throw new IOException("VFX compression quality gate failed: "+source+" alphaRMSE="+alphaRmse+" max="+alphaMax+" RGB="+rgbRmse+" composite="+compositeRmse+" gutter="+gutters);
        var result=new LinkedHashMap<String,Object>();result.put("pixels",count);result.put("visiblePixelsAlphaAtLeast8",visible);result.put("alphaRmse8bit",alphaRmse);result.put("alphaMax8bit",alphaMax);
        result.put("visibleRgbRmse8bit",rgbRmse);result.put("visibleRgbMax8bit",rgbMax);result.put("linearCompositeOver18PercentGreyRmse",compositeRmse);result.put("baseMipGutterNonzeroAlphaPixels",gutters);result.put("status","PASS");return result;
    }
    private static Map<String,Object> entry(String path,byte[] bytes)throws Exception{var value=new LinkedHashMap<String,Object>();value.put("path",path);value.put("sha256",hash(bytes));value.put("bytes",bytes.length);return value;}
    private static void requireHash(Path path,String sha)throws Exception{if(!Files.isRegularFile(path)||!hash(path).equals(sha))throw new IOException("Stale or missing VFX source: "+path+"; run explicit bake before compression");}
    private static String hash(Path path)throws Exception{return hash(Files.readAllBytes(path));}
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static void publish(Path path,byte[] bytes)throws Exception {
        Files.createDirectories(path.getParent());Path temporary=path.resolveSibling("."+path.getFileName()+"."+UUID.randomUUID()+".tmp");
        try {Files.write(temporary,bytes);for(int attempt=0;;attempt++){try{Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);break;}catch(FileSystemException busy){if(attempt==5)throw busy;Thread.sleep(100L*(attempt+1));}}}
        finally{Files.deleteIfExists(temporary);}
    }
}

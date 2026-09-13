package game.wreckriff.tools;

import com.google.gson.*;
import game.wreckriff.presentation.EnvironmentDiffuseDds;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Explicit local environment compression. Ordinary builds never invoke the encoder. */
public final class PrepareEnvironmentDiffuse {
    private static final String SOURCE="src/tools/java/game/wreckriff/tools/PrepareEnvironmentDiffuse.java";
    private static final String ENCODER_SHA="dcfdec10244e02cf5037fba089c55fb7e1326b1c8181742d77d15fa5cb5eef06";
    private static final List<String> FLAGS=List.of("-nologo","-f","BC7_UNORM_SRGB","-srgb","-m","0","-aw","100","-gpu","0","-if","BOX","-vflip","-y");
    private static final List<String> DECODE=List.of("-nologo","-ft","png","-f","R8G8B8A8_UNORM_SRGB","-srgb","-m","1","-vflip","-y");
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private record Prepared(Path encoded,Map<String,Object> record,JsonObject provenance){}
    public static void main(String[] args)throws Exception {
        if(args.length<2||args.length>3)throw new IllegalArgumentException("Repository and verified local texconv.exe paths required; optional --stage-only or --publish=<candidate-directory>");
        Path root=Path.of(args[0]).toAbsolutePath().normalize(),encoder=Path.of(args[1]).toAbsolutePath();
        if(!Files.isRegularFile(encoder)||!hash(encoder).equals(ENCODER_SHA))throw new IOException("Missing or unverified DirectXTex may2026 encoder; preparation does not download tools");
        if(args.length==3&&args[2].startsWith("--publish=")){publishCandidate(root,Path.of(args[2].substring(10)).toAbsolutePath().normalize());return;}
        boolean stageOnly=args.length==3&&args[2].equals("--stage-only");if(args.length==3&&!stageOnly)throw new IllegalArgumentException("Expected --stage-only or --publish=<candidate-directory>");
        String gpu=verifiedGpu();
        Path runtime=root.resolve("src/main/resources"),work=root.resolve("build/environment-diffuse-compression/"+UUID.randomUUID());Files.createDirectories(work);
        JsonObject provenance=JsonParser.parseString(Files.readString(runtime.resolve("licenses/asset-provenance.json"))).getAsJsonObject();
        JsonObject previous=Files.isRegularFile(runtime.resolve("textures/materials/diffuse-provenance.json"))?JsonParser.parseString(Files.readString(runtime.resolve("textures/materials/diffuse-provenance.json"))).getAsJsonObject():null;
        var jobs=new ArrayList<Callable<Prepared>>();
        for(String name:EnvironmentDiffuseDds.MATERIALS){
            String base="textures/materials/"+name+"/diffuse";
            JsonObject original=null;for(JsonElement candidate:provenance.getAsJsonArray("assets"))if(candidate.getAsJsonObject().get("path").getAsString().equals(base+".png")){original=candidate.getAsJsonObject().deepCopy();break;}
            if(original==null)for(JsonElement candidate:provenance.getAsJsonArray("assets"))if(candidate.getAsJsonObject().get("path").getAsString().equals(base+".dds")){original=candidate.getAsJsonObject().deepCopy();break;}
            if(original==null)throw new IOException("Missing environment source provenance: "+name);
            Path source=root.resolve("src/tools/assets/materials/"+name+"/runtime-diffuse.png"),png=runtime.resolve(base+".png");
            JsonObject prior=null;if(previous!=null)for(JsonElement entry:previous.getAsJsonArray("textures"))if(entry.getAsJsonObject().get("runtime").getAsString().equals(base+".dds")){prior=entry.getAsJsonObject();break;}
            String expected=original.get("path").getAsString().endsWith(".png")?original.get("sha256").getAsString():Objects.requireNonNull(prior,"Missing intermediate provenance").get("sourceSha256").getAsString();
            if(Files.isRegularFile(png)){if(!hash(png).equals(expected))throw new IOException("Environment PNG differs from provenance "+png);publish(source,Files.readAllBytes(png));}
            if(!Files.isRegularFile(source))throw new IOException("Missing prepared 8-bit source "+source);
            if(!hash(source).equals(expected)||!hash(root.resolve(original.get("sourcePath").getAsString())).equals(original.get("sourceSha256").getAsString()))throw new IOException("Environment source checksum mismatch "+name);
            JsonObject origin=original;jobs.add(()->prepare(root,runtime,encoder,work,name,source,origin));
        }
        var prepared=new ArrayList<Prepared>();var failures=new ArrayList<Exception>();
        for(var job:jobs)try{prepared.add(job.call());}catch(IOException failure){failures.add(failure);System.err.println(failure.getMessage());}
        if(!failures.isEmpty()){var failure=new IOException("Environment preparation rejected "+failures.size()+" maps; no runtime files published. Evidence: "+work);failures.forEach(failure::addSuppressed);throw failure;}
        var manifest=new LinkedHashMap<String,Object>();manifest.put("schemaVersion",1);manifest.put("format","BC7_UNORM_SRGB");manifest.put("converter",SOURCE);manifest.put("converterSha256",hash(root.resolve(SOURCE)));
        manifest.put("tool","Microsoft DirectXTex may2026");manifest.put("toolSha256",ENCODER_SHA);manifest.put("toolUrl","https://github.com/microsoft/DirectXTex/releases/download/may2026/texconv.exe");
        manifest.put("license","licenses/DirectXTex-MIT.txt");manifest.put("licenseSha256",hash(runtime.resolve("licenses/DirectXTex-MIT.txt")));manifest.put("arguments",FLAGS);manifest.put("decodeArguments",DECODE);
        manifest.put("gpuDevice",gpu);manifest.put("gpuDriver","610.62");manifest.put("reproducibility","Two identical encodes per map on the recorded GPU/driver only; cross-GPU determinism is not claimed");
        manifest.put("qualityContract","Photographic environment: RGB RMSE<=3/255, p99 channel error<=8/255, maximum<=48/255, alpha254..255; native day/neon comparison before first publication. Separate from vehicle thresholds.");
        manifest.put("offlineFlipY",true);manifest.put("loaderFlipY",false);manifest.put("imageColorSpace","Linear; BC7_UNORM_SRGB selects GL sRGB decoding directly in jME 3.8.1");manifest.put("textures",prepared.stream().map(Prepared::record).toList());
        publish(work.resolve("candidate.json"),(JSON.toJson(Map.of("manifest",manifest,"provenance",prepared.stream().map(Prepared::provenance).toList()))+"\n").getBytes(StandardCharsets.UTF_8));
        if(!stageOnly)publishCandidate(root,work);else System.out.println("Thirteen environment candidates ready for native review; lossless leafy grass retained; explicit --publish required: "+work);
    }
    private static Prepared prepare(Path root,Path runtime,Path encoder,Path work,String name,Path source,JsonObject original)throws Exception {
        Path encoderInput=source;
        Path first=encode(encoder,encoderInput,work.resolve(name+"/first"),FLAGS,".dds");
        var dds=EnvironmentDiffuseDds.read(Files.readAllBytes(first));Path decoded=encode(encoder,first,work.resolve(name+"/decoded"),DECODE,".png");
        var entry=compare(source,decoded);String target="textures/materials/"+name+"/diffuse.dds";
        Path repeat=encode(encoder,encoderInput,work.resolve(name+"/repeat"),FLAGS,".dds");String sha=hash(first);if(!sha.equals(hash(repeat)))throw new IOException("BC7 encoding is not reproducible on the recorded GPU: "+name);
        entry.put("encoderInputSha256",hash(encoderInput));entry.put("opaqueInput","Original RGB8/RGBA8 intermediate; every source alpha verified as 255; alpha fitting weight 100");
        entry.put("source",root.relativize(source).toString().replace('\\','/'));entry.put("sourceSha256",hash(source));entry.put("runtime",target);entry.put("runtimeSha256",sha);entry.put("repeatSha256",hash(repeat));entry.put("size",dds.size());entry.put("mipLevels",dds.levels());entry.put("payloadBytes",dds.payloadBytes());
        entry.put("originalSource",original.get("sourcePath").getAsString());entry.put("originalSourceSha256",original.get("sourceSha256").getAsString());entry.put("sourceTransformation",original.get("transformation").getAsString().split("; BC7_UNORM_SRGB",2)[0]);
        original.addProperty("path",target);original.addProperty("sha256",sha);
        original.addProperty("transformation",entry.get("sourceTransformation")+"; BC7_UNORM_SRGB full BOX mips, offline vertical flip; PrepareEnvironmentDiffuse.java; same-device GPU repeat and decoded quality verified");
        publish(work.resolve(name+"/quality.json"),(JSON.toJson(entry)+"\n").getBytes(StandardCharsets.UTF_8));System.out.println("Prepared "+name+": "+JSON.toJson(entry));return new Prepared(first,entry,original);
    }
    private static Path encode(Path encoder,Path source,Path output,List<String> flags,String extension)throws Exception {
        Files.createDirectories(output);var command=new ArrayList<String>();command.add(encoder.toString());command.addAll(flags);command.add("-o");command.add(output.toString());command.add(source.toString());
        Path log=output.resolve(source.getFileName()+".log");var builder=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());builder.environment().put("OMP_NUM_THREADS","2");var process=builder.start();
        try{if(!process.waitFor(60,TimeUnit.SECONDS)){process.destroyForcibly().waitFor();throw new IOException("Environment conversion exceeded 60 seconds: "+log);}}
        catch(InterruptedException interrupted){process.destroyForcibly();throw interrupted;}
        if(process.exitValue()!=0)throw new IOException("Environment conversion failed: "+log);
        if(extension.equals(".dds")&&!Files.readString(log).contains("[Using DirectCompute 5.0 on \"NVIDIA GeForce RTX 5080\"]"))throw new IOException("Expected DirectCompute GPU encoder; CPU fallback is forbidden: "+log);
        Path result=output.resolve(source.getFileName().toString().replaceFirst("\\.[^.]+$",extension));if(!Files.isRegularFile(result))throw new IOException("Missing encoded output "+result);return result;
    }
    private static Map<String,Object> compare(Path source,Path decoded)throws Exception {
        var original=ImageIO.read(source.toFile());var after=ImageIO.read(decoded.toFile());
        if(original==null||after==null||original.getWidth()!=2048||original.getHeight()!=2048||after.getWidth()!=2048||after.getHeight()!=2048)throw new IOException("BC7 decoded dimensions differ "+source);
        long count=2048L*2048*3,squares=0;int maximum=0,alphaMin=255,alphaMax=0;long[] errors=new long[256];
        for(int y=0;y<2048;y++)for(int x=0;x<2048;x++){int before=original.getRGB(x,y),output=after.getRGB(x,y);if((before>>>24)!=255)throw new IOException("Meaningful alpha in opaque environment map "+source);alphaMin=Math.min(alphaMin,output>>>24);alphaMax=Math.max(alphaMax,output>>>24);for(int shift=0;shift<24;shift+=8){int delta=(output>>>shift&255)-(before>>>shift&255);squares+=(long)delta*delta;maximum=Math.max(maximum,Math.abs(delta));errors[Math.abs(delta)]++;}}
        double rmse=Math.sqrt((double)squares/count);var result=new LinkedHashMap<String,Object>();result.put("rgbRmse8bit",rmse);result.put("rgbPsnrDb",20*Math.log10(255/Math.max(rmse,1e-12)));result.put("rgbMax8bit",maximum);result.put("decodedAlphaMin",alphaMin);result.put("decodedAlphaMax",alphaMax);
        long accumulated=0;int p99=0;for(;p99<255;p99++){accumulated+=errors[p99];if(accumulated>=Math.ceil(count*.99))break;}result.put("rgbP99Absolute8bit",p99);
        publish(decoded.resolveSibling("metrics.json"),(JSON.toJson(result)+"\n").getBytes(StandardCharsets.UTF_8));
        if(rmse>3||p99>8||maximum>48||alphaMin<254)throw new IOException("Environment photographic BC7 quality failed: "+source+" RMSE="+rmse+" p99="+p99+" max="+maximum+" alpha="+alphaMin);
        result.put("status","PASS");return result;
    }
    private static String hash(Path path)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
    private static String verifiedGpu()throws Exception {
        var process=new ProcessBuilder("nvidia-smi.exe","--query-gpu=index,name,driver_version","--format=csv,noheader").redirectErrorStream(true).start();
        if(!process.waitFor(10,TimeUnit.SECONDS)){process.destroyForcibly();throw new IOException("GPU identity query timed out");}
        String result=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);if(process.exitValue()!=0||!result.lines().anyMatch(line->line.trim().equals("0, NVIDIA GeForce RTX 5080, 610.62")))throw new IOException("Expected pinned GPU0 RTX5080 driver610.62; no silent device substitution: "+result);return "NVIDIA GeForce RTX 5080";
    }
    private static void publishCandidate(Path root,Path work)throws Exception {
        if(!work.startsWith(root.resolve("build/environment-diffuse-compression")))throw new IOException("Candidate must be inside the preparation workspace");
        var candidate=JsonParser.parseString(Files.readString(work.resolve("candidate.json"))).getAsJsonObject();var manifest=candidate.getAsJsonObject("manifest");Path runtime=root.resolve("src/main/resources");
        if(!hash(root.resolve(SOURCE)).equals(manifest.get("converterSha256").getAsString())||!manifest.get("toolSha256").getAsString().equals(ENCODER_SHA))throw new IOException("Candidate converter/tool differs");
        var pending=new LinkedHashMap<Path,Path>();var required=new HashSet<>(EnvironmentDiffuseDds.MATERIALS);
        for(var value:manifest.getAsJsonArray("textures")){var entry=value.getAsJsonObject();String target=entry.get("runtime").getAsString();String name=target.split("/")[2];if(!required.remove(name)||!target.equals("textures/materials/"+name+"/diffuse.dds"))throw new IOException("Unexpected candidate map "+target);
            Path source=root.resolve("src/tools/assets/materials/"+name+"/runtime-diffuse.png"),first=work.resolve(name+"/first/runtime-diffuse.dds"),repeat=work.resolve(name+"/repeat/runtime-diffuse.dds");
            if(!hash(root.resolve(entry.get("originalSource").getAsString())).equals(entry.get("originalSourceSha256").getAsString()))throw new IOException("Original source changed "+name);
            if(!hash(source).equals(entry.get("sourceSha256").getAsString())||!hash(first).equals(entry.get("runtimeSha256").getAsString())||!hash(repeat).equals(entry.get("repeatSha256").getAsString())||!hash(first).equals(hash(repeat)))throw new IOException("Candidate source or repeat changed "+name);
            EnvironmentDiffuseDds.read(Files.readAllBytes(first));compare(source,work.resolve(name+"/decoded/runtime-diffuse.png"));pending.put(runtime.resolve(target),first);
        }if(!required.isEmpty())throw new IOException("Missing candidate maps "+required);
        var provenance=JsonParser.parseString(Files.readString(runtime.resolve("licenses/asset-provenance.json"))).getAsJsonObject();JsonArray all=new JsonArray();for(var entry:provenance.getAsJsonArray("assets")){String path=entry.getAsJsonObject().get("path").getAsString();if(EnvironmentDiffuseDds.MATERIALS.stream().noneMatch(n->path.equals("textures/materials/"+n+"/diffuse.png")||path.equals("textures/materials/"+n+"/diffuse.dds")))all.add(entry);}
        for(var entry:candidate.getAsJsonArray("provenance"))all.add(entry);provenance.add("assets",all);
        for(var entry:pending.entrySet())publish(entry.getKey(),Files.readAllBytes(entry.getValue()));publish(runtime.resolve("licenses/asset-provenance.json"),(JSON.toJson(provenance)+"\n").getBytes(StandardCharsets.UTF_8));
        publish(runtime.resolve("textures/materials/diffuse-provenance.json"),(JSON.toJson(manifest)+"\n").getBytes(StandardCharsets.UTF_8));publish(work.resolve("complete.json"),(JSON.toJson(manifest)+"\n").getBytes(StandardCharsets.UTF_8));System.out.println("PUBLISHED environment DDS: "+work);
    }
    private static void publish(Path target,byte[] bytes)throws Exception {
        Files.createDirectories(target.getParent());Path temporary=target.resolveSibling("."+target.getFileName()+"."+UUID.randomUUID()+".tmp");try{Files.write(temporary,bytes);for(int attempt=0;;attempt++)try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);break;}catch(FileSystemException e){if(attempt==6)throw e;Thread.sleep(100L*(attempt+1));}}finally{Files.deleteIfExists(temporary);}
    }
}

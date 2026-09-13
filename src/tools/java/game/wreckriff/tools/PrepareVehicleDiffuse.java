package game.wreckriff.tools;

import com.google.gson.*;
import game.wreckriff.presentation.VehicleDiffuseDds;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Explicit offline opaque diffuse compression. Ordinary builds read the prepared DDS only. */
public final class PrepareVehicleDiffuse {
    private static final String SOURCE="src/tools/java/game/wreckriff/tools/PrepareVehicleDiffuse.java";
    private static final String ENCODER_SHA="dcfdec10244e02cf5037fba089c55fb7e1326b1c8181742d77d15fa5cb5eef06";
    private static final List<String> FLAGS=List.of("-nologo","-f","BC7_UNORM_SRGB","-srgb","-m","0","-nogpu","-if","BOX","-vflip","-y");
    private static final List<String> DECODE=List.of("-nologo","-ft","png","-f","R8G8B8A8_UNORM_SRGB","-srgb","-m","1","-vflip","-y");
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private PrepareVehicleDiffuse(){}
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("Repository and verified local texconv.exe paths required");
        Path root=Path.of(args[0]).toAbsolutePath().normalize(),encoder=Path.of(args[1]).toAbsolutePath();
        if(!Files.isRegularFile(encoder)||!hash(encoder).equals(ENCODER_SHA))throw new IOException("Missing or unverified DirectXTex may2026 encoder; explicit preparation never downloads tools");
        Path runtime=root.resolve("src/main/resources"),work=root.resolve("build/vehicle-diffuse-compression/"+UUID.randomUUID());Files.createDirectories(work);
        var records=new ArrayList<Map<String,Object>>();var prepared=new LinkedHashMap<Path,Path>();
        for(String profile:List.of("rivet","grinder","spark","boss_foreman","boss_prefect","boss_emcee","shared")) {
            String name=(profile.equals("shared")?"metal-":"")+"diffuse";Path source=root.resolve("src/tools/assets/vehicles/"+profile+"/"+name+".png");
            var bake=JsonParser.parseString(Files.readString(source.resolveSibling("source.json"))).getAsJsonObject();
            if(!hash(source).equals(bake.getAsJsonObject("exports").get(name+".png").getAsString()))throw new IOException("Diffuse source differs from the authored export: "+source);
            if(!hash(root.resolve("src/tools/author_vehicle_models.py")).equals(bake.get("generatorSha256").getAsString()))throw new IOException("Stale vehicle source bake: "+profile);
            Path first=encode(encoder,source,work.resolve(profile+"/first"),FLAGS,".dds"),repeat=encode(encoder,source,work.resolve(profile+"/repeat"),FLAGS,".dds");
            String sha=hash(first);if(!sha.equals(hash(repeat)))throw new IOException("Vehicle BC7 CPU encoding is not reproducible: "+profile);
            var dds=VehicleDiffuseDds.read(Files.readAllBytes(first),profile.equals("shared")?1024:2048);
            Path decoded=encode(encoder,first,work.resolve(profile+"/decoded"),DECODE,".png");
            var entry=compare(source,decoded);String target="textures/vehicles/"+profile+"/"+name+".dds";
            entry.put("source",root.relativize(source).toString().replace('\\','/'));entry.put("sourceSha256",hash(source));entry.put("runtime",target);entry.put("runtimeSha256",sha);entry.put("repeatSha256",hash(repeat));entry.put("size",dds.size());entry.put("mipLevels",dds.levels());entry.put("payloadBytes",dds.payloadBytes());
            records.add(entry);prepared.put(runtime.resolve(target),first);System.out.println("Prepared BC7 "+profile+": "+JSON.toJson(entry));
        }
        var manifest=new LinkedHashMap<String,Object>();manifest.put("schemaVersion",1);manifest.put("format","BC7_UNORM_SRGB");manifest.put("converter",SOURCE);manifest.put("converterSha256",hash(root.resolve(SOURCE)));
        manifest.put("tool","Microsoft DirectXTex may2026");manifest.put("toolSha256",ENCODER_SHA);manifest.put("toolUrl","https://github.com/microsoft/DirectXTex/releases/download/may2026/texconv.exe");
        manifest.put("license","licenses/DirectXTex-MIT.txt");manifest.put("licenseSha256",hash(runtime.resolve("licenses/DirectXTex-MIT.txt")));manifest.put("arguments",FLAGS);manifest.put("decodeArguments",DECODE);
        manifest.put("offlineFlipY",true);manifest.put("loaderFlipY",false);manifest.put("imageColorSpace","Linear; DX10 BC7_UNORM_SRGB selects GL sRGB internal format directly in jME 3.8.1");manifest.put("textures",records);
        // Publish only after every profile passed quality and reproducibility, preserving each previous complete file until replacement.
        for(var entry:prepared.entrySet())publish(entry.getKey(),Files.readAllBytes(entry.getValue()));
        publish(runtime.resolve("textures/vehicles/diffuse-provenance.json"),(JSON.toJson(manifest)+"\n").getBytes(StandardCharsets.UTF_8));
        publish(work.resolve("complete.json"),(JSON.toJson(manifest)+"\n").getBytes(StandardCharsets.UTF_8));
        System.out.println("Seven diffuse DDS prepared; run prepareVehicleModels to replace bank references and retire PNG. Evidence: "+work);
    }
    private static Path encode(Path encoder,Path source,Path output,List<String> flags,String extension)throws Exception {
        Files.createDirectories(output);var command=new ArrayList<String>();command.add(encoder.toString());command.addAll(flags);command.add("-o");command.add(output.toString());command.add(source.toString());
        Path log=output.resolve(source.getFileName()+".log");var process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if(!process.waitFor(180,TimeUnit.SECONDS)){process.destroyForcibly().waitFor();throw new IOException("Vehicle diffuse conversion exceeded 180 seconds: "+log);}
        if(process.exitValue()!=0)throw new IOException("Vehicle diffuse conversion failed: "+log);
        Path result=output.resolve(source.getFileName().toString().replaceFirst("\\.[^.]+$",extension));if(!Files.isRegularFile(result))throw new IOException("Missing encoded output "+result);return result;
    }
    private static Map<String,Object> compare(Path source,Path decoded)throws Exception {
        var original=ImageIO.read(source.toFile());var after=ImageIO.read(decoded.toFile());
        if(original==null||after==null||original.getWidth()!=after.getWidth()||original.getHeight()!=after.getHeight())throw new IOException("BC7 decoded dimensions differ "+source);
        long count=(long)original.getWidth()*original.getHeight()*3,squares=0;int maximum=0,alphaMin=255,alphaMax=0;
        for(int y=0;y<original.getHeight();y++)for(int x=0;x<original.getWidth();x++) {
            int before=original.getRGB(x,y),output=after.getRGB(x,y);if((before>>>24)!=255)throw new IOException("Vehicle diffuse source has meaningful alpha "+source);
            alphaMin=Math.min(alphaMin,output>>>24);alphaMax=Math.max(alphaMax,output>>>24);
            for(int shift=0;shift<24;shift+=8){int delta=(output>>>shift&255)-(before>>>shift&255);squares+=(long)delta*delta;maximum=Math.max(maximum,Math.abs(delta));}
        }
        double rmse=Math.sqrt((double)squares/count);if(rmse>1.2||maximum>16||alphaMin<254)throw new IOException("Vehicle BC7 quality failed: "+source+" RMSE="+rmse+" max="+maximum+" alpha="+alphaMin);
        var result=new LinkedHashMap<String,Object>();result.put("rgbRmse8bit",rmse);result.put("rgbPsnrDb",20*Math.log10(255/Math.max(rmse,1e-12)));result.put("rgbMax8bit",maximum);result.put("decodedAlphaMin",alphaMin);result.put("decodedAlphaMax",alphaMax);result.put("status","PASS");return result;
    }
    private static String hash(Path path)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
    private static void publish(Path target,byte[] bytes)throws Exception {
        Files.createDirectories(target.getParent());Path temporary=target.resolveSibling("."+target.getFileName()+"."+UUID.randomUUID()+".tmp");
        try{Files.write(temporary,bytes);for(int attempt=0;;attempt++)try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);break;}catch(FileSystemException error){if(attempt==6)throw error;Thread.sleep(100L*(attempt+1));}}
        finally{Files.deleteIfExists(temporary);}
    }
}

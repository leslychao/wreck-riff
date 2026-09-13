package game.wreckriff.diagnostics;

import com.google.gson.*;
import game.wreckriff.audio.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Offline evidence for the three small recorded machinery loops. */
public final class AmbientAssetsVerifier {
    private AmbientAssetsVerifier() {}
    public static Map<String,String> verify(AudioConfig config,List<VerifyAssets.Asset> assets)throws Exception {
        byte[] sourcesBytes=resource("audio/ambient-sources.json"),provenanceBytes=resource("audio/ambient-provenance.json");
        JsonObject sources=json(sourcesBytes),provenance=json(provenanceBytes);
        require(sources.get("schemaVersion").getAsInt()==1&&provenance.get("schemaVersion").getAsInt()==1,"schema");
        require("LICENSED_RECORDINGS".equals(sources.get("origin").getAsString())
                &&"LICENSED_RECORDINGS".equals(provenance.get("origin").getAsString()),"recording origin");
        require("NEEDS_CREATIVE_REVIEW".equals(provenance.get("artisticStatus").getAsString()),"owner review boundary");
        require(hash(sourcesBytes).equals(provenance.get("sourceEvidenceSha256").getAsString()),"source evidence hash");
        fileHash(sources,"preparationScriptPath","preparationScriptSha256");
        fileHash(provenance,"generatorPath","generatorSha256");
        require(hash(resource("licenses/assets/CC0-1.0.txt")).equals("a2010f343487d3f7618affe54f789f5487602331c0a8d03f49e9a7c547cf0499"),"CC0 license text");
        Map<String,JsonObject> byId=new HashMap<>();
        for(JsonElement element:sources.getAsJsonArray("sources")) {
            JsonObject entry=element.getAsJsonObject();String id=entry.get("id").getAsString();
            require(byId.put(id,entry)==null&&entry.get("license").getAsString().equals("CC0-1.0"),"source identity/license");
            fileHash(entry,"sourcePath","sourceSha256");fileHash(entry,"decodedPath","decodedSha256");fileHash(entry,"evidencePath","evidenceSha256");
            String page=Files.readString(Path.of(entry.get("evidencePath").getAsString()));
            require(page.contains("creativecommons.org/publicdomain/zero/1.0"),"primary license evidence");
            if(entry.get("downloadUrl").getAsString().contains("/previews/"))
                require(entry.get("artifactForm").getAsString().contains("preview")&&entry.get("artifactForm").getAsString().contains("not downloaded"),"preview disclosure");
        }
        require(byId.keySet().equals(Set.of("qubodup-fan","rvgerxini-motor","jerimee-machinery")),"three expected recordings");
        Map<String,String> result=new LinkedHashMap<>();Set<String> sourceUses=new HashSet<>();
        for(JsonElement element:provenance.getAsJsonArray("assets")) {
            JsonObject entry=element.getAsJsonObject();String path=entry.get("path").getAsString(),id=entry.get("sourceId").getAsString();
            require(path.matches("audio/ambient-(motor|rotor|ventilation)\\.wav")&&byId.containsKey(id)&&sourceUses.add(id),"asset identity");
            String cue=path.substring(6,path.length()-4);require(config.effects().contains(cue),"cue preloading");
            require(entry.get("loop").getAsBoolean()&&entry.get("license").getAsString().equals("CC0-1.0"),"loop license");
            byte[] wave=resource(path);require(hash(wave).equals(entry.get("sha256").getAsString()),"loop hash");
            require(Arrays.equals(wave,Files.readAllBytes(Path.of("src/tools/assets/audio/ambient/processed").resolve(path))),"prepared/build loop equivalence");
            try(InputStream input=new ByteArrayInputStream(wave)) {
                PcmWave.Header header=PcmWave.header(input);require(header.channels()==1&&header.seconds()>2&&header.seconds()<12,"mono loop duration");
                require(header.dataBytes()/2==entry.get("frames").getAsLong(),"frame count");
                byte[] pcm=input.readNBytes((int)header.dataBytes());long sum=0;double square=0,peak=0;
                for(int i=0;i<pcm.length;i+=2){int sample=sample(pcm,i);sum+=sample;square+=(double)sample*sample;peak=Math.max(peak,Math.abs(sample));}
                require(Math.abs(sample(pcm,0)-sample(pcm,pcm.length-2))<=2,"continuous PCM seam");
                require(peak/32768>.44&&peak/32768<.46&&Math.sqrt(square/(pcm.length/2))/32768>.025,"audible unclipped recording");
                require(Math.abs(sum/(double)(pcm.length/2))/32768<.002,"DC offset");
            }
            JsonObject source=byId.get(id);
            require(result.put(path,source.get("author").getAsString()+"; "+source.get("sourceUrl").getAsString()+"; "
                    +source.get("artifactForm").getAsString()+"; "+entry.get("transformation").getAsString())==null,"duplicate cue");
        }
        require(result.size()==ArenaAmbience.CUES.size(),"complete ambient set");
        assets.add(new VerifyAssets.Asset("audio/ambient-sources.json","sfx-provenance",sourcesBytes.length,hash(sourcesBytes),
                "CC0 primary recording pages; original WAV and explicitly identified HQ previews","VERIFIED",null,null,null,null));
        assets.add(new VerifyAssets.Asset("audio/ambient-provenance.json","sfx-provenance",provenanceBytes.length,hash(provenanceBytes),
                "GenerateAudio; offline recording loop edits","VERIFIED",null,null,null,null));
        return result;
    }
    private static int sample(byte[] pcm,int i){return (short)((pcm[i]&255)|(pcm[i+1]<<8));}
    private static JsonObject json(byte[] bytes){return JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();}
    private static byte[] resource(String path)throws IOException {
        try(InputStream input=AmbientAssetsVerifier.class.getResourceAsStream("/"+path)) {
            if(input==null)throw new IOException("Missing ambient resource: "+path);
            byte[] bytes=input.readNBytes(4_000_001);require(bytes.length<=4_000_000,"resource size");return bytes;
        }
    }
    private static void fileHash(JsonObject entry,String path,String sha)throws Exception {
        require(hash(Files.readAllBytes(Path.of(entry.get(path).getAsString()))).equals(entry.get(sha).getAsString()),path);
    }
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static void require(boolean valid,String message)throws IOException {if(!valid)throw new IOException("Ambient assets: "+message);}
}

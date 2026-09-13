package game.wreckriff.tools;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import com.google.gson.*;

/** Local licensed recordings and original deterministic ancillary sound-effect synthesis. No build-time network. */
public final class GenerateAudio {
    public static final int RATE = 48_000;
    public static final long SEED = 0x5249464657415645L;
    private static final double TAU = 2 * Math.PI;
    private static final List<String> METRICS = new ArrayList<>();
    private GenerateAudio() {}

    public static void main(String[] args) throws Exception {
        if(args.length==3&&args[0].equals("--ambient-only")) {
            Path output=Path.of(args[1]).resolve("audio");Files.createDirectories(output);
            METRICS.clear();METRICS.add("asset,frames,channels,sample_rate,bits,peak,rms,sha256");
            prepareAmbient(output,Path.of(args[2]).resolve("ambient"));
            Files.write(output.resolve("ambient-metrics.csv"),METRICS,StandardCharsets.UTF_8);
            System.out.println("Prepared three recorded machinery loops offline in "+output);return;
        }
        if(args.length!=2)throw new IllegalArgumentException("GenerateAudio <output-resources-root> <audio-source-directory>");
        Path output=Path.of(args[0]).resolve("audio"),sources=Path.of(args[1]);
        Files.createDirectories(output);
        // Remove superseded outputs when upgrading an existing build directory.
        // Generated directory belongs to this task; replacing its WAV set removes
        // superseded cues/variants without a parallel legacy route.
        try (var prior = Files.list(output)) {
            for (Path file : prior.filter(p -> p.getFileName().toString().endsWith(".wav")).toList()) Files.delete(file);
        }
        METRICS.clear();METRICS.add("asset,frames,channels,sample_rate,bits,peak,rms,sha256");
        prepareMusic(output,sources);
        prepareRecordedEffects(output,sources.resolve("recorded"));
        prepareAmbient(output,sources.resolve("ambient"));
        prepareResults(output,sources);
        preparePickups(output);
        prepareSpecials(output);
        prepareArenaHazards(output);
        prepareBossTelegraph(output);
        prepareMenuEffects(output);
        for(String id:List.of("engine-idle","engine-drive","turbo-loop","tyre-slip","empty",
                "low-hp","hazard-warning","hazard-active","pickup-repair","pickup-turbo",
                "mine-place"))effect(output,id);
        Files.write(output.resolve("audio-metrics.csv"),METRICS,StandardCharsets.UTF_8);
        Files.copy(sources.resolve("music/score.txt"),output.resolve("score.txt"),StandardCopyOption.REPLACE_EXISTING);
        System.out.println("Prepared eight licensed metal recordings, recorded combat variants and original menu effects in "+output);
    }

    /** Explicit authoring lives in import_menu_music.py; builds only verify/copy local prepared recordings. */
    private static void prepareMusic(Path output,Path sources)throws Exception {
        Path source=sources.resolve("music"),target=output.resolve("music");
        Files.createDirectories(target);
        // Generated output belongs to this task. Retired music cannot remain on incremental builds.
        Path retired=output.resolve("campaign");
        if(Files.exists(retired))try(var paths=Files.walk(retired)) {
            for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);
        }
        for(String name:List.of("music-source.json","music-provenance.json"))Files.deleteIfExists(output.resolve(name));
        Set<String> required=new HashSet<>(List.of("menu.wav","dead-air-yard.wav","construction_17-normal.wav",
                "construction_17-boss.wav","neon_zero-normal.wav","neon_zero-boss.wav","euphoria_park-normal.wav","euphoria_park-boss.wav"));
        try(var prior=Files.list(target)) {
            for(Path file:prior.filter(p->p.getFileName().toString().endsWith(".wav")).toList())Files.delete(file);
        }
        List<String> rows=Files.readAllLines(source.resolve("audio-metrics.csv"),StandardCharsets.UTF_8);
        if(rows.size()!=9||!rows.getFirst().equals(METRICS.getFirst()))throw new IOException("Exactly eight soundtrack recordings are required");
        for(String row:rows.subList(1,rows.size())) {
            String[] columns=row.split(",",-1);
            if(columns.length!=8||!required.remove(columns[0])||!columns[2].equals("2")||!columns[3].equals("48000")||!columns[4].equals("16"))
                throw new IOException("Invalid soundtrack metric row: "+row);
            Path prepared=source.resolve(columns[0]);
            if(!hash(Files.readAllBytes(prepared)).equals(columns[7]))throw new IOException("Soundtrack checksum mismatch: "+prepared);
            try(var input=javax.sound.sampled.AudioSystem.getAudioInputStream(prepared.toFile())) {
                var format=input.getFormat();
                if(format.getChannels()!=2||format.getSampleRate()!=RATE||format.getSampleSizeInBits()!=16||format.isBigEndian()
                        ||!format.getEncoding().equals(javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED)
                        ||input.getFrameLength()!=Long.parseLong(columns[1]))throw new IOException("Invalid soundtrack PCM: "+prepared);
            }
            Files.copy(prepared,target.resolve(columns[0]),StandardCopyOption.REPLACE_EXISTING);
        }
        for(String name:List.of("audio-metrics.csv","provenance.json","sources.json","score.txt"))
            Files.copy(source.resolve(name),target.resolve(name),StandardCopyOption.REPLACE_EXISTING);
    }

    private static void prepareRecordedEffects(Path output,Path source)throws Exception {
        List<String> manifest=Files.readAllLines(source.resolve("audio-metrics.csv"),StandardCharsets.UTF_8);
        if(manifest.isEmpty() || !manifest.getFirst().equals(METRICS.getFirst()))
            throw new IOException("Invalid recorded effect metrics header");
        Set<String> names=new HashSet<>();
        for(String row:manifest.subList(1,manifest.size())) {
            String[] columns=row.split(",",-1);
            if(columns.length!=8 || !columns[0].matches("[a-z][a-z0-9-]*\\.wav") || !names.add(columns[0]))
                throw new IOException("Invalid/duplicate recorded effect row");
            Path prepared=source.resolve("processed").resolve(columns[0]);
            if(!hash(Files.readAllBytes(prepared)).equals(columns[7]))throw new IOException("Recorded effect hash mismatch: "+prepared);
            Files.copy(prepared,output.resolve(columns[0]),StandardCopyOption.REPLACE_EXISTING);
            METRICS.add(row);
        }
        Files.copy(source.resolve("sfx-provenance.json"),output.resolve("sfx-provenance.json"),StandardCopyOption.REPLACE_EXISTING);
        Files.copy(source.resolve("sources.json"),output.resolve("sfx-sources.json"),StandardCopyOption.REPLACE_EXISTING);
    }

    private static double sample(byte[] data,int frame,int channel) {
        int offset=frame*4+channel*2;
        return (short)((data[offset]&255)|(data[offset+1]<<8))/32768.0;
    }

    /** Real local machinery recordings; this is a loop edit, not synthesized replacement audio. */
    private static void prepareAmbient(Path output,Path source)throws Exception {
        byte[] evidence=Files.readAllBytes(source.resolve("sources.json"));
        JsonObject manifest=JsonParser.parseString(new String(evidence,StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray entries=new JsonArray();
        Set<String> required=new HashSet<>(List.of("qubodup-fan","rvgerxini-motor","jerimee-machinery"));
        for(JsonElement element:manifest.getAsJsonArray("sources")) {
            JsonObject item=element.getAsJsonObject();String id=item.get("id").getAsString();
            if(!required.remove(id)||!item.get("license").getAsString().equals("CC0-1.0"))throw new IOException("Invalid ambient source");
            Path decoded=source.resolve("decoded").resolve(id+".wav");
            if(!hash(Files.readAllBytes(decoded)).equals(item.get("decodedSha256").getAsString()))throw new IOException("Ambient input changed: "+id);
            byte[] raw;
            try(var stream=javax.sound.sampled.AudioSystem.getAudioInputStream(decoded.toFile())) {
                var format=stream.getFormat();
                if(format.getChannels()!=1||format.getSampleRate()!=RATE||format.getSampleSizeInBits()!=16||format.isBigEndian()
                        ||stream.getFrameLength()>RATE*20)throw new IOException("Invalid ambient PCM");
                raw=stream.readAllBytes();
            }
            boolean rotor=id.equals("qubodup-fan"),motor=id.equals("rvgerxini-motor");
            String cue=rotor?"ambient-rotor":motor?"ambient-motor":"ambient-ventilation";
            int trim=(int)(RATE*(rotor?.15:.5)),length=raw.length/2-trim*2,overlap=(int)(RATE*(rotor?.25:.6));
            if(length<overlap*3)throw new IOException("Ambient recording is too short: "+id);
            float[] edited=new float[length];double low=0,dc=0;
            double highAlpha=1-Math.exp(-TAU*45/RATE),lowAlpha=1-Math.exp(-TAU*(rotor?2600:motor?3400:3000)/RATE);
            for(int i=0;i<length;i++) {
                int j=(i+trim)*2;double value=(short)((raw[j]&255)|(raw[j+1]<<8))/32768.0;
                dc+=highAlpha*(value-dc);low+=lowAlpha*(value-dc-low);edited[i]=(float)low;
            }
            float[] loop=new float[length-overlap];int middle=length-2*overlap;
            System.arraycopy(edited,overlap,loop,0,middle);
            for(int i=0;i<overlap;i++) {
                float blend=i/(float)(overlap-1);
                loop[middle+i]=edited[length-overlap+i]*(1-blend)+edited[i]*blend;
            }
            // Tiny end correction keeps the repeated PCM boundary click-free,
            // without silent attack/release gaps in the continuous mechanism.
            int edge=RATE/200;float delta=loop[0]-loop[loop.length-1];
            for(int i=0;i<edge;i++)loop[loop.length-edge+i]+=delta*i/(edge-1);
            double mean=0,peak=0;for(float value:loop)mean+=value;mean/=loop.length;
            for(int i=0;i<loop.length;i++){loop[i]-=(float)mean;peak=Math.max(peak,Math.abs(loop[i]));}
            if(peak<.0001)throw new IOException("Silent ambient recording: "+id);
            for(int i=0;i<loop.length;i++)loop[i]*=(float)(.45/peak);
            write(output,cue,new float[][]{loop});
            JsonObject entry=new JsonObject();entry.addProperty("path","audio/"+cue+".wav");entry.addProperty("sourceId",id);
            entry.addProperty("sha256",hash(Files.readAllBytes(output.resolve(cue+".wav"))));entry.addProperty("frames",loop.length);
            entry.addProperty("license","CC0-1.0");entry.addProperty("loop",true);
            entry.addProperty("transformation","Preserved mono 48 kHz recording; trim "+trim+" frames per edge; highpass 45 Hz; lowpass "+(rotor?2600:motor?3400:3000)+" Hz; "+overlap+" frame tail/head overlap; 5 ms seam correction; DC removal; linear peak normalization 0.45; no synthesized layers");
            entries.add(entry);
        }
        if(!required.isEmpty())throw new IOException("Missing ambient recordings: "+required);
        JsonObject provenance=new JsonObject();provenance.addProperty("schemaVersion",1);provenance.addProperty("origin","LICENSED_RECORDINGS");
        provenance.addProperty("sourceEvidenceSha256",hash(evidence));provenance.addProperty("generatorPath","src/tools/java/game/wreckriff/tools/GenerateAudio.java");
        provenance.addProperty("generatorSha256",hash(Files.readAllBytes(Path.of("src/tools/java/game/wreckriff/tools/GenerateAudio.java"))));
        provenance.addProperty("artisticStatus","NEEDS_CREATIVE_REVIEW");provenance.add("assets",entries);
        Files.writeString(output.resolve("ambient-provenance.json"),new GsonBuilder().setPrettyPrinting().create().toJson(provenance)+"\n",StandardCharsets.UTF_8);
        Files.write(output.resolve("ambient-sources.json"),evidence);
    }
    private static String hash(byte[] bytes)throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** Recorded rock punctuation; never a synthesized note ladder or a second music loop. */
    private static void prepareResults(Path output,Path sources)throws Exception {
        Path musicSource=sources.resolve("music/menu.wav");
        Path impactSource=sources.resolve("recorded/processed/ram-hit-1.wav");
        byte[] music,impact;
        try(var input=javax.sound.sampled.AudioSystem.getAudioInputStream(musicSource.toFile())) {
            music=input.readNBytes(RATE*4*8);
        }
        try(var input=javax.sound.sampled.AudioSystem.getAudioInputStream(impactSource.toFile())) {
            var format=input.getFormat();
            if(format.getChannels()!=1||format.getSampleRate()!=RATE||format.getSampleSizeInBits()!=16||format.isBigEndian())
                throw new IOException("Result impact source must be PCM48k/16 mono");
            impact=input.readNBytes(RATE*2*3+1);
        }
        if(music.length!=RATE*4*8||impact.length%2!=0||impact.length>RATE*2*3)
            throw new IOException("Unexpected result recording duration");
        List<String> evidence=new ArrayList<>();
        for(String id:List.of("victory","defeat","draw")) {
            boolean defeat=id.equals("defeat");
            double seconds=id.equals("victory")?3.6:defeat?2.6:2.0;
            double phrase=id.equals("victory")?3.2:defeat?1.55:1.6;
            float[] pcm=new float[(int)(seconds*RATE)];
            double phase=0,filtered=0;
            for(int i=0;i<pcm.length;i++) {
                double t=i/(double)RATE;
                double speed=defeat?Math.max(.12,1-Math.max(0,t-.35)*.8):1;
                int frame=(int)phase;double blend=phase-frame;
                double a=(sample(music,frame,0)+sample(music,frame,1))*.5;
                double b=(sample(music,frame+1,0)+sample(music,frame+1,1))*.5;
                double guitar=a+(b-a)*blend;
                double cutoff=defeat?4200*speed:6500;
                filtered+=(1-Math.exp(-TAU*cutoff/RATE))*(guitar-filtered);
                double envelope=t<phrase?Math.min(1,t/.004):Math.exp(-(t-phrase)*12);
                if(t>phrase+.35)envelope=0;
                double metal=i<impact.length/2?(short)((impact[i*2]&255)|(impact[i*2+1]<<8))/32768.0:0;
                pcm[i]=(float)((filtered*.9*envelope+metal*(defeat?.75:.38))
                        *Math.min(1,(pcm.length-1-i)/480.0));
                phase+=speed;
            }
            float[][] channels={pcm};master(channels,.84);write(output,id,channels);
            String edit=defeat?"Recorded guitar/drum attack; continuous tape slowdown 1.0 to 0.12; lowpass follows speed; metal crush; 2.6s"
                    :"Recorded Riffs guitar/drum phrase from prepared source start; mono fold; LP6500Hz; metal accent; short release; "+seconds+"s";
            evidence.add("{\"path\":\"audio/"+id+".wav\",\"sha256\":\""+hash(Files.readAllBytes(output.resolve(id+".wav")))
                    +"\",\"transformation\":\""+edit+"\"}");
        }
        Files.writeString(output.resolve("result-provenance.json"),"""
                {"schemaVersion":1,"author":"Alexander Nakarada","title":"Riffs",
                "license":"CC-BY-4.0","licensePath":"licenses/assets/CC-BY-4.0.txt",
                "sourcePath":"src/tools/assets/audio/music/menu.wav","sourceSha256":"%s",
                "impactSourcePath":"src/tools/assets/audio/recorded/processed/ram-hit-1.wav","impactSourceSha256":"%s",
                "impactLicense":"CC0-1.0","impactProvenance":"audio/sfx-provenance.json",
                "generator":"src/tools/java/game/wreckriff/tools/GenerateAudio.java","assets":[%s]}
                """.formatted(hash(Files.readAllBytes(musicSource)),hash(Files.readAllBytes(impactSource)),String.join(",",evidence)),StandardCharsets.UTF_8);
    }

    /** Original physical-item punctuation, authored offline as PCM; playback never synthesizes audio. */
    private static void preparePickups(Path output)throws Exception {
        String generator="src/tools/java/game/wreckriff/tools/GenerateAudio.java";
        List<String> evidence=new ArrayList<>();
        for(String kind:List.of("homing","power","mine","napalm","ballistic","cannon")) {
            double seconds=switch(kind) {
                case "homing" -> .42;case "power" -> .48;case "mine" -> .38;
                case "napalm" -> .55;case "ballistic" -> .52;case "cannon" -> .50;
                default -> throw new IllegalArgumentException(kind);
            };
            String recipe=switch(kind) {
                case "homing" -> "Metal latch plus a short ascending electronic lock tone";
                case "power" -> "Heavy bolt closure followed by low inharmonic steel and bass body";
                case "mine" -> "Two dry latch clicks followed by a steady non-warning confirmation tone";
                case "napalm" -> "Liquid bubble/slosh followed by a closing valve and short filtered hiss";
                case "ballistic" -> "Cassette latch followed by four separate ascending stepped tones";
                case "cannon" -> "Two spaced heavy inharmonic metal clunks with low ringing tails";
                default -> throw new IllegalArgumentException(kind);
            };
            for(int take=1;take<=3;take++) {
                String id="pickup-"+kind+"-"+take;
                Random random=new Random(SEED^id.hashCode());
                float[] pcm=new float[(int)Math.round(seconds*RATE)];
                double pitch=1+(take-2)*.035,low=0,offset=(take-2)*.004;
                for(int i=0;i<pcm.length;i++) {
                    double t=i/(double)RATE,n=random.nextDouble()*2-1;
                    low+=.08*(n-low);
                    double sound=switch(kind) {
                        case "homing" -> metal(t,.008,560*pitch,54,n)*.60
                                + pickupTone(t,.075+offset,.245,720*pitch,840,.5);
                        case "power" -> metal(t,.01,145*pitch,12,n)*.80
                                + metal(t,.065+offset,230*pitch,20,n)*.36
                                + pickupTone(t,.025,.36,74*pitch,-25,.70);
                        case "mine" -> metal(t,.008,1250*pitch,88,n)*.60
                                + metal(t,.085+offset,1610*pitch,92,n)*.56
                                + pickupTone(t,.16,.14,1045*pitch,0,.58);
                        case "napalm" -> {
                            double water=Math.sin(TAU*(180*pitch*t+480*t*t)+1.9*Math.sin(TAU*37*t));
                            double slosh=(water*.33+low*2.5)*pickupEnvelope(t,.008,.23);
                            double hiss=(n-low)*pickupEnvelope(t,.245+offset,.24)*.20;
                            yield slosh+metal(t,.22+offset,610*pitch,54,n)*.46+hiss;
                        }
                        case "ballistic" -> {
                            double cassette=metal(t,.008,430*pitch,36,n)*.75;
                            for(int step=0;step<4;step++)
                                cassette+=pickupTone(t,.08+step*.075+offset,.060,
                                        (630+step*175)*pitch,0,.53);
                            yield cassette;
                        }
                        case "cannon" -> metal(t,.008,178*pitch,11,n)*.92
                                + metal(t,.16+offset,211*pitch,13,n)*.85
                                + pickupTone(t,.17,.27,92*pitch,0,.24);
                        default -> throw new IllegalArgumentException(kind);
                    };
                    pcm[i]=(float)sound;
                }
                masterPickup(pcm);
                write(output,id,new float[][]{pcm});
                evidence.add("{\"path\":\"audio/"+id+".wav\",\"cue\":\"pickup-"+kind
                        +"\",\"take\":"+take+",\"frames\":"+pcm.length+",\"sha256\":\""
                        +hash(Files.readAllBytes(output.resolve(id+".wav")))+"\",\"recipe\":\""+recipe+"\"}");
            }
        }
        Files.writeString(output.resolve("pickup-provenance.json"),"""
                {"schemaVersion":1,"origin":"ORIGINAL_PROJECT_CONTENT","externalSamples":false,
                "generator":"%s","generatorSha256":"%s","seed":"0x5249464657415645",
                "sampleRate":48000,"channels":1,"bits":16,"rmsTarget":0.16,"peakCeiling":0.82,
                "mastering":"DC removal with silent tapered boundaries; RMS matching with linear peak ceiling; no clipped samples",
                "previousRecipe":"docs/asset-history/GenerateAudio-before-recorded-results.java",
                "artisticStatus":"NEEDS_CREATIVE_REVIEW","assets":[%s]}
                """.formatted(generator,hash(Files.readAllBytes(Path.of(generator))),String.join(",",evidence)),StandardCharsets.UTF_8);
    }

    /** Original player-special cues. Integer-period loop components have no crossfade seam. */
    private static void prepareSpecials(Path output)throws Exception {
        String generator="src/tools/java/game/wreckriff/tools/GenerateAudio.java";
        List<String> evidence=new ArrayList<>();
        for(String id:List.of("special-pulse-charge","special-pulse-hit","special-grinder-start",
                "special-grinder-loop","special-dash","special-bomb-warning")) {
            double seconds=switch(id) {
                case "special-pulse-charge","special-pulse-hit" -> .3;
                case "special-grinder-start" -> .6;case "special-grinder-loop" -> .5;
                case "special-dash" -> .22;case "special-bomb-warning" -> .7;
                default -> throw new IllegalArgumentException(id);
            };
            String recipe=switch(id) {
                case "special-pulse-charge" -> "Low rising 70 to 230 Hz coil with a quiet inharmonic metal overtone; 0.3 second windup";
                case "special-pulse-hit" -> "Falling 145 Hz bass thump with short filtered impact noise and resonant steel; 0.3 second decay";
                case "special-grinder-start" -> "Rising 32 to 104 Hz motor with gear overtones and filtered friction; 0.6 second spinup";
                case "special-grinder-loop" -> "Integer-cycle 68/104/414 Hz gears with 26 Hz rotor modulation and seeded periodic friction; seamless 0.5 second loop";
                case "special-dash" -> "Seeded highpass air hiss with short valve onset and rapid 0.22 second release";
                case "special-bomb-warning" -> "Four clear warning beeps at 0.01, 0.23, 0.43 and 0.57 seconds; rising pitch before 0.7 second fuse";
                default -> throw new IllegalArgumentException(id);
            };
            Random random=new Random(SEED^id.hashCode());
            float[] pcm=new float[(int)Math.round(seconds*RATE)];
            double low=0;double[] phases=new double[16];
            for(int harmonic=0;harmonic<phases.length;harmonic++)phases[harmonic]=random.nextDouble()*TAU;
            boolean loop=id.equals("special-grinder-loop");
            for(int frame=0;frame<pcm.length;frame++) {
                double t=frame/(double)RATE,n=random.nextDouble()*2-1;
                low+=.055*(n-low);
                double sound=switch(id) {
                    case "special-pulse-charge" -> Math.sin(TAU*(70*t+160*t*t/(2*seconds)))*(.12+.65*t/seconds)
                            +Math.sin(TAU*(185*t+350*t*t))*.12;
                    case "special-pulse-hit" -> (Math.sin(TAU*(145*t-130*t*t))*.9+low*1.7)*Math.exp(-t*17)
                            +metal(t,.003,235,22,n)*.25;
                    case "special-grinder-start" -> {
                        double rotor=TAU*(32*t+72*t*t/(2*seconds));
                        yield (Math.sin(rotor)+.24*Math.sin(rotor*4)+low*.8)*(.16+.5*t/seconds);
                    }
                    case "special-grinder-loop" -> {
                        double friction=0;
                        for(int harmonic=0;harmonic<phases.length;harmonic++)
                            friction+=Math.sin(TAU*(326+harmonic*46)*t+phases[harmonic])*.018;
                        yield Math.sin(TAU*68*t)*.24+Math.sin(TAU*104*t)*(.38+.14*Math.sin(TAU*26*t))
                                +Math.sin(TAU*414*t)*.10+friction;
                    }
                    case "special-dash" -> (n-low)*.75*Math.exp(-t*8)+metal(t,.002,560,65,n)*.22;
                    case "special-bomb-warning" -> pickupTone(t,.01,.075,840,0,.9)
                            +pickupTone(t,.23,.075,920,0,.9)+pickupTone(t,.43,.075,1040,0,.9)
                            +pickupTone(t,.57,.095,1180,0,.9);
                    default -> throw new IllegalArgumentException(id);
                };
                pcm[frame]=(float)sound;
            }
            if(loop)master(new float[][]{pcm},.72);else masterPickup(pcm);
            write(output,id,new float[][]{pcm});
            evidence.add("{\"path\":\"audio/"+id+".wav\",\"frames\":"+pcm.length+",\"loop\":"+loop
                    +",\"sha256\":\""+hash(Files.readAllBytes(output.resolve(id+".wav")))+"\",\"recipe\":\""+recipe+"\"}");
        }
        Files.writeString(output.resolve("special-provenance.json"),"""
                {"schemaVersion":1,"origin":"ORIGINAL_PROJECT_CONTENT","externalSamples":false,
                "generator":"%s","generatorSha256":"%s","seed":"0x5249464657415645",
                "sampleRate":48000,"channels":1,"bits":16,
                "mastering":"One shots: silent tapered edges, DC cleanup, RMS target 0.16 and peak ceiling 0.82. Loop: periodic waveform, DC cleanup and peak 0.72.",
                "artisticStatus":"NEEDS_CREATIVE_REVIEW","assets":[%s]}
                """.formatted(generator,hash(Files.readAllBytes(Path.of(generator))),String.join(",",evidence)),StandardCharsets.UTF_8);
    }

    /** Arena phase punctuation. No idle/active loop or secondary hazard timer is created at runtime. */
    private static void prepareArenaHazards(Path output)throws Exception {
        String generator="src/tools/java/game/wreckriff/tools/GenerateAudio.java";
        List<String> evidence=new ArrayList<>();
        for(String kind:List.of("crane","traffic","carousel","electric","fire","barrier"))
            for(boolean warning:new boolean[]{true,false}) {
                String id="hazard-"+kind+(warning?"-warning":"-active");
                double seconds=warning?1.2:switch(kind) {
                    case "crane" -> .95;case "traffic" -> 1;case "carousel" -> 1.1;
                    case "electric" -> .8;case "fire" -> 1.3;case "barrier" -> .9;
                    default -> throw new IllegalArgumentException(kind);
                };
                String recipe=switch(kind) {
                    case "crane" -> warning?"Three low hoist alarms over a tensioned steel ratchet":"Falling heavy load; bass impact, flexing steel and concrete debris";
                    case "traffic" -> warning?"Two alternating vehicle horns with an approaching engine":"Passing engine rush, tire scrub and a short road impact";
                    case "carousel" -> warning?"Uneven fairground three-note alarm and motor windup":"Rotating mechanism and inharmonic gantry strike";
                    case "electric" -> warning?"Rising electrical charge with pulsed high-frequency ticks":"Sharp electric arc followed by decaying mains buzz";
                    case "fire" -> warning?"Pressure-valve rattle and rising pressurised hiss":"Low ignition whoosh with filtered crackling fire";
                    case "barrier" -> warning?"Two pneumatic gate alerts followed by servomotor tension":"Sliding gate grind with a mechanical end-stop";
                    default -> throw new IllegalArgumentException(kind);
                };
                Random random=new Random(SEED^id.hashCode());float[] pcm=new float[(int)Math.round(seconds*RATE)];double low=0;
                for(int frame=0;frame<pcm.length;frame++) {
                    double t=frame/(double)RATE,n=random.nextDouble()*2-1;low+=.055*(n-low);
                    double value;
                    if(warning)value=switch(kind) {
                        case "crane" -> pickupTone(t,.02,.20,315,0,.7)+pickupTone(t,.36,.20,365,0,.7)
                                +pickupTone(t,.70,.23,415,0,.7)+metal(t,.015,143,4,n)*.22;
                        case "traffic" -> pickupTone(t,.025,.32,380,0,.65)+pickupTone(t,.025,.32,510,0,.4)
                                +pickupTone(t,.55,.34,460,0,.7)+pickupTone(t,.55,.34,610,0,.35)
                                +Math.sin(TAU*(65*t+38*t*t))*.16*pickupEnvelope(t,.01,1.1);
                        case "carousel" -> pickupTone(t,.02,.24,620,0,.6)+pickupTone(t,.34,.24,795,0,.6)
                                +pickupTone(t,.68,.25,705,0,.6)+Math.sin(TAU*(40*t+70*t*t))*.23*pickupEnvelope(t,.08,1.1);
                        case "electric" -> Math.sin(TAU*(230*t+700*t*t))*.42*pickupEnvelope(t,.01,1.08)
                                +(n-low)*.26*Math.pow(Math.max(0,Math.sin(TAU*9*t)),8)*pickupEnvelope(t,.01,1.08);
                        case "fire" -> (n-low)*(.08+.50*t)*pickupEnvelope(t,.02,1.1)
                                +metal(t,.05,380,21,n)*.5+metal(t,.28,540,24,n)*.35;
                        case "barrier" -> pickupTone(t,.02,.14,820,0,.58)+pickupTone(t,.33,.14,590,0,.58)
                                +Math.sin(TAU*(95*t+90*t*t))*.23*pickupEnvelope(t,.45,.62)+(n-low)*.10*pickupEnvelope(t,.05,.8);
                        default -> throw new IllegalArgumentException(kind);
                    };
                    else value=switch(kind) {
                        case "crane" -> metal(t,.02,92,7,n)*.9+low*2.5*Math.exp(-t*6)
                                +metal(t,.15,230,14,n)*.3;
                        case "traffic" -> Math.sin(TAU*(180*t-62*t*t))*.32*pickupEnvelope(t,.01,.9)
                                +(n-low)*.4*pickupEnvelope(t,.09,.73)+metal(t,.08,190,24,n)*.4;
                        case "carousel" -> Math.sin(TAU*82*t)*(1+.6*Math.sin(TAU*12*t))*.3*Math.exp(-t*3)
                                +metal(t,.02,460,7,n)*.62;
                        case "electric" -> (n-low)*.68*Math.exp(-t*9)
                                +Math.tanh(Math.sin(TAU*100*t)*2)*.42*Math.exp(-t*5);
                        case "fire" -> (low*3.2+(n-low)*.22)*Math.exp(-t*2.8)
                                +Math.sin(TAU*(94*t-20*t*t))*.55*Math.exp(-t*6);
                        case "barrier" -> Math.sin(TAU*(115*t+23*t*t))*.35*pickupEnvelope(t,.01,.68)
                                +low*1.3*pickupEnvelope(t,.01,.68)+metal(t,.65,290,20,n)*.65;
                        default -> throw new IllegalArgumentException(kind);
                    };
                    pcm[frame]=(float)value;
                }
                masterPickup(pcm);write(output,id,new float[][]{pcm});
                evidence.add("{\"path\":\"audio/"+id+".wav\",\"kind\":\""+kind+"\",\"phase\":\""+(warning?"warning":"active")
                        +"\",\"frames\":"+pcm.length+",\"sha256\":\""+hash(Files.readAllBytes(output.resolve(id+".wav")))
                        +"\",\"recipe\":\""+recipe+"\"}");
            }
        Files.writeString(output.resolve("arena-hazard-provenance.json"),"""
                {"schemaVersion":1,"origin":"ORIGINAL_PROJECT_CONTENT","externalSamples":false,
                "generator":"%s","generatorSha256":"%s","seed":"0x5249464657415645",
                "sampleRate":48000,"channels":1,"bits":16,"looping":false,
                "mastering":"Silent tapered edges; DC cleanup; RMS target 0.16 and linear peak ceiling 0.82",
                "artisticStatus":"NEEDS_CREATIVE_REVIEW","assets":[%s]}
                """.formatted(generator,hash(Files.readAllBytes(Path.of(generator))),String.join(",",evidence)),StandardCharsets.UTF_8);
    }

    /** Original boss windup, shorter than the minimum 0.9 second attack telegraph. */
    private static void prepareBossTelegraph(Path output)throws Exception {
        String id="boss-telegraph",generator="src/tools/java/game/wreckriff/tools/GenerateAudio.java";
        float[] pcm=new float[40800];Random random=new Random(SEED^id.hashCode());double low=0;
        for(int frame=0;frame<pcm.length;frame++) {
            double t=frame/(double)RATE,n=random.nextDouble()*2-1;low+=.055*(n-low);
            pcm[frame]=(float)(pickupTone(t,.01,.21,270,210,.72)+pickupTone(t,.29,.17,405,300,.74)
                    +pickupTone(t,.53,.22,625,390,.77)
                    +(Math.sin(TAU*(62*t+80*t*t))*.22+low*.5)*pickupEnvelope(t,.01,.81)
                    +metal(t,.012,184,17,n)*.16);
        }
        masterPickup(pcm);write(output,id,new float[][]{pcm});
        Files.writeString(output.resolve("boss-telegraph-provenance.json"),"""
                {"schemaVersion":1,"origin":"ORIGINAL_PROJECT_CONTENT","externalSamples":false,
                "generator":"%s","generatorSha256":"%s","seed":"0x5249464657415645",
                "sampleRate":48000,"channels":1,"bits":16,"looping":false,
                "mastering":"Silent tapered edges; DC cleanup; RMS target 0.16 and linear peak ceiling 0.82",
                "artisticStatus":"NEEDS_CREATIVE_REVIEW","assets":[{
                "path":"audio/boss-telegraph.wav","frames":40800,"sha256":"%s",
                "recipe":"Three tightening rising alarm pulses at 0.01, 0.29 and 0.53 seconds over low motor tension and a short steel latch; original deterministic synthesis; 0.85 seconds"}]}
                """.formatted(generator,hash(Files.readAllBytes(Path.of(generator))),
                        hash(Files.readAllBytes(output.resolve(id+".wav")))),StandardCharsets.UTF_8);
    }

    private static double pickupEnvelope(double t,double start,double duration) {
        double age=t-start;
        if(age<=0||age>=duration)return 0;
        return Math.min(1,age/.004)*Math.min(1,(duration-age)/.025);
    }

    private static double pickupTone(double t,double start,double duration,double hz,double sweep,double gain) {
        double age=t-start;
        return age<=0?0:gain*Math.sin(TAU*(hz*age+sweep*age*age*.5))
                *pickupEnvelope(t,start,duration)*Math.exp(-age*4);
    }

    private static double metal(double t,double start,double hz,double decay,double noise) {
        double age=t-start;
        if(age<=0)return 0;
        double modes=Math.sin(TAU*hz*age)+.47*Math.sin(TAU*hz*2.71*age)
                +.23*Math.sin(TAU*hz*4.13*age);
        return Math.min(1,age/.002)*(modes*Math.exp(-decay*age)+noise*.5*Math.exp(-age*100));
    }

    private static void masterPickup(float[] pcm) {
        // Weighted DC removal preserves silent endpoints; release remains 30ms even for the metal tails.
        double sum=0,weight=0;
        for(int i=0;i<pcm.length;i++) {
            double edge=Math.min(1,i/144.0)*Math.min(1,(pcm.length-i-1)/1440.0);
            pcm[i]*=(float)edge;sum+=pcm[i];weight+=edge;
        }
        double peak=0,squares=0,mean=sum/weight;
        for(int i=0;i<pcm.length;i++) {
            double edge=Math.min(1,i/144.0)*Math.min(1,(pcm.length-i-1)/1440.0);
            pcm[i]-=(float)(mean*edge);peak=Math.max(peak,Math.abs(pcm[i]));squares+=pcm[i]*pcm[i];
        }
        if(peak==0)throw new IllegalStateException("Silent pickup asset");
        double gain=Math.min(.16/Math.sqrt(squares/pcm.length),.82/peak);
        for(int i=0;i<pcm.length;i++)pcm[i]*=(float)gain;
    }

    /** Mechanical UI vocabulary and workshop bed, independent of paused gameplay notifications. */
    private static void prepareMenuEffects(Path output)throws Exception {
        List<String> evidence=new ArrayList<>();
        for(String cue:List.of("ui-nav","ui-change","ui-confirm","ui-back","ui-vehicle","menu-ambience")) {
            boolean ambience=cue.equals("menu-ambience");
            double seconds=switch(cue) {
                case "ui-nav" -> .11;case "ui-change" -> .16;case "ui-confirm" -> .30;
                case "ui-back" -> .24;case "ui-vehicle" -> .48;default -> 16;
            };
            double pitch=switch(cue) {
                case "ui-nav" -> 880;case "ui-change" -> 560;case "ui-confirm" -> 300;
                case "ui-back" -> 420;case "ui-vehicle" -> 145;default -> 60;
            };
            float[] pcm=new float[(int)Math.round(seconds*RATE)];
            Random noise=new Random(SEED^cue.hashCode());double low=0;
            for(int i=0;i<pcm.length;i++) {
                double t=i/(double)RATE,n=noise.nextDouble()*2-1;low+=.018*(n-low);
                double sound;
                if(ambience) {
                    // Fan, transformer hum, slow air movement and a distant cyclic tool rattle.
                    sound=.32*Math.sin(TAU*60*t)+.10*Math.sin(TAU*120*t)+.06*Math.sin(TAU*181*t)
                            +low*.9*(.8+.2*Math.sin(TAU*t/8))
                            +.045*Math.sin(TAU*733*t)*Math.pow(Math.max(0,Math.sin(TAU*t/4)),24);
                } else {
                    sound=metal(t,0,pitch,35,n)*.65;
                    if(cue.equals("ui-confirm"))sound+=metal(t,.065,pitch*1.51,22,n)*.50;
                    if(cue.equals("ui-back"))sound+=metal(t,.045,pitch*.63,35,n)*.36;
                    if(cue.equals("ui-vehicle"))sound+=metal(t,.095,92,14,n)*.7
                            +low*Math.exp(-t*9)*2.5;
                    sound*=Math.min(1,i/72.0)*Math.min(1,(pcm.length-1-i)/240.0);
                }
                pcm[i]=(float)sound;
            }
            if(ambience) {
                // Tiny de-click fades, with whole-number oscillator periods in the 16s bed.
                for(int i=0;i<240;i++){pcm[i]*=i/239f;pcm[pcm.length-1-i]*=i/239f;}
            }
            float[][] channels={pcm};master(channels,ambience?.62:.82);write(output,cue,channels);
            evidence.add("{\"path\":\"audio/"+cue+".wav\",\"loop\":"+ambience+",\"frames\":"+pcm.length
                    +",\"sha256\":\""+hash(Files.readAllBytes(output.resolve(cue+".wav")))
                    +"\",\"recipe\":\""+(ambience?"Original fan hum and filtered air; quiet distant mechanical rattle"
                    :"Original inharmonic steel modes and filtered latch noise; distinct timing and pitch for "+cue)+"\"}");
        }
        String generator="src/tools/java/game/wreckriff/tools/GenerateAudio.java";
        Files.writeString(output.resolve("menu-provenance.json"),"""
                {"schemaVersion":1,"origin":"ORIGINAL_PROJECT_CONTENT","externalSamples":false,
                "generator":"%s","generatorSha256":"%s","seed":"0x5249464657415645",
                "artisticStatus":"NEEDS_CREATIVE_REVIEW","assets":[%s]}
                """.formatted(generator,hash(Files.readAllBytes(Path.of(generator))),String.join(",",evidence)),StandardCharsets.UTF_8);
    }

    private static void effect(Path output, String id) throws Exception {
        boolean loop = Set.of("engine-idle", "engine-drive", "turbo-loop", "tyre-slip", "hazard-active").contains(id);
        double seconds = switch (id) {
            case "engine-idle", "engine-drive", "turbo-loop", "tyre-slip", "hazard-active" -> 2;
            case "hazard-warning" -> 1.1;
            case "low-hp" -> .65;
            case "empty" -> .11;
            default -> .38;
        };
        Random random = new Random(SEED ^ id.hashCode());
        float[] pcm = new float[(int) (seconds * RATE)];
        double low = 0;
        for (int i = 0; i < pcm.length; i++) {
            double t = i / (double) RATE, n = random.nextDouble() * 2 - 1;
            low += .045 * (n - low);
            double decay = Math.exp(-t * 11);
            double sound = switch (id) {
                case "engine-idle", "engine-drive" -> {
                    double hz = id.equals("engine-idle") ? 42 : 89;
                    double pulse = Math.pow(Math.max(0, Math.sin(TAU * hz * t)), 5);
                    yield Math.tanh((pulse - .17) * 3) * .55 + Math.sin(TAU * hz * .5 * t) * .18
                            + Math.sin(TAU * hz * 3 * t) * .08 + low * .06;
                }
                case "turbo-loop" -> low * 3 + Math.sin(TAU * 610 * t + 1.4 * Math.sin(TAU * 31 * t)) * .25;
                case "tyre-slip" -> (n - low) * .38 + Math.sin(TAU * 1037 * t + 4 * Math.sin(TAU * 57 * t)) * .18;
                case "hazard-active" -> Math.tanh(Math.sin(TAU * 50 * t) * 3) * .36
                        + n * Math.pow(Math.max(0, Math.sin(TAU * 13 * t)), 14) * .6;
                case "mine-place" -> (Math.sin(TAU * 267 * t) * .8 + n * .55) * Math.exp(-t * 24)
                        + (t > .16 ? Math.sin(TAU * 1300 * (t - .16)) * Math.exp(-(t - .16) * 35) * .22 : 0);
                case "empty" -> Math.sin(TAU * 179 * t) * decay + n * Math.exp(-t * 120) * .6;
                case "low-hp" -> Math.sin(TAU * 350 * t) * ((t < .11 || t > .25 && t < .37) ? .6 : 0);
                case "hazard-warning" -> Math.sin(TAU * (480 * t + 40 * t * t))
                        * (.35 + .2 * Math.sin(TAU * 5 * t)) * Math.min(1, (seconds - t) * 10);
                case "pickup-repair" -> chime(t, 392, 523.25, 659.25);
                case "pickup-turbo" -> Math.sin(TAU * (300 * t + 900 * t * t)) * Math.exp(-t * 6);
                default -> throw new IllegalArgumentException(id);
            };
            if (!loop) sound *= Math.min(1, i / 96.0) * Math.min(1, (pcm.length - i - 1) / 480.0);
            pcm[i] = (float) sound;
        }
        if (loop) {
            // Overlap the stochastic boundary; periodic engine harmonics already meet in phase.
            int cross = RATE / 25;
            for (int i = 0; i < cross; i++) {
                double w = i / (double) (cross - 1);
                pcm[pcm.length - cross + i] = (float) (pcm[pcm.length - cross + i] * (1 - w) + pcm[i] * w);
            }
            // Last sample meets sample zero. The crossfade then travels through the same head twice
            // only over 40 ms, trading minor modulation for a quiet join on the continuous loops.
            for (int i = 0; i < 160; i++) pcm[pcm.length - 160 + i] *= 1f - i / 159f;
            for (int i = 0; i < 160; i++) pcm[i] *= i / 159f;
        }
        float[][] channels = {pcm};
        master(channels, loop ? .69 : .88);
        write(output, id, channels);
    }

    private static double chime(double t, double a, double b, double c) {
        double sound = Math.sin(TAU * a * t) * Math.exp(-t * 12);
        if (t > .075) sound += Math.sin(TAU * b * (t - .075)) * Math.exp(-(t - .075) * 13);
        if (t > .15) sound += Math.sin(TAU * c * (t - .15)) * Math.exp(-(t - .15) * 14);
        return sound * .65;
    }

    private static void master(float[][] pcm, double target) {
        double peak = 0;
        for (float[] channel : pcm) {
            double sum = 0;
            for (float value : channel) sum += value;
            double mean = sum / channel.length;
            for (int i = 0; i < channel.length; i++) {
                channel[i] = (float) Math.tanh((channel[i] - mean) * .83);
            }
            // Nonlinear saturation of asymmetric waveforms can reintroduce a DC component.
            sum = 0;
            for(float value:channel) sum+=value;
            mean=sum/channel.length;
            for(int i=0;i<channel.length;i++) {
                channel[i]-=(float)mean;
                peak = Math.max(peak, Math.abs(channel[i]));
            }
        }
        if (peak == 0) throw new IllegalStateException("Silent asset");
        for (float[] channel : pcm) for (int i = 0; i < channel.length; i++) channel[i] *= target / peak;
    }

    private static void write(Path output, String id, float[][] pcm) throws Exception {
        Path path = output.resolve(id + ".wav");
        int size = pcm[0].length * pcm.length * 2;
        double peak = 0, squares = 0;
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
            out.writeBytes("RIFF"); littleInt(out, size + 36); out.writeBytes("WAVEfmt "); littleInt(out, 16);
            littleShort(out, 1); littleShort(out, pcm.length); littleInt(out, RATE);
            littleInt(out, RATE * pcm.length * 2); littleShort(out, pcm.length * 2); littleShort(out, 16);
            out.writeBytes("data"); littleInt(out, size);
            for (int frame = 0; frame < pcm[0].length; frame++) for (float[] channel : pcm) {
                double value = channel[frame];
                if (!Double.isFinite(value) || Math.abs(value) >= 1) throw new IllegalStateException("Invalid PCM: " + id);
                peak = Math.max(peak, Math.abs(value)); squares += value * value;
                littleShort(out, (int) Math.round(value * 32767));
            }
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] block = new byte[16384];
            for (int length; (length = input.read(block)) >= 0;) digest.update(block, 0, length);
        }
        METRICS.add(String.format(Locale.ROOT, "%s.wav,%d,%d,%d,16,%.6f,%.6f,%s", id,
                pcm[0].length, pcm.length, RATE, peak, Math.sqrt(squares / (pcm[0].length * (double) pcm.length)),
                HexFormat.of().formatHex(digest.digest())));
    }

    private static void littleInt(DataOutputStream out, int value) throws IOException { out.writeInt(Integer.reverseBytes(value)); }
    private static void littleShort(DataOutputStream out, int value) throws IOException { out.writeShort(Short.reverseBytes((short) value)); }
}

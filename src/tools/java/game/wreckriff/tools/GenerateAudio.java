package game.wreckriff.tools;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Local licensed music preparation and original deterministic sound-effect synthesis. No build-time network. */
public final class GenerateAudio {
    public static final int RATE = 48_000;
    public static final long SEED = 0x5249464657415645L;
    private static final double TAU = 2 * Math.PI;
    private static final List<String> METRICS = new ArrayList<>();
    private GenerateAudio() {}

    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("GenerateAudio <output-resources-root> <audio-source-directory>");
        Path output=Path.of(args[0]).resolve("audio"),sources=Path.of(args[1]);
        Files.createDirectories(output);
        // Remove superseded outputs when upgrading an existing build directory.
        Files.deleteIfExists(output.resolve("dead-air-circuit.wav"));
        Files.deleteIfExists(output.resolve("overheat.wav"));
        METRICS.clear();METRICS.add("asset,frames,channels,sample_rate,bits,peak,rms,sha256");
        prepareMusic(output,sources);
        for(String id:List.of("engine-idle","engine-drive","turbo-loop","tyre-slip","machine-gun",
                "homing-launch","power-launch","pulse","empty","metal-hit","explosion","destroyed",
                "low-hp","hazard-warning","hazard-active","pickup-repair","pickup-ammo","pickup-turbo",
                "ui-nav","ui-confirm","victory","defeat","draw","mine-place","mine-detonate",
                "napalm-launch","napalm-fire","freeze","stun","shield"))effect(output,id);
        Files.write(output.resolve("audio-metrics.csv"),METRICS,StandardCharsets.UTF_8);
        Files.writeString(output.resolve("score.txt"), """
                WRECK RIFF / METALMANIA
                Metalmania by Kevin MacLeod (incompetech.com), ISRC USUAN1700023, CC BY 4.0.
                https://incompetech.com/music/royalty-free/index.html?Search=Search&isrc=USUAN1700023
                https://creativecommons.org/licenses/by/4.0/
                Creator instrumentation: Drums, Bass, Guitar. 150 BPM; source duration about 190 seconds.
                Imported creator MP3 decoded once to local stereo PCM 48000Hz / 16-bit.
                Loop edit: source 0..179.3s; final 100ms equal-power crossfade into source 0..0.1s;
                output starts at source 0.1s, yielding 179.2s (112 bars at 150 BPM), no ending fade gap.
                DC removal and linear peak normalization to 0.84; no synthesized replacement music.
                Original mono sound effects: deterministic analytical synthesis seed 0x5249464657415645.
                Original rejected 0.1 score/glyph recipes retained in docs/asset-history, excluded from runtime.
                Artistic status: NEEDS_CREATIVE_REVIEW. Signal/spectral metrics do not prove listening approval.
                """,StandardCharsets.UTF_8);
        System.out.println("Prepared licensed guitar track and 30 original effects in "+output);
    }

    private static void prepareMusic(Path output,Path sources)throws Exception {
        Path source=sources.resolve("Metalmania-source.wav");
        byte[] bytes;
        try(var input=javax.sound.sampled.AudioSystem.getAudioInputStream(source.toFile())) {
            var format=input.getFormat();
            if(format.getChannels()!=2 || format.getSampleRate()!=RATE || format.getSampleSizeInBits()!=16
                    || format.isBigEndian() || !format.getEncoding().equals(javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED))
                throw new IllegalArgumentException("Music source must be stereo signed little-endian PCM48k/16");
            bytes=input.readNBytes(RATE*4*211+1);
        }
        if(bytes.length<180*RATE*4 || bytes.length>210*RATE*4 || bytes.length%4!=0)
            throw new IllegalArgumentException("Unexpected source music duration");
        int cross=RATE/10,frames=(int)Math.round(179.2*RATE),end=frames+cross;
        float[][] pcm=new float[2][frames];
        for(int frame=0;frame<frames;frame++)for(int channel=0;channel<2;channel++) {
            int sourceFrame=frame+cross;
            double value=sample(bytes,sourceFrame,channel);
            if(sourceFrame>=end-cross) {
                int offset=sourceFrame-(end-cross);
                double blend=offset/(double)(cross-1);
                value=value*Math.cos(blend*Math.PI/2)+sample(bytes,offset,channel)*Math.sin(blend*Math.PI/2);
            }
            pcm[channel][frame]=(float)value;
        }
        double peak=0;
        for(float[] channel:pcm) {
            double mean=0;for(float value:channel)mean+=value;mean/=channel.length;
            for(int i=0;i<channel.length;i++){channel[i]-=(float)mean;peak=Math.max(peak,Math.abs(channel[i]));}
        }
        if(peak==0)throw new IllegalArgumentException("Silent music source");
        for(float[] channel:pcm)for(int i=0;i<channel.length;i++)channel[i]*=.84/peak;
        write(output,"metalmania",pcm);
        String sourceEvidence=Files.readString(sources.resolve("source.json"),StandardCharsets.UTF_8);
        Files.writeString(output.resolve("music-source.json"),sourceEvidence,StandardCharsets.UTF_8);
        Files.writeString(output.resolve("music-provenance.json"),"""
                {"schemaVersion":1,"title":"Metalmania","author":"Kevin MacLeod","license":"CC-BY-4.0",
                "licensePath":"licenses/assets/CC-BY-4.0.txt",
                "sourceUrl":"https://incompetech.com/music/royalty-free/mp3-royaltyfree/Metalmania.mp3",
                "sourcePath":"src/tools/assets/audio/Metalmania-source.wav","sourceSha256":"%s",
                "sourceEvidenceSha256":"%s","sha256":"%s","frames":%d,"sampleRate":48000,"channels":2,
                "edit":"179.2s / 112 bars at150BPM;100ms equal-power loop crossfade; DC removal; linear peak0.84",
                "artisticStatus":"NEEDS_CREATIVE_REVIEW"}
                """.formatted(hash(Files.readAllBytes(source)),hash(sourceEvidence.getBytes(StandardCharsets.UTF_8)),
                hash(Files.readAllBytes(output.resolve("metalmania.wav"))),frames),StandardCharsets.UTF_8);
    }

    private static double sample(byte[] data,int frame,int channel) {
        int offset=frame*4+channel*2;
        return (short)((data[offset]&255)|(data[offset+1]<<8))/32768.0;
    }
    private static String hash(byte[] bytes)throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void effect(Path output, String id) throws Exception {
        boolean loop = Set.of("engine-idle", "engine-drive", "turbo-loop", "tyre-slip", "hazard-active", "napalm-fire").contains(id);
        double seconds = switch (id) {
            case "engine-idle", "engine-drive", "turbo-loop", "tyre-slip", "hazard-active", "napalm-fire" -> 2;
            case "destroyed", "victory", "defeat", "draw" -> 2.4;
            case "explosion", "pulse", "hazard-warning" -> 1.1;
            case "homing-launch", "power-launch", "low-hp" -> .65;
            case "machine-gun", "empty", "ui-nav" -> .11;
            case "mine-detonate", "shield" -> 1.2;
            case "freeze", "stun" -> .8;
            default -> .38;
        };
        Random random = new Random(SEED ^ id.hashCode());
        float[] pcm = new float[(int) (seconds * RATE)];
        double low = 0, phase = 0;
        for (int i = 0; i < pcm.length; i++) {
            double t = i / (double) RATE, n = random.nextDouble() * 2 - 1;
            low += .045 * (n - low);
            double decay = Math.exp(-t * (id.equals("destroyed") ? 2.7 : id.equals("explosion") ? 5 : 11));
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
                case "napalm-fire" -> low * 2.8 * (.7 + .2 * Math.sin(TAU * 3 * t))
                        + n * Math.pow(Math.max(0, Math.sin(TAU * 19 * t)), 24) * .35;
                case "mine-place" -> (Math.sin(TAU * 267 * t) * .8 + n * .55) * Math.exp(-t * 24)
                        + (t > .16 ? Math.sin(TAU * 1300 * (t - .16)) * Math.exp(-(t - .16) * 35) * .22 : 0);
                case "mine-detonate" -> (low * 3.1 + Math.sin(TAU * 37 * t) * .7 + n * Math.exp(-t * 18) * .5)
                        * Math.exp(-t * 5.5);
                case "napalm-launch" -> (low * 2.9 + Math.sin(TAU * (130 * t - 85 * t * t)) * .5)
                        * Math.exp(-t * 7) + (n - low) * Math.exp(-t * 65) * .4;
                case "freeze" -> (Math.sin(TAU * (1300 * t - 460 * t * t))
                        + .35 * Math.sin(TAU * 2309 * t) + .25 * (n - low)) * Math.exp(-t * 6);
                case "stun" -> (Math.sin(TAU * 91 * t) * .6 + n * .5)
                        * Math.exp(-t * 7) * (.6 + .4 * Math.cos(TAU * 31 * t));
                case "shield" -> (Math.sin(TAU * (210 * t + 115 * t * t))
                        + .4 * Math.sin(TAU * 631 * t)) * Math.exp(-t * 4);
                case "machine-gun" -> (n * .8 + Math.sin(TAU * (130 * t + 3 * (1 - Math.exp(-t * 90))))) * decay;
                case "homing-launch", "power-launch" -> {
                    boolean power = id.equals("power-launch");
                    phase += TAU * ((power ? 210 : 580) * Math.exp(-t * 5) + 48) / RATE;
                    yield (Math.sin(phase) * .7 + low * 2.4 + n * Math.exp(-t * 45) * .35)
                            * Math.exp(-t * (power ? 6 : 8));
                }
                case "pulse" -> Math.sin(TAU * (75 * t + 120 * t * t)) * Math.exp(-t * 4)
                        * (.6 + .3 * Math.sin(TAU * 18 * t)) + low * 1.3 * Math.exp(-t * 7);
                case "explosion", "destroyed" -> (low * 3 + n * Math.exp(-t * 10) * .45
                        + Math.sin(TAU * 42 * t) * .55) * decay
                        + (id.equals("destroyed") && t > .19 ? Math.sin(TAU * 317 * t) * Math.exp(-(t - .19) * 6) * .2 : 0);
                case "metal-hit" -> (Math.sin(TAU * 521 * t) + .42 * Math.sin(TAU * 1379 * t)
                        + n * Math.exp(-t * 70)) * decay;
                case "empty" -> Math.sin(TAU * 179 * t) * decay + n * Math.exp(-t * 120) * .6;
                case "low-hp" -> Math.sin(TAU * 350 * t) * ((t < .11 || t > .25 && t < .37) ? .6 : 0);
                case "hazard-warning" -> Math.sin(TAU * (480 * t + 40 * t * t))
                        * (.35 + .2 * Math.sin(TAU * 5 * t)) * Math.min(1, (seconds - t) * 10);
                case "pickup-repair" -> chime(t, 392, 523.25, 659.25);
                case "pickup-ammo" -> chime(t, 220, 329.63, 440) + n * Math.exp(-t * 80) * .3;
                case "pickup-turbo" -> Math.sin(TAU * (300 * t + 900 * t * t)) * Math.exp(-t * 6);
                case "ui-nav" -> Math.sin(TAU * 740 * t) * Math.exp(-t * 45);
                case "ui-confirm" -> chime(t, 493.88, 659.25, 987.77);
                case "victory" -> fanfare(t, new int[]{38, 45, 50, 53, 57, 62});
                case "defeat" -> fanfare(t, new int[]{50, 48, 45, 41, 38, 26});
                case "draw" -> fanfare(t, new int[]{38, 45, 41, 45, 38, 38});
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

    private static double fanfare(double t, int[] notes) {
        int index = Math.min(notes.length - 1, (int) (t / .24));
        double age = t - index * .24, f = frequency(notes[index]);
        return Math.tanh(2 * (Math.sin(TAU * f * age) + .38 * Math.sin(TAU * f * 1.5 * age)))
                * Math.exp(-age * 4.4) * Math.min(1, age * 400);
    }

    private static double frequency(int midi) { return 440 * Math.pow(2, (midi - 69) / 12.0); }

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

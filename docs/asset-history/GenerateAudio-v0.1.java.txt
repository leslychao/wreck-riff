package game.wreckriff.tools;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Original score and sound design. No samples, instruments, editors or network input. */
public final class GenerateAudio {
    public static final int RATE = 48_000;
    public static final long SEED = 0x5249464657415645L;
    private static final double BEAT = 60.0 / 128;
    private static final int FRAMES = (int) Math.round(96 * 4 * BEAT * RATE);
    private static final double TAU = 2 * Math.PI;
    private static final List<String> METRICS = new ArrayList<>();
    private GenerateAudio() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("GenerateAudio <output-resources-root>");
        Path output = Path.of(args[0]).resolve("audio");
        Files.createDirectories(output);
        METRICS.clear();
        METRICS.add("asset,frames,channels,sample_rate,bits,peak,rms,sha256");
        compose(output);
        for (String id : List.of("engine-idle", "engine-drive", "turbo-loop", "tyre-slip", "machine-gun",
                "homing-launch", "power-launch", "pulse", "empty", "overheat", "metal-hit", "explosion",
                "destroyed", "low-hp", "hazard-warning", "hazard-active", "pickup-repair", "pickup-ammo",
                "pickup-turbo", "ui-nav", "ui-confirm", "victory", "defeat", "draw")) effect(output, id);
        Files.write(output.resolve("audio-metrics.csv"), METRICS, StandardCharsets.UTF_8);
        Files.writeString(output.resolve("score.txt"), """
                WRECK RIFF / DEAD AIR CIRCUIT
                Original procedural composition and sound design. Seed 0x5249464657415645.
                PCM 48000 Hz / signed 16-bit / stereo music, mono sound effects.
                128 BPM, 4/4, 96 bars, 180 seconds. Tonal centre D1/D2.
                Bars 01-08: ignition; filtered kick, metallic pulse, bass, riff enters at bar 5.
                Bars 09-32: circuit A; syncopated low D pedal, Eb/F tensions, alternating kicks.
                Bars 33-48: circuit B; Bb/C pedal and open fifths, eighth-note bass drive.
                Bars 49-56: pressure; halftime groove, muted string attacks, no silent breakdown.
                Bars 57-64: coil charge; progressive snare roll, inverted riff answers, riser.
                Bars 65-88: circuit A return; double kick variations and wider second riff.
                Bars 89-96: return feed; groove closes into ignition, final beat pickup.
                Strings: damped waveguide plucks, nonlinear amp, cabinet low-pass, independent double tracks.
                Bass: detuned harmonic oscillator, saturated low-pass. Drums/noise: analytical oscillators
                and seeded noise. Tail samples wrap to the beginning, preserving the full bar grid.
                Master: soft saturation, DC removal, peak normalized to 0.84, deterministic quantization.
                Artistic status: NEEDS_CREATIVE_REVIEW. Signal metrics do not prove musical approval.
                """, StandardCharsets.UTF_8);
        System.out.println("Generated original 180 s score and 24 effects in " + output);
    }

    private static void compose(Path output) throws Exception {
        float[][] mix = new float[2][FRAMES];
        Random random = new Random(SEED);
        int[] a = {0, 0, -99, 0, 1, 0, -99, 3, 0, 0, 5, -99, 3, 1, 0, -99};
        int[] b = {8, -99, 8, 8, 10, -99, 10, 8, 0, 0, -99, 3, 1, -99, 0, 0};
        for (int bar = 0; bar < 96; bar++) {
            boolean intro = bar < 8, bridge = bar >= 48 && bar < 56, charge = bar >= 56 && bar < 64;
            boolean alternate = bar >= 32 && bar < 48;
            boolean outro = bar >= 88;
            double intensity = intro ? .62 : bridge ? .75 : charge ? .83 : 1;
            int[] riff = alternate ? b : a;
            for (int step = 0; step < 16; step++) {
                double when = (bar * 4 + step * .25) * BEAT;
                boolean kick = step == 0 || step == 8 || (!bridge && (step == 6 || step == 11))
                        || (!intro && bar % 4 == 3 && (step == 14 || step == 15));
                if (kick) add(mix, drum("kick", .44, random), when, .91 * intensity, 0);
                if (bridge ? step == 8 : step == 4 || step == 12)
                    add(mix, drum("snare", .31, random), when, .65 * intensity, .02);
                if (charge && (step % 2 == 0 || bar >= 62))
                    add(mix, drum("snare", .15, random), when, .08 + .026 * (bar - 56), (step % 3 - 1) * .35);
                if ((step % 2 == 0 || (!intro && !bridge)) && !(outro && bar == 95 && step >= 14))
                    add(mix, drum(step == 14 && bar % 2 == 1 ? "openhat" : "hat", .19, random),
                            when, (step % 2 == 0 ? .24 : .12) * intensity, .45);
                if (step == 0 && (bar % 8 == 0 || bar == 64))
                    add(mix, drum("crash", 1.35, random), when, .29, -.42);
                int note = riff[step];
                if (intro && bar < 4) note = step % 4 == 0 ? 0 : -99;
                if (bridge && step % 4 != 0) note = -99;
                if (note != -99) {
                    double duration = (step % 4 == 0 && alternate ? .38 : .135) * (bridge ? 1.35 : 1);
                    double base = frequency(38 + note); // D2, original authored intervals
                    if (!intro || bar >= 4) {
                        add(mix, guitar(base, duration, random, .993), when, .47 * intensity, -.73);
                        // Independently excited, detuned fifth answer; second rhythmic layer is deliberate.
                        double other = frequency(38 + note + (alternate && step % 4 == 0 ? 12 : 7));
                        add(mix, guitar(other * 1.0018, duration * 1.08, random, .991),
                                when + .008, .34 * intensity, .72);
                    }
                    if (step % 2 == 0 || step == 11 || step == 15)
                        add(mix, bass(frequency(26 + note), duration * 1.35), when, .59, 0);
                }
                if (step == 10 && bar % 4 == 2)
                    add(mix, drum("clang", .62, random), when, .2, -.6);
            }
            if (bar % 16 == 15) add(mix, riser(1.5, random), (bar * 4 + 1) * BEAT, .16, .2);
        }
        // A short stereo ambience created from the composition itself, no external impulse response.
        for (int c = 0; c < 2; c++) {
            int delay = (int) (RATE * (c == 0 ? .083 : .107));
            float[] dry = mix[c].clone();
            for (int i = 0; i < FRAMES; i++) mix[c][i] += dry[(i - delay + FRAMES) % FRAMES] * .055f;
        }
        master(mix, .84);
        write(output, "dead-air-circuit", mix);
    }

    private static float[] guitar(double frequency, double seconds, Random random, double damping) {
        int delay = Math.max(8, (int) Math.round(RATE / frequency));
        double[] string = new double[delay];
        for (int i = 0; i < delay; i++) string[i] = (random.nextDouble() * 2 - 1) * .7
                + Math.sin(TAU * i / delay) * .3;
        float[] out = new float[(int) ((seconds + .08) * RATE)];
        double cabinet = 0, dc = 0, previous = 0;
        for (int i = 0; i < out.length; i++) {
            int j = i % delay;
            double sample = string[j];
            string[j] = damping * (sample + string[(j + 1) % delay]) * .5;
            double t = i / (double) RATE;
            double gate = Math.min(1, t / .002) * Math.min(1, Math.max(0, (seconds + .08 - t) / .08));
            double amp = Math.tanh(sample * 13 + Math.sin(TAU * frequency * t) * .9);
            dc = amp - previous + .995 * dc;
            previous = amp;
            cabinet += .26 * (dc - cabinet);
            out[i] = (float) (cabinet * gate * Math.exp(-t * (seconds < .2 ? 4 : 1.4)));
        }
        return out;
    }

    private static float[] bass(double frequency, double seconds) {
        float[] out = new float[(int) ((seconds + .06) * RATE)];
        double low = 0;
        for (int i = 0; i < out.length; i++) {
            double t = i / (double) RATE;
            double oscillator = Math.sin(TAU * frequency * t) + .27 * Math.sin(TAU * frequency * 2 * t)
                    + .13 * Math.sin(TAU * frequency * 3.003 * t);
            low += .045 * (Math.tanh(oscillator * 1.5) - low);
            out[i] = (float) (low * Math.min(1, t / .004) * Math.min(1, (out.length - i) / (RATE * .045)));
        }
        return out;
    }

    private static float[] drum(String kind, double seconds, Random random) {
        float[] out = new float[(int) (seconds * RATE)];
        double low = 0, phase = 0;
        for (int i = 0; i < out.length; i++) {
            double t = i / (double) RATE, n = random.nextDouble() * 2 - 1;
            low += .12 * (n - low);
            double sound = switch (kind) {
                case "kick" -> {
                    phase += TAU * (46 + 134 * Math.exp(-t * 46)) / RATE;
                    yield Math.sin(phase) * Math.exp(-t * 10) + (n - low) * Math.exp(-t * 240) * .3;
                }
                case "snare" -> (n - low) * Math.exp(-t * 23) * .76
                        + (Math.sin(TAU * 173 * t) + .5 * Math.sin(TAU * 311 * t)) * Math.exp(-t * 33) * .35;
                case "clang" -> (Math.sin(TAU * 403 * t) + .56 * Math.sin(TAU * 617 * t)
                        + .37 * Math.sin(TAU * 1097 * t)) * Math.exp(-t * 7) * .65;
                default -> {
                    double metal = Math.sin(TAU * 6091 * t) * Math.sin(TAU * 4337 * t)
                            + .4 * Math.sin(TAU * 7919 * t);
                    double decay = kind.equals("crash") ? 4 : kind.equals("openhat") ? 16 : 65;
                    yield ((n - low) * .72 + metal * .22) * Math.exp(-t * decay);
                }
            };
            out[i] = (float) (sound * Math.min(1, i / 10.0) * Math.min(1, (out.length - i) / 160.0));
        }
        return out;
    }

    private static float[] riser(double seconds, Random random) {
        float[] out = new float[(int) (seconds * RATE)];
        double low = 0;
        for (int i = 0; i < out.length; i++) {
            double t = i / (double) RATE, amount = t / seconds;
            low += (.008 + .25 * amount * amount) * (random.nextDouble() * 2 - 1 - low);
            out[i] = (float) (low * amount * Math.min(1, (out.length - i) / 450.0));
        }
        return out;
    }

    private static void effect(Path output, String id) throws Exception {
        boolean loop = Set.of("engine-idle", "engine-drive", "turbo-loop", "tyre-slip", "hazard-active").contains(id);
        double seconds = switch (id) {
            case "engine-idle", "engine-drive", "turbo-loop", "tyre-slip", "hazard-active" -> 2;
            case "destroyed", "victory", "defeat", "draw" -> 2.4;
            case "explosion", "pulse", "hazard-warning" -> 1.1;
            case "homing-launch", "power-launch", "overheat", "low-hp" -> .65;
            case "machine-gun", "empty", "ui-nav" -> .11;
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
                case "overheat" -> low * 2.5 * Math.exp(-t * 4)
                        + Math.sin(TAU * (660 * t - 280 * t * t)) * .22 * decay;
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

    private static void add(float[][] mix, float[] sound, double seconds, double gain, double pan) {
        int start = (int) Math.round(seconds * RATE);
        double left = Math.sqrt((1 - pan) / 2) * gain, right = Math.sqrt((1 + pan) / 2) * gain;
        for (int i = 0; i < sound.length; i++) {
            int frame = (start + i) % mix[0].length;
            mix[0][frame] += sound[i] * left;
            mix[1][frame] += sound[i] * right;
        }
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

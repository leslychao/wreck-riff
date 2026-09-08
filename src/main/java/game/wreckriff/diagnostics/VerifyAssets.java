package game.wreckriff.diagnostics;

import com.google.gson.*;
import game.wreckriff.ai.AiRules;
import game.wreckriff.arena.ArenaDefinition;
import game.wreckriff.audio.*;
import game.wreckriff.combat.CombatRules;
import game.wreckriff.config.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.*;

/** Build-time integrity/provenance gate. It does not create a window or claim artistic approval. */
public final class VerifyAssets {
    public record Asset(String path, String category, long bytes, String sha256, String source,
            String status, Integer channels, Long frames, Double peak, Double rms) {}
    public record Dependency(String coordinate, String jar, String sha256, List<String> licenseFiles, String status) {}
    public record Report(int schemaVersion, String status, String artisticStatus, String distributionStatus,
            List<Asset> assets, List<Dependency> dependencies, List<String> windowsX64Natives,
            List<String> errors, List<String> reviewItems) {}
    public record WaveMetrics(long frames, int channels, int rate, int bits, double peak, double rms, long bytes, String sha256) {}
    private record LicenseSource(String file, String source, List<String> components, String sha256) {}
    private record LicenseIndex(int schemaVersion, String description, List<LicenseSource> sources) {}
    private static final Map<String, Class<?>> CONFIGS = Map.of(
            "ai", AiRules.class, "arena", ArenaDefinition.class, "audio", AudioConfig.class,
            "camera", CameraRules.class, "combat", CombatRules.class, "match", MatchRules.class, "vehicle", VehicleRules.class);
    private static final Set<String> REQUIRED_NATIVE_ENTRIES = Set.of(
            "windows/x64/org/lwjgl/lwjgl.dll", "windows/x64/org/lwjgl/glfw/glfw.dll",
            "windows/x64/org/lwjgl/openal/OpenAL.dll", "native/windows/x86_64/bulletjme.dll");
    private static final long MAX_ASSET_BYTES = 128L * 1024 * 1024;
    private VerifyAssets() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("verifyAssets takes no arguments");
        Report report = verify(Path.of("build", "reports", "assets"));
        System.out.printf("Assets: %s; %d files; %d dependencies; %d Windows x64 native entries.%n",
                report.status, report.assets.size(), report.dependencies.size(), report.windowsX64Natives.size());
        for (String review : report.reviewItems) System.out.println("REVIEW: " + review);
        if (!report.errors.isEmpty()) throw new IllegalStateException("Asset verification failed:\n" + String.join("\n", report.errors));
    }

    public static Report verify(Path output) throws IOException {
        Files.createDirectories(output);
        List<Asset> assets = new ArrayList<>();
        List<Dependency> dependencies = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> reviews = new ArrayList<>(List.of(
                "Music, font, vehicles, arena, and effects: NEEDS_CREATIVE_REVIEW; checksum/signal tests do not prove artistic approval.",
                "Before external distribution, verify corresponding-source delivery and replacement rights for the LGPL OpenAL Soft native library.",
                "The jpackage Java runtime retains its own legal notices; packageWindows verifies their presence. No legal approval is inferred."));
        List<String> nativeEntries = new ArrayList<>();
        for (String name : new TreeSet<>(CONFIGS.keySet())) attempt(errors, "config/" + name + ".json", () -> {
            String path = "config/" + name + ".json";
            byte[] bytes = resource(path);
            JsonElement json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            Configs.validate(json, CONFIGS.get(name), name);
            Configs.gson().fromJson(json, CONFIGS.get(name));
            assets.add(asset(path, "config", bytes, "src/main/resources/" + path, "VALIDATED"));
        });
        attempt(errors, "audio", () -> verifyAudio(assets));
        attempt(errors, "font", () -> verifyFont(assets));
        attempt(errors, "engine materials", () -> {
            for (String path : List.of("Common/MatDefs/Light/Lighting.j3md", "Common/MatDefs/Misc/Unshaded.j3md")) {
                assets.add(asset(path, "engine-material", resource(path), "org.jmonkeyengine:jme3-core:3.8.1-stable; jme-BSD3.txt", "DEPENDENCY_RESOURCE_PRESENT"));
            }
        });
        attempt(errors, "procedural sources", () -> {
            for (String path : List.of("src/tools/java/game/wreckriff/tools/GenerateAudio.java", "src/tools/java/game/wreckriff/tools/GenerateFont.java",
                    "src/main/java/game/wreckriff/presentation/VehicleVisual.java", "src/main/java/game/wreckriff/arena/ArenaFactory.java")) {
                byte[] bytes = Files.readAllBytes(Path.of(path));
                if (bytes.length == 0) throw new IOException("Empty generator " + path);
                assets.add(asset(path, "procedural-source", bytes, "Original project source recipe", "SOURCE_PRESENT"));
            }
        });
        attempt(errors, "dependency license evidence", () -> verifyDependencies(output, assets, dependencies, nativeEntries, errors));
        for (String required : new TreeSet<>(REQUIRED_NATIVE_ENTRIES)) {
            if (!nativeEntries.contains(required)) errors.add("Missing Windows x64 native entry: " + required);
        }
        assets.sort(Comparator.comparing(Asset::path));
        dependencies.sort(Comparator.comparing(Dependency::jar));
        Collections.sort(nativeEntries);
        Report report = new Report(1, errors.isEmpty() ? "TECHNICAL_PASS" : "FAIL", "NEEDS_CREATIVE_REVIEW",
                "REVIEW_REQUIRED", List.copyOf(assets), List.copyOf(dependencies), List.copyOf(nativeEntries), List.copyOf(errors), List.copyOf(reviews));
        Files.writeString(output.resolve("verification.json"), Configs.gson().toJson(report), StandardCharsets.UTF_8);
        Files.writeString(output.resolve("manifest.json"), Configs.gson().toJson(assets), StandardCharsets.UTF_8);
        return report;
    }

    private static void verifyAudio(List<Asset> assets) throws Exception {
        AudioConfig config = Configs.gson().fromJson(new String(resource("config/audio.json"), StandardCharsets.UTF_8), AudioConfig.class);
        String metricsPath = "audio/audio-metrics.csv";
        byte[] metricsBytes = resource(metricsPath);
        Map<String, String[]> expected = new TreeMap<>();
        List<String> lines = new String(metricsBytes, StandardCharsets.UTF_8).lines().toList();
        if (lines.isEmpty() || !lines.getFirst().equals("asset,frames,channels,sample_rate,bits,peak,rms,sha256")) throw new IOException("Malformed audio metric header");
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) continue;
            String[] columns = line.split(",", -1);
            if (columns.length != 8 || !columns[0].matches("[a-z][a-z0-9-]*\\.wav") || expected.put(columns[0], columns) != null) {
                throw new IOException("Malformed or duplicate audio metric row");
            }
        }
        Set<String> required = new TreeSet<>();
        required.add(config.musicAsset());
        for (String effect : config.effects()) required.add("audio/" + effect + ".wav");
        if (expected.size() != required.size()) throw new IOException("Audio manifest must cover exactly the configured files");
        for (String path : required) {
            String[] row = expected.get(path.substring("audio/".length()));
            if (row == null) throw new IOException("Missing metric row: " + path);
            URL url = uniqueResource(path);
            WaveMetrics metrics = inspectWave(url);
            int channels = path.equals(config.musicAsset()) ? 2 : 1;
            if (metrics.channels != channels || metrics.rate != 48_000 || metrics.bits != 16) throw new IOException("Unexpected WAV format: " + path);
            if (metrics.frames != Long.parseLong(row[1]) || metrics.channels != Integer.parseInt(row[2])
                    || metrics.rate != Integer.parseInt(row[3]) || metrics.bits != Integer.parseInt(row[4])
                    || !metrics.sha256.equals(row[7])) throw new IOException("Audio metrics/checksum mismatch: " + path);
            if (Math.abs(metrics.peak - Double.parseDouble(row[5])) > 0.00004 || Math.abs(metrics.rms - Double.parseDouble(row[6])) > 0.00004) {
                throw new IOException("Audio signal metrics mismatch: " + path);
            }
            if (path.equals(config.musicAsset()) && (metrics.frames / 48_000.0 < 150 || metrics.frames / 48_000.0 > 210)) {
                throw new IOException("Music length must remain approximately 180 seconds");
            }
            assets.add(new Asset(path, channels == 2 ? "music" : "sound-effect", metrics.bytes, metrics.sha256,
                    "GenerateAudio.java; fixed score/seed; no external samples", "ORIGINAL_GENERATED", metrics.channels, metrics.frames, metrics.peak, metrics.rms));
        }
        assets.add(asset(metricsPath, "audio-metrics", metricsBytes, "GenerateAudio.java", "VERIFIED_AGAINST_PCM"));
        assets.add(asset("audio/score.txt", "score", resource("audio/score.txt"), "GenerateAudio.java", "SOURCE_PRESENT"));
    }

    public static WaveMetrics inspectWave(URL url) throws Exception {
        PcmWave.Header header;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long bytes = 0;
        try (InputStream in = new BufferedInputStream(url.openStream())) {
            byte[] buffer = new byte[65_536];
            int count;
            while ((count = in.read(buffer)) >= 0) {
                bytes += count;
                if (bytes > MAX_ASSET_BYTES) throw new IOException("Audio file exceeds bounded asset budget");
                digest.update(buffer, 0, count);
            }
        }
        long sampleCount;
        double peak = 0, squares = 0;
        try (BufferedInputStream input = new BufferedInputStream(url.openStream())) {
            input.mark(12);
            byte[] signature = input.readNBytes(12);
            if (signature.length != 12 || Integer.toUnsignedLong(ByteBuffer.wrap(signature).order(ByteOrder.LITTLE_ENDIAN).getInt(4)) + 8 != bytes) {
                throw new IOException("RIFF declared file size differs from the actual file");
            }
            input.reset();
            header = PcmWave.header(input);
            if (header.dataOffset() + header.dataBytes() != bytes) throw new IOException("WAV size/data mismatch or trailing unregistered chunks");
            sampleCount = header.dataBytes() / 2;
            byte[] block = new byte[65_536];
            long remaining = header.dataBytes();
            while (remaining > 0) {
                int count = (int) Math.min(block.length, remaining);
                if (input.readNBytes(block, 0, count) != count) throw new EOFException("Truncated PCM data");
                for (int i = 0; i < count; i += 2) {
                    short sample = (short) ((block[i] & 255) | (block[i + 1] << 8));
                    if (sample == Short.MIN_VALUE || sample == Short.MAX_VALUE) throw new IOException("Digital clipping in WAV");
                    double value = sample / 32767.0;
                    peak = Math.max(peak, Math.abs(value));
                    squares += value * value;
                }
                remaining -= count;
            }
        }
        if (peak == 0) throw new IOException("Silent WAV asset");
        return new WaveMetrics(header.dataBytes() / header.frameBytes(), header.channels(), header.rate(), header.bits(),
                peak, Math.sqrt(squares / sampleCount), bytes, HexFormat.of().formatHex(digest.digest()));
    }

    private static void verifyFont(List<Asset> assets) throws Exception {
        byte[] font = resource("fonts/wreck.fnt"), atlas = resource("fonts/wreck.png"), evidence = resource("fonts/font-provenance.json");
        JsonObject provenance = JsonParser.parseString(new String(evidence, StandardCharsets.UTF_8)).getAsJsonObject();
        if (provenance.get("externalFontInput").getAsBoolean() || provenance.get("glyphs").getAsInt() != 95
                || !hash(font).equals(provenance.get("fntSha256").getAsString()) || !hash(atlas).equals(provenance.get("pngSha256").getAsString())) {
            throw new IOException("Font provenance/checksum mismatch");
        }
        verifyFontBytes(font, atlas);
        assets.add(asset("fonts/wreck.fnt", "bitmap-font", font, "GenerateFont.java; original 5x7 recipes", "ORIGINAL_GENERATED"));
        assets.add(asset("fonts/wreck.png", "font-atlas", atlas, "GenerateFont.java; no system font input", "ORIGINAL_GENERATED"));
        assets.add(asset("fonts/font-provenance.json", "font-provenance", evidence, "GenerateFont.java", "VERIFIED"));
    }

    public static void verifyFontBytes(byte[] font, byte[] atlas) throws IOException {
        String text = new String(font, StandardCharsets.UTF_8);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(atlas));
        if (image == null || image.getWidth() != 512 || image.getHeight() != 256 || !text.contains("file=\"wreck.png\"")) {
            throw new IOException("Invalid font atlas reference or dimensions");
        }
        Set<Integer> characters = new HashSet<>();
        Pattern pattern = Pattern.compile("^char id=(\\d+) x=(\\d+) y=(\\d+) width=(\\d+) height=(\\d+).*$");
        for (String line : text.lines().toList()) {
            var matcher = pattern.matcher(line);
            if (!matcher.matches()) continue;
            int code = Integer.parseInt(matcher.group(1)), x = Integer.parseInt(matcher.group(2)), y = Integer.parseInt(matcher.group(3));
            int width = Integer.parseInt(matcher.group(4)), height = Integer.parseInt(matcher.group(5));
            if (!characters.add(code) || x < 0 || y < 0 || width <= 0 || height <= 0 || x + width > image.getWidth() || y + height > image.getHeight()) {
                throw new IOException("Duplicate or out-of-atlas font glyph");
            }
            boolean visible = false;
            for (int row = y; row < y + height; row++) for (int column = x; column < x + width; column++) {
                if ((image.getRGB(column, row) >>> 24) != 0) visible = true;
            }
            if (code != 32 && !visible) throw new IOException("Empty visible ASCII glyph " + code);
        }
        for (int code = 32; code <= 126; code++) if (!characters.contains(code)) throw new IOException("Missing ASCII glyph " + code);
        if (characters.size() != 95) throw new IOException("Unexpected font charset");
    }

    private static void verifyDependencies(Path output, List<Asset> assets, List<Dependency> dependencies,
            List<String> nativeEntries, List<String> errors) throws Exception {
        byte[] indexBytes = resource("licenses/license-index.json");
        JsonElement tree = JsonParser.parseString(new String(indexBytes, StandardCharsets.UTF_8));
        Configs.validate(tree, LicenseIndex.class, "license-index");
        LicenseIndex index = Configs.gson().fromJson(tree, LicenseIndex.class);
        if (index.schemaVersion != 1 || index.sources.isEmpty()) throw new IOException("Invalid license index");
        Map<String, List<String>> licenses = new TreeMap<>();
        Path licenseOutput = output.resolve("third-party");
        Files.createDirectories(licenseOutput);
        for (LicenseSource source : index.sources) {
            if (!source.file.matches("[A-Za-z0-9._-]+") || !source.source.startsWith("https://")) throw new IOException("Invalid license source");
            String resource = "licenses/" + source.file;
            byte[] bytes = resource(resource);
            if (!hash(bytes).equals(source.sha256)) throw new IOException("License evidence hash mismatch: " + source.file);
            Files.write(licenseOutput.resolve(source.file), bytes);
            assets.add(asset(resource, "third-party-license", bytes, source.source, "TEXT_CAPTURED"));
            for (String component : source.components) licenses.computeIfAbsent(component, ignored -> new ArrayList<>()).add(source.file);
        }
        assets.add(asset("licenses/license-index.json", "license-evidence-index", indexBytes, "tools/refresh-license-evidence.ps1", "VERIFIED"));
        licenses.putIfAbsent("com.simsilica:sim-math:1.6.0", new ArrayList<>());
        licenses.putIfAbsent("org.lwjglx:lwjgl3-awt:0.2.3", new ArrayList<>());
        for (String item : System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator))) {
            Path path = Path.of(item);
            if (!path.getFileName().toString().endsWith(".jar")) continue;
            String filename = path.getFileName().toString();
            if (filename.startsWith("wreck-riff-")) continue;
            String coordinate = licenses.keySet().stream().filter(component -> {
                String[] fields = component.split(":");
                return filename.equals(fields[1] + "-" + fields[2] + ".jar") || filename.startsWith(fields[1] + "-" + fields[2] + "-");
            }).findFirst().orElse(null);
            List<String> texts = coordinate == null ? new ArrayList<>() : new ArrayList<>(licenses.get(coordinate));
            try (ZipFile jar = new ZipFile(path.toFile())) {
                for (ZipEntry entry : Collections.list(jar.entries())) {
                    String name = entry.getName();
                    if (name.toLowerCase(Locale.ROOT).matches("(^|.*/)(license|licence|notice|copying|copyright)([./_-].*|$)")) {
                        if (entry.isDirectory() || entry.getSize() > 1_048_576) continue;
                        String extracted = filename + "--" + name.replaceAll("[^A-Za-z0-9._-]", "_");
                        try (InputStream input = jar.getInputStream(entry)) {
                            byte[] bytes = input.readNBytes(1_048_577);
                            if (bytes.length > 1_048_576 || bytes.length == 0) throw new IOException("Invalid embedded license: " + filename);
                            Files.write(licenseOutput.resolve(extracted), bytes);
                        }
                        texts.add(extracted);
                    }
                    if (REQUIRED_NATIVE_ENTRIES.contains(name)) {
                        try (InputStream input = jar.getInputStream(entry)) {
                            if (entry.getSize() < 1024 || input.read() != 'M' || input.read() != 'Z') throw new IOException("Invalid Windows PE native: " + name);
                        }
                        nativeEntries.add(name);
                    }
                }
            }
            String status = coordinate == null || texts.isEmpty() ? "MISSING_EVIDENCE" : "LICENSE_TEXTS_PRESENT";
            if (status.equals("MISSING_EVIDENCE")) errors.add("Dependency license evidence missing: " + filename);
            dependencies.add(new Dependency(coordinate == null ? "UNKNOWN" : coordinate, filename, hash(Files.readAllBytes(path)), List.copyOf(texts), status));
        }
        Files.writeString(licenseOutput.resolve("dependency-evidence.json"), Configs.gson().toJson(dependencies), StandardCharsets.UTF_8);
    }

    private static Asset asset(String path, String kind, byte[] bytes, String source, String status) throws NoSuchAlgorithmException {
        return new Asset(path, kind, bytes.length, hash(bytes), source, status, null, null, null, null);
    }
    private static byte[] resource(String path) throws IOException {
        try (InputStream input = uniqueResource(path).openStream()) {
            byte[] bytes = input.readNBytes((int) MAX_ASSET_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_ASSET_BYTES) throw new IOException("Empty/oversized asset: " + path);
            return bytes;
        }
    }
    private static URL uniqueResource(String path) throws IOException {
        List<URL> locations = Collections.list(VerifyAssets.class.getClassLoader().getResources(path));
        if (locations.size() != 1) throw new IOException("Expected exactly one bundled resource for " + path + ", found " + locations.size());
        return locations.getFirst();
    }
    private static String hash(byte[] bytes) throws NoSuchAlgorithmException { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    @FunctionalInterface private interface Check { void run() throws Exception; }
    private static void attempt(List<String> errors, String label, Check check) {
        try { check.run(); }
        catch (Exception e) { errors.add(label + ": " + e.getClass().getSimpleName() + ": " + e.getMessage()); }
    }
}

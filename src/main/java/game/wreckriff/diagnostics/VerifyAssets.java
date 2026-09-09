package game.wreckriff.diagnostics;

import com.google.gson.*;
import game.wreckriff.ai.AiRules;
import game.wreckriff.arena.ArenaDefinition;
import game.wreckriff.arena.ArenaRegistry;
import game.wreckriff.audio.*;
import game.wreckriff.combat.CombatRules;
import game.wreckriff.config.*;
import game.wreckriff.input.GamepadProfile;

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
    record RegisterEntry(String assetId, String packagedPath, String sourceOrigin, String author,
            String licensePermission, String licenseTextPath, String sha256, String hashScope,
            String verificationStatus, String attributionRequired, String artifactForm) {}
    private record LicenseSource(String file, String source, List<String> components, String sha256) {}
    private record LicenseIndex(int schemaVersion, String description, List<LicenseSource> sources) {}
    private record TextureSource(String path, String sourcePath, String sourceUrl, String author, String license,
            String licensePath, String acquired, String sourceSha256, String sha256, String transformation) {}
    private record TextureIndex(int schemaVersion, List<TextureSource> assets) {}
    private static final Set<String> MATERIALS = Set.of("asphalt_02", "cracked_concrete", "metal_plate_02", "blue_metal_plate", "rusty_metal_03");
    private static final Map<String,String> ASSET_LICENSE_HASHES = Map.of(
            "CC-BY-4.0.txt", "9ba9550ad48438d0836ddab3da480b3b69ffa0aac7b7878b5a0039e7ab429411",
            "CC0-1.0.txt", "a2010f343487d3f7618affe54f789f5487602331c0a8d03f49e9a7c547cf0499",
            "Roboto-OFL.txt", "061402327a96aadb0bfb694a960ed289ecd38d383e396243831ab81feb109c41");
    private static final Map<String, Class<?>> CONFIGS = Map.of(
            "ai", AiRules.class, "arena", ArenaDefinition.class, "arenas", ArenaRegistry.Catalogue.class, "audio", AudioConfig.class,
            "camera", CameraRules.class, "combat", CombatRules.class, "gamepad", GamepadProfile.class,
            "match", MatchRules.class, "vehicle", VehicleRules.class);
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
        Map<String,Class<?>> configs=new TreeMap<>(CONFIGS);
        attempt(errors,"arena catalogue",()-> {
            var registry=ArenaRegistry.load();
            for(var entry:registry.entries())configs.put(entry.resourceKey(),ArenaDefinition.class);
            for(var entry:registry.entries())if(entry.campaign()) {
                var arena=registry.definition(entry.id());
                for(String path:List.of(arena.metadata().music(),arena.metadata().bossMusic()))
                    attempt(errors,entry.id()+" music "+path,()-> {
                        byte[] bytes=resource(path);
                        if(bytes.length<1024)throw new IOException("Empty/truncated arena music: "+path);
                        assets.add(asset(path,"campaign-music",bytes,"Original campaign composition; authoring source required","RESOURCE_PRESENT"));
                    });
            }
        });
        for (String name : configs.keySet()) attempt(errors, "config/" + name + ".json", () -> {
            String path = "config/" + name + ".json";
            byte[] bytes = resource(path);
            JsonElement json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            Configs.validate(json, configs.get(name), name);
            Configs.gson().fromJson(json, configs.get(name));
            assets.add(asset(path, "config", bytes, "src/main/resources/" + path, "VALIDATED"));
        });
        attempt(errors, "audio", () -> verifyAudio(assets));
        attempt(errors, "font", () -> verifyFont(assets));
        attempt(errors, "licensed textures", () -> verifyTextures(assets));
        attempt(errors, "asset license evidence", () -> {
            for (var license : ASSET_LICENSE_HASHES.entrySet()) {
                String path = "licenses/assets/" + license.getKey();
                byte[] bytes = resource(path);
                if (!hash(bytes).equals(license.getValue())) throw new IOException("Altered/missing licensed-asset notice: " + path);
                String source = license.getKey().equals("Roboto-OFL.txt")
                        ? "https://raw.githubusercontent.com/googlefonts/roboto-3-classic/v3.016/OFL.txt"
                        : license.getKey().equals("CC0-1.0.txt") ? "https://creativecommons.org/publicdomain/zero/1.0/legalcode.txt"
                        : "https://creativecommons.org/licenses/by/4.0/legalcode.txt";
                assets.add(asset(path, "third-party-license", bytes, source, "TEXT_CAPTURED"));
            }
        });
        attempt(errors, "engine materials", () -> {
            for (String path : List.of("Common/MatDefs/Light/Lighting.j3md", "Common/MatDefs/Misc/Unshaded.j3md")) {
                assets.add(asset(path, "engine-material", resource(path), "org.jmonkeyengine:jme3-core:3.8.1-stable; jme-BSD3.txt", "DEPENDENCY_RESOURCE_PRESENT"));
            }
        });
        attempt(errors, "project materials", () -> {
            for (String path : List.of("materials/CombatParticles.j3md", "materials/CombatParticles.vert", "materials/CombatParticles.frag")) {
                byte[] bytes = resource(path);
                if (bytes.length == 0) throw new IOException("Empty project material " + path);
                assets.add(asset(path, "project-material", bytes, "src/main/resources/" + path + "; original project source", "SOURCE_PRESENT"));
            }
        });
        attempt(errors, "procedural sources", () -> {
            for (String path : List.of("src/tools/java/game/wreckriff/tools/GenerateAudio.java", "src/tools/java/game/wreckriff/tools/GenerateFont.java", "src/tools/prepare_recorded_sfx.py",
                    "src/main/java/game/wreckriff/presentation/VehicleVisual.java", "src/main/java/game/wreckriff/arena/ArenaFactory.java",
                    "src/main/java/game/wreckriff/presentation/CombatVisuals.java", "src/main/java/game/wreckriff/presentation/ArenaPresentation.java")) {
                byte[] bytes = Files.readAllBytes(Path.of(path));
                if (bytes.length == 0) throw new IOException("Empty generator " + path);
                assets.add(asset(path, "procedural-source", bytes, "Original project source recipe", "SOURCE_PRESENT"));
            }
            for (String path : List.of("docs/asset-history/GenerateAudio-v0.1.java.txt", "docs/asset-history/GenerateAudio-v0.2.java.txt", "docs/asset-history/GenerateFont-v0.1.java.txt",
                    "docs/asset-history/audio-0.3/prepare_recorded_sfx.py.txt", "docs/asset-history/audio-0.3/sfx-provenance.json", "docs/asset-history/audio-0.3/audio-metrics.csv")) {
                assets.add(asset(path, "historical-source", Files.readAllBytes(Path.of(path)),
                        "Superseded original recipe retained for provenance only; excluded from compilation and runtime", "SOURCE_PRESENT"));
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
        Properties build = new Properties();
        try (InputStream input = uniqueResource("build-info.properties").openStream()) { build.load(input); }
        writeAssetRegister(output.resolve("asset-register.csv"), registerEntries(assets, build.getProperty("version")));
        return report;
    }

    /** A source checksum never purports to identify the in-memory mesh produced from that source. */
    static List<RegisterEntry> registerEntries(List<Asset> assets, String version) {
        if (version == null || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) throw new IllegalArgumentException("Missing/invalid build version for asset registry");
        List<RegisterEntry> entries = new ArrayList<>();
        for (Asset asset : assets) {
            boolean source = Set.of("procedural-source", "historical-source").contains(asset.category);
            boolean runtimeGeometry = source && asset.path.startsWith("src/main/");
            String id = runtimeGeometry ? "runtime/" + Path.of(asset.path).getFileName().toString().replace(".java", "") : asset.path;
            String packaged = source ? "" : "app/wreck-riff-" + version + ".jar!/" + asset.path;
            String origin = source ? asset.path : asset.source;
            if (Set.of("music", "licensed-music", "licensed-result", "result-provenance", "sound-effect", "audio-metrics", "score", "music-provenance").contains(asset.category)) {
                origin = "src/tools/java/game/wreckriff/tools/GenerateAudio.java; " + origin;
            } else if (Set.of("bitmap-font", "font-atlas", "font-provenance").contains(asset.category)) {
                origin = "src/tools/java/game/wreckriff/tools/GenerateFont.java; " + origin;
            }
            String author = "Wreck Riff project contributors/tooling; ownership review pending";
            String permission = "Original project content; no distribution license assigned; owner review required";
            String license = "", attribution = "OWNER_REVIEW_REQUIRED";
            if (Set.of("licensed-result","result-provenance").contains(asset.category)) {
                author = "Kevin MacLeod (incompetech.com); CC0 metal recordings identified in audio/sfx-sources.json";
                permission = "CC-BY-4.0 Metalmania excerpt and CC0 impact; transformations in audio/result-provenance.json";
                license = "licenses/assets/CC-BY-4.0.txt; licenses/assets/CC0-1.0.txt";
                attribution = "CREDIT_TITLE_AUTHOR_SOURCE_LICENSE_AND_MODIFICATIONS";
            } else if (asset.category.equals("licensed-music") || asset.category.equals("music-provenance")) {
                author = "Kevin MacLeod (incompetech.com)";
                permission = "CC-BY-4.0; source track Metalmania; local loop edit and normalization identified in audio/music-provenance.json";
                license = "licenses/assets/CC-BY-4.0.txt";
                attribution = "CREDIT_TITLE_AUTHOR_SOURCE_LICENSE_AND_MODIFICATIONS";
            } else if (Set.of("recorded-sound-effect", "sfx-provenance").contains(asset.category)) {
                author = "Ben Jaszczak, Brian Nelson, Kevin Heras, Matthew Nanney; rubberduck; processing by Wreck Riff tooling";
                permission = "CC0-1.0; recording sources and transformations in audio/sfx-sources.json and audio/sfx-provenance.json";
                license = "licenses/assets/CC0-1.0.txt";
                attribution = "NOT_REQUIRED_BY_CC0; SOURCE_RETAINED";
            } else if (Set.of("bitmap-font", "font-atlas", "font-provenance").contains(asset.category)) {
                author = "The Roboto Project Authors; atlas rasterization by Wreck Riff tooling";
                permission = "OFL-1.1; Roboto Condensed v3.016 derived bitmap font";
                license = "licenses/assets/Roboto-OFL.txt";
                attribution = "RETAIN_COPYRIGHT_AND_OFL_NOTICE";
            } else if (asset.category.equals("licensed-texture")) {
                author = asset.source.contains("cracked_concrete") ? "Dimitrios Savva / Poly Haven"
                        : asset.source.contains("rusty_metal_03") ? "Amal Kumar / Poly Haven" : "Rob Tuytel / Poly Haven";
                permission = "CC0-1.0; source and per-file transformations retained in licenses/asset-provenance.json";
                license = "licenses/assets/CC0-1.0.txt";
                attribution = "NOT_REQUIRED_BY_CC0; SOURCE_RETAINED";
            } else if (asset.category.equals("engine-material")) {
                packaged = "app/jme3-core-3.8.1-stable.jar!/" + asset.path;
                author = "jMonkeyEngine contributors; see retained upstream notice";
                permission = "BSD-3-Clause; subject to the retained upstream notice";
                license = "licenses/jme-BSD3.txt";
                attribution = "RETAIN_COPYRIGHT_AND_LICENSE_NOTICE";
            } else if (asset.category.equals("third-party-license")) {
                author = "Upstream rights holders identified in this notice";
                permission = "Retained upstream license evidence; no new permission granted";
                license = asset.path;
                attribution = "SEE_UPSTREAM_NOTICE";
            } else if (asset.category.equals("license-evidence-index")) {
                permission = "Evidence index; component permissions remain in their original notices";
                license = "licenses/THIRD_PARTY_NOTICES.md";
                attribution = "SEE_COMPONENT_NOTICES";
            }
            String form = asset.category.equals("historical-source") ? "HISTORICAL_RECIPE_NOT_COMPILED_OR_PACKAGED"
                    : runtimeGeometry ? "RUNTIME_GEOMETRY_NOT_PACKAGED; generated by " + Path.of(asset.path).getFileName()
                    : source ? "BUILD_GENERATOR_NOT_PACKAGED" : "PACKAGED_RESOURCE";
            entries.add(new RegisterEntry(id, packaged, origin, author, permission, license, asset.sha256,
                    source ? "SOURCE_GENERATOR_BYTES; NOT_RUNTIME_MESH_OR_COMPILED_CLASS_BYTES" : "PACKAGED_RESOURCE_BYTES",
                    asset.status + "; DISTRIBUTION_REVIEW_REQUIRED", attribution, form));
        }
        return List.copyOf(entries);
    }

    private static void writeAssetRegister(Path output, List<RegisterEntry> entries) throws IOException {
        StringBuilder csv = new StringBuilder("asset_id,packaged_path,source_origin,author,license_permission,license_text_path,sha256,hash_scope,verification_status,attribution_required,artifact_form\n");
        for (RegisterEntry entry : entries) {
            List<String> fields = List.of(entry.assetId, entry.packagedPath, entry.sourceOrigin, entry.author,
                    entry.licensePermission, entry.licenseTextPath, entry.sha256, entry.hashScope,
                    entry.verificationStatus, entry.attributionRequired, entry.artifactForm);
            csv.append(String.join(",", fields.stream().map(value -> "\"" + value.replace("\"", "\"\"") + "\"").toList())).append('\n');
        }
        Files.writeString(output, csv, StandardCharsets.UTF_8);
    }

    private static void verifyAudio(List<Asset> assets) throws Exception {
        AudioConfig config = Configs.gson().fromJson(new String(resource("config/audio.json"), StandardCharsets.UTF_8), AudioConfig.class);
        if (!config.musicAsset().equals("audio/metalmania.wav")) throw new IOException("Unexpected music cue");
        for(String retired:List.of("dead-air-circuit","overheat","pulse","stun","freeze","shield",
                "machine-gun","metal-hit","explosion","destroyed","homing-launch","power-launch","mine-detonate","napalm-launch")) {
            if(config.effects().contains(retired) || VerifyAssets.class.getClassLoader().getResource("audio/"+retired+".wav")!=null)
                throw new IOException("Superseded runtime audio is still packaged: "+retired);
        }
        Map<String,String> recorded = verifyRecordedEffects(config,assets);
        byte[] provenanceBytes = resource("audio/music-provenance.json"), sourceBytes = resource("audio/music-source.json");
        JsonObject provenance = JsonParser.parseString(new String(provenanceBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject source = JsonParser.parseString(new String(sourceBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        if (!provenance.get("license").getAsString().equals("CC-BY-4.0")
                || !source.get("license").getAsString().equals("CC-BY-4.0")
                || !source.get("isrc").getAsString().equals("USUAN1700023")
                || !source.get("author").getAsString().equals("Kevin MacLeod")
                || !source.get("sourceUrl").getAsString().equals("https://incompetech.com/music/royalty-free/mp3-royaltyfree/Metalmania.mp3")
                || !hash(sourceBytes).equals(provenance.get("sourceEvidenceSha256").getAsString())
                || !hash(Files.readAllBytes(Path.of("src/tools/assets/audio/Metalmania-original.mp3"))).equals(source.get("originalSha256").getAsString())
                || !hash(Files.readAllBytes(Path.of("src/tools/assets/audio/Metalmania-source.wav"))).equals(provenance.get("sourceSha256").getAsString()))
            throw new IOException("Music source/license provenance mismatch");
        Map<String,String> results=verifyResults(config,assets,provenance);
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
            if (path.equals(config.musicAsset()) && (metrics.frames != 8_601_600
                    || !metrics.sha256.equals(provenance.get("sha256").getAsString()))) {
                throw new IOException("Music must match registered 112-bar loop and hash");
            }
            assets.add(new Asset(path, channels == 2 ? "licensed-music" : results.containsKey(path)?"licensed-result":recorded.containsKey(path)?"recorded-sound-effect":"sound-effect", metrics.bytes, metrics.sha256,
                    channels == 2 ? source.get("sourceUrl").getAsString() + "; Kevin MacLeod; CC-BY-4.0; loop edit/DC removal/normalization"
                            : results.getOrDefault(path,recorded.getOrDefault(path,"GenerateAudio.java; fixed seed; original ancillary sound-effect synthesis; no external samples")),
                    channels == 2 || recorded.containsKey(path)||results.containsKey(path) ? "LICENSED_DERIVED" : "ORIGINAL_GENERATED", metrics.channels, metrics.frames, metrics.peak, metrics.rms));
        }
        assets.add(asset(metricsPath, "audio-metrics", metricsBytes, "GenerateAudio.java", "VERIFIED_AGAINST_PCM"));
        assets.add(asset("audio/score.txt", "score", resource("audio/score.txt"), "GenerateAudio.java", "SOURCE_PRESENT"));
        assets.add(asset("audio/music-provenance.json", "music-provenance", provenanceBytes, source.get("sourceUrl").getAsString(), "VERIFIED"));
        assets.add(asset("audio/music-source.json", "music-provenance", sourceBytes, source.get("sourceUrl").getAsString(), "VERIFIED"));
    }

    private static Map<String,String> verifyResults(AudioConfig config,List<Asset> assets,JsonObject music)throws Exception {
        byte[] bytes=resource("audio/result-provenance.json");
        JsonObject evidence=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
        if(evidence.get("schemaVersion").getAsInt()!=1||!"Kevin MacLeod".equals(evidence.get("author").getAsString())
                ||!"CC-BY-4.0".equals(evidence.get("license").getAsString())
                ||!"licenses/assets/CC-BY-4.0.txt".equals(evidence.get("licensePath").getAsString())
                ||!"src/tools/assets/audio/Metalmania-source.wav".equals(evidence.get("sourcePath").getAsString())
                ||!music.get("sourceSha256").getAsString().equals(evidence.get("sourceSha256").getAsString())
                ||!"src/tools/assets/audio/recorded/processed/ram-hit-1.wav".equals(evidence.get("impactSourcePath").getAsString())
                ||!"CC0-1.0".equals(evidence.get("impactLicense").getAsString())
                ||!hash(resource("audio/ram-hit-1.wav")).equals(evidence.get("impactSourceSha256").getAsString()))
            throw new IOException("Result sting source/license mismatch");
        Map<String,String> result=new HashMap<>();
        for(JsonElement entry:evidence.getAsJsonArray("assets")) {
            JsonObject item=entry.getAsJsonObject();String path=item.get("path").getAsString();
            if(!Set.of("audio/victory.wav","audio/defeat.wav","audio/draw.wav").contains(path)
                    ||!config.effects().contains(path.substring(6,path.length()-4))||result.containsKey(path)
                    ||!hash(resource(path)).equals(item.get("sha256").getAsString())
                    ||item.get("transformation").getAsString().isBlank())throw new IOException("Result sting hash/recipe mismatch");
            result.put(path,"Metalmania; Kevin MacLeod; CC-BY-4.0; recorded CC0 metal accent; "+item.get("transformation").getAsString());
        }
        if(result.size()!=3)throw new IOException("All three recorded result stings are required");
        assets.add(asset("audio/result-provenance.json","result-provenance",bytes,"GenerateAudio.java; recorded guitar result edits","VERIFIED"));
        return result;
    }

    private static Map<String,String> verifyRecordedEffects(AudioConfig config,List<Asset> assets)throws Exception {
        byte[] sourcesBytes=resource("audio/sfx-sources.json"),provenanceBytes=resource("audio/sfx-provenance.json");
        JsonObject sources=JsonParser.parseString(new String(sourcesBytes,StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject provenance=JsonParser.parseString(new String(provenanceBytes,StandardCharsets.UTF_8)).getAsJsonObject();
        if(sources.get("schemaVersion").getAsInt()!=1 || provenance.get("schemaVersion").getAsInt()!=1
                || !hash(sourcesBytes).equals(provenance.get("sourceEvidenceSha256").getAsString())
                || !"0.4".equals(provenance.get("revision").getAsString())
                || !"src/tools/prepare_recorded_sfx.py".equals(provenance.get("preparationScriptPath").getAsString())
                || !hash(Files.readAllBytes(Path.of("src/tools/prepare_recorded_sfx.py"))).equals(provenance.get("preparationScriptSha256").getAsString()))
            throw new IOException("Unsupported recording provenance schema");
        Map<String,String> origins=new HashMap<>();
        Map<String,String> archives=Map.of(
                "https://opengameart.org/sites/default/files/Prepared%20SFX%20Library.7z","cc1ab5a99a0a365105c7c5dd783f4b0b1fe90938114d3ceec53856bfe005f7d6",
                "https://opengameart.org/sites/default/files/25-CC0-bang-sfx.zip","c0c9ecc11e2dc0d190f0cced755569858234b8528286bc83becb6314c663407f");
        Set<String> pages=Set.of("https://opengameart.org/content/the-free-firearm-sound-library",
                "https://opengameart.org/content/25-cc0-bang-firework-sfx");
        for(JsonElement element:sources.getAsJsonArray("sources")) {
            JsonObject item=element.getAsJsonObject();
            String id=item.get("id").getAsString(),origin=item.get("sourceUrl").getAsString();
            if(origins.put(id,origin)!=null || !pages.contains(origin)
                    || !"CC0-1.0".equals(item.get("license").getAsString())
                    || !"licenses/assets/CC0-1.0.txt".equals(item.get("licensePath").getAsString())
                    || !Objects.equals(archives.get(item.get("archiveUrl").getAsString()),item.get("archiveSha256").getAsString()))
                throw new IOException("Unapproved/duplicate recording source: "+id);
            for(String kind:List.of("source","decoded")) {
                String path=item.get(kind+"Path").getAsString();
                String directory=kind.equals("source")?"original":"decoded";
                if(path.contains("..") || !path.startsWith("src/tools/assets/audio/recorded/"+directory+"/")
                        || !hash(Files.readAllBytes(Path.of(path))).equals(item.get(kind+"Sha256").getAsString()))
                    throw new IOException("Recording source checksum/path mismatch: "+path);
            }
        }
        Map<String,String> recorded=new HashMap<>();
        for(JsonElement element:provenance.getAsJsonArray("assets")) {
            JsonObject item=element.getAsJsonObject();
            String path=item.get("path").getAsString(),prepared=item.get("preparedPath").getAsString();
            Set<String> inputOrigins=new TreeSet<>();
            for(JsonElement input:item.getAsJsonArray("inputs")) {
                String origin=origins.get(input.getAsString());
                if(origin==null)throw new IOException("Missing recorded layer source");
                inputOrigins.add(origin);
            }
            if(inputOrigins.isEmpty() || !path.matches("audio/[a-z][a-z0-9-]*\\.wav")
                    || !config.effects().contains(path.substring(6,path.length()-4)) || recorded.containsKey(path)
                    || !prepared.equals("src/tools/assets/audio/recorded/processed/"+path.substring(6))
                    || !"CC0-1.0".equals(item.get("license").getAsString())
                    || item.get("transformation").getAsString().isBlank()
                    || !hash(resource(path)).equals(item.get("sha256").getAsString())
                    || !hash(Files.readAllBytes(Path.of(prepared))).equals(item.get("sha256").getAsString()))
                throw new IOException("Recorded effect source/output/transform mismatch: "+path);
            recorded.put(path,String.join("; ",inputOrigins)+"; "+item.get("transformation").getAsString());
        }
        for(String cue:List.of("machine-gun","metal-hit","explosion","destroyed","homing-launch","power-launch",
                "power-explosion","mine-detonate","napalm-launch","napalm-explosion",
                "cannon-launch","cannon-ricochet","cannon-hit","ballistic-launch","ballistic-fall","ballistic-explosion","ram-hit")) {
            List<String> bank=config.cueBanks().get(cue);
            if(bank==null || bank.size()<3 || bank.stream().anyMatch(take->!recorded.containsKey("audio/"+take+".wav")))
                throw new IOException("Three recorded variations required: "+cue);
        }
        assets.add(asset("audio/sfx-sources.json","sfx-provenance",sourcesBytes,"CC0 recorded source inventory","VERIFIED"));
        assets.add(asset("audio/sfx-provenance.json","sfx-provenance",provenanceBytes,"prepare_recorded_sfx.py; offline layered cue recipes","VERIFIED"));
        return recorded;
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
        byte[] evidence = resource("fonts/font-provenance.json");
        JsonObject provenance = JsonParser.parseString(new String(evidence, StandardCharsets.UTF_8)).getAsJsonObject();
        if (!provenance.get("externalFontInput").getAsBoolean() || provenance.get("glyphs").getAsInt() != 161
                || provenance.get("schemaVersion").getAsInt() != 2 || !provenance.get("license").getAsString().equals("OFL-1.1")) {
            throw new IOException("Font provenance/checksum mismatch");
        }
        Set<String> names = new HashSet<>();
        for (JsonElement element : provenance.getAsJsonArray("fonts")) {
            JsonObject face = element.getAsJsonObject();
            String name = face.get("name").getAsString(), style = name.equals("wreck") ? "Regular" : "Bold";
            if (!Set.of("wreck", "wreck-bold").contains(name) || !names.add(name)) throw new IOException("Unexpected/duplicate font face");
            byte[] font = resource("fonts/" + name + ".fnt"), atlas = resource("fonts/" + name + ".png");
            Path source = Path.of("src/tools/assets/fonts/RobotoCondensed-" + style + ".ttf");
            byte[] sourceBytes = Files.readAllBytes(source);
            if (!hash(font).equals(face.get("fntSha256").getAsString()) || !hash(atlas).equals(face.get("pngSha256").getAsString())
                    || !hash(sourceBytes).equals(face.get("sourceSha256").getAsString()))
                throw new IOException("Font source or output checksum mismatch: " + name);
            verifyFontSourceLicense(sourceBytes);
            verifyFontBytes(font, atlas);
            String origin = "https://github.com/googlefonts/roboto-3-classic/releases/download/v3.016/Roboto_v3.016.zip; GenerateFont.java; local static " + style + " TTF";
            assets.add(asset("fonts/" + name + ".fnt", "bitmap-font", font, origin, "LICENSED_DERIVED"));
            assets.add(asset("fonts/" + name + ".png", "font-atlas", atlas, origin, "LICENSED_DERIVED"));
        }
        if (names.size() != 2) throw new IOException("Both regular and bold font faces required");
        assets.add(asset("fonts/font-provenance.json", "font-provenance", evidence, "GenerateFont.java", "VERIFIED"));
    }

    /** A license from a newer font release must not be attached to older, differently licensed bytes. */
    static void verifyFontSourceLicense(byte[] source) throws IOException {
        try {
            ByteBuffer ttf = ByteBuffer.wrap(source).order(ByteOrder.BIG_ENDIAN);
            int tables = Short.toUnsignedInt(ttf.getShort(4));
            if (tables > (source.length - 12) / 16) throw new IOException("Invalid TTF table directory");
            for (int table = 0; table < tables; table++) {
                int offset = 12 + table * 16;
                if (ttf.getInt(offset) != 0x6e616d65) continue; // name
                int start = ttf.getInt(offset + 8), length = ttf.getInt(offset + 12);
                if (start < 0 || length < 6 || start > source.length - length) throw new IOException("Invalid TTF name table");
                int count = Short.toUnsignedInt(ttf.getShort(start + 2));
                int strings = Short.toUnsignedInt(ttf.getShort(start + 4));
                if (count > (length - 6) / 12) throw new IOException("Invalid TTF name records");
                boolean found = false;
                for (int record = 0; record < count; record++) {
                    int row = start + 6 + record * 12;
                    int platform = Short.toUnsignedInt(ttf.getShort(row));
                    if (Short.toUnsignedInt(ttf.getShort(row + 6)) != 13) continue;
                    int size = Short.toUnsignedInt(ttf.getShort(row + 8));
                    int textOffset = strings + Short.toUnsignedInt(ttf.getShort(row + 10));
                    if (textOffset > length - size) throw new IOException("Invalid TTF license name range");
                    String license = new String(source, start + textOffset, size,
                            platform == 0 || platform == 3 ? StandardCharsets.UTF_16BE : StandardCharsets.ISO_8859_1);
                    if (!license.contains("SIL Open Font License, Version 1.1"))
                        throw new IOException("TTF embedded license does not match the retained OFL notice");
                    found = true;
                }
                if (found) return;
            }
            throw new IOException("Missing TTF embedded OFL license metadata");
        } catch (IndexOutOfBoundsException e) {
            throw new IOException("Truncated TTF license metadata", e);
        }
    }

    public static void verifyFontBytes(byte[] font, byte[] atlas) throws IOException {
        String text = new String(font, StandardCharsets.UTF_8);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(atlas));
        if (image == null || image.getWidth() != 2048 || image.getHeight() != 1024
                || !(text.contains("file=\"wreck.png\"") || text.contains("file=\"wreck-bold.png\""))
                || !text.contains("smooth=1") || !text.contains("unicode=1")) {
            throw new IOException("Invalid font atlas reference or dimensions");
        }
        Set<Integer> characters = new HashSet<>();
        boolean antialiased = false;
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
                int alpha = image.getRGB(column, row) >>> 24;
                if (alpha != 0) visible = true;
                if (alpha > 0 && alpha < 255) antialiased = true;
            }
            if (code != 32 && !visible) throw new IOException("Empty visible font glyph " + code);
        }
        for (int code = 32; code <= 126; code++) if (!characters.contains(code)) throw new IOException("Missing ASCII glyph " + code);
        for (int code = 0x410; code <= 0x44f; code++) if (!characters.contains(code)) throw new IOException("Missing Cyrillic glyph " + code);
        if (!characters.contains(0x401) || !characters.contains(0x451) || characters.size() != 161 || !antialiased)
            throw new IOException("Unexpected font charset or missing grayscale antialiasing");
    }

    private static void verifyTextures(List<Asset> assets) throws Exception {
        byte[] evidence = resource("licenses/asset-provenance.json");
        JsonElement tree = JsonParser.parseString(new String(evidence, StandardCharsets.UTF_8));
        Configs.validate(tree, TextureIndex.class, "asset-provenance");
        TextureIndex index = Configs.gson().fromJson(tree, TextureIndex.class);
        Set<String> required = new TreeSet<>();
        for (String material : MATERIALS) for (String map : List.of("diffuse", "normal", "specular"))
            required.add("textures/materials/" + material + "/" + map + ".png");
        required.addAll(List.of("textures/vehicle/paint.png", "textures/vehicle/rubber.png"));
        Set<String> found = new HashSet<>();
        if (index.schemaVersion != 1) throw new IOException("Unexpected texture provenance schema");
        for (TextureSource source : index.assets) {
            if (!required.contains(source.path) || !found.add(source.path)
                    || !source.sourcePath.startsWith("src/tools/assets/materials/") || source.sourcePath.contains("..")
                    || !(source.sourceUrl.startsWith("https://dl.polyhaven.org/file/ph-assets/Textures/png/2k/")
                         || source.sourceUrl.equals("https://polyhaven.com/a/blue_metal_plate"))
                    || !source.license.equals("CC0-1.0") || !source.licensePath.equals("licenses/assets/CC0-1.0.txt")
                    || source.author.isBlank() || source.transformation.isBlank())
                throw new IOException("Unapproved/malformed texture source: " + source.path);
            byte[] bytes = resource(source.path);
            if (!hash(bytes).equals(source.sha256) || !hash(Files.readAllBytes(Path.of(source.sourcePath))).equals(source.sourceSha256))
                throw new IOException("Texture source/output checksum mismatch: " + source.path);
            verifyTextureBytes(bytes, source.path.endsWith("/normal.png"));
            assets.add(asset(source.path, "licensed-texture", bytes, source.sourceUrl + "; " + source.transformation, "LICENSED_BYTES_VERIFIED"));
        }
        if (!found.equals(required)) throw new IOException("Required 2K material map missing");
        assets.add(asset("licenses/asset-provenance.json", "asset-provenance", evidence, "src/tools/import_assets.py", "VERIFIED"));
    }

    static void verifyTextureBytes(byte[] bytes, boolean normalMap) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null || image.getWidth() != 2048 || image.getHeight() != 2048)
            throw new IOException("Required texture must be a 2K PNG");
        if (normalMap) for (int y = 0; y < 2048; y += 31) for (int x = 0; x < 2048; x += 31) {
            int rgb = image.getRGB(x, y);
            float nx = ((rgb >> 16) & 255) / 127.5f - 1, ny = ((rgb >> 8) & 255) / 127.5f - 1, nz = (rgb & 255) / 127.5f - 1;
            float length = nx * nx + ny * ny + nz * nz;
            if (length < .75f || length > 1.25f || nz < -.01f) throw new IOException("Invalid tangent-space normal map");
        }
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

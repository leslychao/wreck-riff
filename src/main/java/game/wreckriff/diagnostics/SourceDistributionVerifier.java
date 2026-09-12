package game.wreckriff.diagnostics;

import com.google.gson.JsonParser;
import game.wreckriff.config.Configs;

import java.io.*;
import java.net.JarURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipFile;

/** Offline source/notice integrity gate. Does not load native code or grant distribution approval. */
public final class SourceDistributionVerifier {
    public record Artifact(String path, long bytes, String sha256) {}
    public record SourceEvidence(String id, String version, String commit, String url, Artifact archive,
                                 int archiveEntries, long expandedBytes) {}
    public record Report(int schemaVersion, String status, String distributionApproval, Artifact index,
                         List<SourceEvidence> sources, List<Artifact> documents, NativeBinding nativeLibrary,
                         RuntimeBinding runtime, String selectedJdkReleaseSha256, String inputIndexSha256) {}
    record RequiredFile(String path, String sha256) {}
    record Source(String id, String version, String commit, String archive, String url, long bytes,
                  String sha256, String prefix, List<RequiredFile> requiredFiles, List<Artifact> parts) {}
    record NativeBinding(String sourceId, String jar, String jarSha256, String entry, String sha256, String gitEntry) {}
    record RuntimeBinding(String sourceId, String releaseFile, String implementor, String implementorVersion,
                          String runtimeVersion, String javaVersion, String source, List<String> jlinkModules) {}
    record Document(String path, String sha256, String source) {}
    record Index(int schemaVersion, List<Source> sources, NativeBinding nativeLibrary,
                 RuntimeBinding runtime, List<Document> documents) {}
    // The released materials contain complete archives, never the repository's storage parts.
    record PackagedSource(String id, String version, String commit, String archive, String url, long bytes,
                          String sha256, String prefix, List<RequiredFile> requiredFiles) {}
    record PackagedIndex(int schemaVersion, List<PackagedSource> sources, NativeBinding nativeLibrary,
                         RuntimeBinding runtime, List<Document> documents) {}
    record ArchiveInspection(int entries, long expandedBytes) {}

    private static final String NATIVE_ENTRY = "windows/x64/org/lwjgl/openal/OpenAL.dll";
    private static final long MAX_ARCHIVE_BYTES = 256L * 1024 * 1024;
    private static final long MAX_PART_BYTES = 32L * 1024 * 1024;
    private static final long MAX_EXPANDED_BYTES = 2L * 1024 * 1024 * 1024;
    private static final Set<String> REQUIRED_DOCUMENTS = Set.of("README.md", "openal/COPYING", "openal/BSD-3Clause",
            "openal/LICENSE-pffft", "openal/fmt-LICENSE", "openal/REPLACEMENT.md", "runtime/LICENSE",
            "runtime/ADDITIONAL_LICENSE_INFO", "runtime/ASSEMBLY_EXCEPTION", "runtime/microsoft-jdk-release.txt", "runtime/README.md");
    private SourceDistributionVerifier() {}

    public static void main(String[] args) throws IOException {
        if (args.length > 1) throw new IllegalArgumentException("Expected optional report output directory");
        Report report = verify(args.length == 0 ? Path.of("build/reports/assets") : Path.of(args[0]));
        System.out.println(report.status() + "; distribution approval: " + report.distributionApproval());
    }

    /** Produces complete offline materials plus source-distribution.json; failures never leave a PASS report. */
    public static Report verify(Path output) throws IOException {
        Path root = Path.of("src/tools/licenses");
        // A prior PASS must not survive a failed recheck of the same output location.
        Files.createDirectories(output);
        Files.deleteIfExists(output.resolve("source-distribution.json"));
        Path stage = Files.createTempDirectory(output, "source-distribution-materials-pending-");
        try {
            Report report = verify(root, readIndex(root), nativeJar(), Path.of(System.getProperty("java.home")), stage);
            Path destination = output.resolve("source-distribution-materials");
            deleteMaterials(destination);
            Files.move(stage, destination);
            Files.writeString(output.resolve("source-distribution.json"), Configs.gson().toJson(report) + "\n", StandardCharsets.UTF_8);
            return report;
        } finally { deleteMaterials(stage); }
    }

    static Index readIndex(Path root) throws IOException {
        Path file = contained(root, "source-index.json");
        if (Files.size(file) > 128 * 1024) throw new IOException("Oversized source distribution index");
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            var tree = JsonParser.parseReader(reader);
            Configs.validate(tree, Index.class, "source-index");
            return Configs.gson().fromJson(tree, Index.class);
        } catch (RuntimeException e) {
            throw new IOException("Invalid source distribution index: " + e.getMessage(), e);
        }
    }

    static Path nativeJar() throws IOException {
        var resources = Collections.list(SourceDistributionVerifier.class.getClassLoader().getResources(NATIVE_ENTRY));
        if (resources.size() != 1 || !(resources.getFirst().openConnection() instanceof JarURLConnection connection))
            throw new IOException("Expected exactly one bundled OpenAL Windows x64 native JAR");
        try { return Path.of(connection.getJarFileURL().toURI()); }
        catch (Exception e) { throw new IOException("Invalid OpenAL native JAR location", e); }
    }

    static Report verify(Path root, Index index, Path nativeJar, Path jdkHome, Path materials) throws IOException {
        if (index.schemaVersion != 2 || index.sources.size() != 2) throw new IOException("Unknown source distribution schema/components");
        Map<String, Source> sources = new HashMap<>();
        for (Source source : index.sources) {
            if (sources.put(source.id, source) != null || !source.commit.matches("[0-9a-f]{40}")
                    || !source.sha256.matches("[0-9a-f]{64}") || source.bytes <= 0 || source.bytes > MAX_ARCHIVE_BYTES
                    || source.requiredFiles.isEmpty() || source.requiredFiles.size() > 64)
                throw new IOException("Invalid or duplicate source identity: " + source.id);
            String repository = switch (source.id) {
                case "openal-soft" -> "LWJGL-CI/openal-soft";
                case "microsoft-openjdk" -> "microsoft/openjdk-jdk21u";
                default -> throw new IOException("Unknown source component: " + source.id);
            };
            String name = repository.substring(repository.indexOf('/') + 1);
            if (!source.url.equals("https://codeload.github.com/" + repository + "/tar.gz/" + source.commit)
                    || !source.prefix.equals(name + "-" + source.commit + "/") || !source.archive.startsWith("sources/")
                    || !source.archive.endsWith(".tar.gz")) throw new IOException("Source URL/archive commit mismatch: " + source.id);
        }
        if (!sources.keySet().equals(Set.of("openal-soft", "microsoft-openjdk"))) throw new IOException("Missing corresponding source component");
        List<Artifact> documents = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Document document : index.documents) {
            if (!names.add(document.path) || document.source.isBlank()) throw new IOException("Invalid/duplicate source notice");
            Path file = contained(root, document.path);
            if (Files.size(file) > 1024 * 1024 || !hash(file).equals(document.sha256))
                throw new IOException("Source notice checksum mismatch: " + document.path);
            documents.add(new Artifact(document.path, Files.size(file), document.sha256));
        }
        if (!names.containsAll(REQUIRED_DOCUMENTS)) throw new IOException("Missing source distribution notice/instructions");

        verifyNative(nativeJar, index.nativeLibrary, sources.get("openal-soft"));
        String releaseHash = verifyRuntime(root, jdkHome, index.runtime, sources.get("microsoft-openjdk"));
        Files.createDirectories(materials);
        for (Document document : index.documents) {
            Path target = materials.resolve(document.path);
            Files.createDirectories(target.getParent());
            Files.copy(contained(root, document.path), target);
            if (!hash(target).equals(document.sha256)) throw new IOException("Source notice changed during copying: " + document.path);
        }
        List<SourceEvidence> evidence = new ArrayList<>();
        for (Source source : index.sources) {
            Path archive = materials.resolve(source.archive);
            assembleArchive(root, source, archive);
            ArchiveInspection inspected = inspectArchive(archive, source);
            evidence.add(new SourceEvidence(source.id, source.version, source.commit, source.url,
                    new Artifact(source.archive, source.bytes, source.sha256), inspected.entries, inspected.expandedBytes));
        }
        var packagedSources = index.sources.stream().map(source -> new PackagedSource(source.id, source.version,
                source.commit, source.archive, source.url, source.bytes, source.sha256, source.prefix, source.requiredFiles)).toList();
        var packagedIndex = new PackagedIndex(1, packagedSources, index.nativeLibrary, index.runtime, index.documents);
        Path indexFile = materials.resolve("source-index.json");
        Files.writeString(indexFile, Configs.gson().toJson(packagedIndex) + "\n", StandardCharsets.UTF_8);
        return new Report(1, "SOURCE_AND_NOTICE_MATERIALS_VERIFIED", "NOT_GRANTED",
                new Artifact("source-index.json", Files.size(indexFile), hash(indexFile)), List.copyOf(evidence),
                List.copyOf(documents), index.nativeLibrary, index.runtime, releaseHash, hash(contained(root, "source-index.json")));
    }

    /** Concatenates bounded, ordered repository parts while checking each part and the complete archive. */
    static void assembleArchive(Path root, Source source, Path target) throws IOException {
        safeRelative(source.archive);
        if (source.parts == null || source.parts.isEmpty() || source.parts.size() > 8
                || source.bytes <= 0 || source.bytes > MAX_ARCHIVE_BYTES) throw new IOException("Invalid source archive parts");
        long expectedTotal = 0;
        for (int i = 0; i < source.parts.size(); i++) {
            Artifact part = source.parts.get(i);
            String expectedPath = source.parts.size() == 1 ? source.archive : source.archive + ".part%03d".formatted(i + 1);
            if (!part.path.equals(expectedPath) || part.bytes <= 0 || part.bytes > MAX_PART_BYTES
                    || !part.sha256.matches("[0-9a-f]{64}")) throw new IOException("Missing/reordered source archive part");
            expectedTotal += part.bytes;
        }
        if (expectedTotal != source.bytes) throw new IOException("Source parts do not cover the complete archive");
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "source-archive-", ".pending");
        try {
            MessageDigest complete = digest();
            try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(temporary))) {
                byte[] buffer = new byte[128 * 1024];
                for (Artifact part : source.parts) {
                    Path file = contained(root, part.path);
                    if (Files.size(file) != part.bytes) throw new IOException("Source part size mismatch: " + part.path);
                    MessageDigest digest = digest();
                    long read = 0;
                    try (InputStream input = Files.newInputStream(file)) {
                        for (int count; (count = input.read(buffer)) >= 0;) {
                            if (count == 0) continue;
                            read += count;
                            if (read > part.bytes) throw new IOException("Source part grew during verification: " + part.path);
                            digest.update(buffer, 0, count);
                            complete.update(buffer, 0, count);
                            output.write(buffer, 0, count);
                        }
                    }
                    if (read != part.bytes || !HexFormat.of().formatHex(digest.digest()).equals(part.sha256))
                        throw new IOException("Source part checksum mismatch: " + part.path);
                }
            }
            if (!HexFormat.of().formatHex(complete.digest()).equals(source.sha256))
                throw new IOException("Assembled source archive checksum mismatch");
            Files.move(temporary, target);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void deleteMaterials(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        Path absolute = directory.toAbsolutePath().normalize();
        if (!absolute.getFileName().toString().startsWith("source-distribution-materials")
                || !directory.toRealPath().startsWith(absolute.getParent().toRealPath()))
            throw new IOException("Refusing to remove materials outside the report directory");
        // Does not follow symlinks or junctions; only this generated materials tree is removed.
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void verifyNative(Path jarPath, NativeBinding binding, Source source) throws IOException {
        if (!binding.sourceId.equals(source.id) || !binding.entry.equals(NATIVE_ENTRY)
                || !binding.gitEntry.equals("META-INF/" + NATIVE_ENTRY + ".git")
                || !jarPath.getFileName().toString().equals(binding.jar) || !hash(jarPath).equals(binding.jarSha256))
            throw new IOException("OpenAL native JAR/source reference mismatch");
        try (ZipFile jar = new ZipFile(jarPath.toFile())) {
            byte[] dll = zipBytes(jar, binding.entry, 16 * 1024 * 1024);
            String commit = new String(zipBytes(jar, binding.gitEntry, 128), StandardCharsets.US_ASCII).strip();
            if (!commit.equals(source.commit) || !hash(dll).equals(binding.sha256)) throw new IOException("OpenAL DLL hash/source commit mismatch");
            String binaryText = new String(dll, StandardCharsets.ISO_8859_1);
            if (!source.version.matches("[0-9]+\\.[0-9]+\\.[0-9]+") || !binaryText.contains("1.1 ALSOFT " + source.version + "\0"))
                throw new IOException("OpenAL source version does not match native DLL version");
        }
    }

    private static String verifyRuntime(Path root, Path jdkHome, RuntimeBinding binding, Source source) throws IOException {
        if (!binding.sourceId.equals(source.id) || !source.version.equals(binding.runtimeVersion)
                || !binding.source.matches("[0-9a-f]{12,40}") || !source.commit.startsWith(binding.source)
                || !binding.releaseFile.equals("runtime/microsoft-jdk-release.txt") || !binding.implementor.equals("Microsoft")
                || !binding.javaVersion.startsWith("21.") || !binding.runtimeVersion.startsWith(binding.javaVersion + "+")
                || binding.jlinkModules.isEmpty() || new HashSet<>(binding.jlinkModules).size() != binding.jlinkModules.size())
            throw new IOException("JDK corresponding source version/reference mismatch");
        Map<String, String> expected = Map.of("IMPLEMENTOR", binding.implementor, "IMPLEMENTOR_VERSION", binding.implementorVersion,
                "JAVA_RUNTIME_VERSION", binding.runtimeVersion, "JAVA_VERSION", binding.javaVersion, "SOURCE", ".:git:" + binding.source);
        for (Path release : List.of(contained(root, binding.releaseFile), jdkHome.resolve("release"))) {
            Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(release, StandardCharsets.UTF_8)) { properties.load(reader); }
            for (var field : expected.entrySet()) {
                if (!("\"" + field.getValue() + "\"").equals(properties.getProperty(field.getKey())))
                    throw new IOException("JDK version/source metadata mismatch: " + field.getKey());
            }
        }
        return hash(jdkHome.resolve("release"));
    }

    /** Checks the entire pinned gzip/tar stream and required source/build entries without extracting any files. */
    static ArchiveInspection inspectArchive(Path archive, Source source) throws IOException {
        if (Files.size(archive) != source.bytes || Files.size(archive) > MAX_ARCHIVE_BYTES || !hash(archive).equals(source.sha256))
            throw new IOException("Source archive checksum/size mismatch: " + source.archive);
        Map<String, String> required = new HashMap<>();
        for (RequiredFile file : source.requiredFiles) {
            safeRelative(file.path);
            if (required.put(source.prefix + file.path, file.sha256) != null || !file.sha256.matches("[0-9a-f]{64}"))
                throw new IOException("Invalid required source file: " + file.path);
        }
        int entries = 0;
        long expanded = 0;
        String longName = null, paxPath = null, commit = null;
        try (InputStream input = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(archive)), 128 * 1024)) {
            while (true) {
                byte[] header = input.readNBytes(512);
                if (header.length != 512) throw new IOException("Truncated source tar header");
                if (zero(header)) {
                    byte[] second = input.readNBytes(512);
                    if (second.length != 512 || !zero(second)) throw new IOException("Incomplete tar end marker");
                    byte[] padding = input.readNBytes(1024 * 1024 + 1);
                    if (padding.length > 1024 * 1024 || !zero(padding)) throw new IOException("Unexpected trailing source archive data");
                    break;
                }
                if (++entries > 200_000) throw new IOException("Too many source archive entries");
                long checksum = 0;
                for (int i = 0; i < 512; i++) checksum += i >= 148 && i < 156 ? 32 : Byte.toUnsignedInt(header[i]);
                if (checksum != octal(header, 148, 8)) throw new IOException("Source tar header checksum mismatch");
                long size = octal(header, 124, 12);
                if (size > MAX_EXPANDED_BYTES - expanded) throw new IOException("Expanded source archive exceeds limit");
                expanded += size;
                char type = (char) header[156];
                String name = field(header, 0, 100), prefix = field(header, 345, 155);
                if (!prefix.isEmpty()) name = prefix + "/" + name;
                if (type == 'x' || type == 'g' || type == 'L' || type == 'K') {
                    if (size > 64 * 1024) throw new IOException("Oversized tar path metadata");
                    byte[] metadata = input.readNBytes((int) size);
                    if (metadata.length != size) throw new IOException("Truncated tar path metadata");
                    if (type == 'L') longName = field(metadata, 0, metadata.length);
                    else if (type == 'x' || type == 'g') {
                        Map<String, String> attributes = pax(metadata);
                        if (type == 'x') paxPath = attributes.get("path");
                        if (type == 'g' && attributes.containsKey("comment")) commit = attributes.get("comment");
                    }
                } else {
                    if (longName != null) name = longName;
                    if (paxPath != null) name = paxPath;
                    longName = null; paxPath = null;
                    safeRelative(name);
                    if (!(name + (name.endsWith("/") ? "" : "/")).startsWith(source.prefix))
                        throw new IOException("Source tar entry outside pinned commit prefix");
                    String expected = required.get(name);
                    if (expected != null) {
                        if (type != '0' && type != '\0' || size > 1024 * 1024) throw new IOException("Invalid required source archive entry: " + name);
                        byte[] bytes = input.readNBytes((int) size);
                        if (bytes.length != size || !hash(bytes).equals(expected)) throw new IOException("Required source file hash mismatch: " + name);
                        required.remove(name);
                    } else input.skipNBytes(size);
                }
                input.skipNBytes((512 - size % 512) % 512);
            }
        }
        if (!source.commit.equals(commit) || !required.isEmpty()) throw new IOException("Source archive commit/required files mismatch");
        return new ArchiveInspection(entries, expanded);
    }

    private static Map<String, String> pax(byte[] bytes) throws IOException {
        Map<String, String> values = new HashMap<>();
        for (int offset = 0; offset < bytes.length;) {
            int space = offset;
            while (space < bytes.length && bytes[space] != ' ') space++;
            int length;
            try { length = Integer.parseInt(new String(bytes, offset, space - offset, StandardCharsets.US_ASCII)); }
            catch (RuntimeException e) { throw new IOException("Invalid pax length", e); }
            if (length <= space - offset + 1 || length > bytes.length - offset || bytes[offset + length - 1] != '\n')
                throw new IOException("Invalid pax record");
            String record = new String(bytes, space + 1, offset + length - space - 2, StandardCharsets.UTF_8);
            int equals = record.indexOf('=');
            if (equals <= 0) throw new IOException("Invalid pax key");
            values.put(record.substring(0, equals), record.substring(equals + 1));
            offset += length;
        }
        return values;
    }

    private static long octal(byte[] bytes, int start, int size) throws IOException {
        String text = field(bytes, start, size).strip();
        try { return text.isEmpty() ? 0 : Long.parseLong(text, 8); }
        catch (NumberFormatException e) { throw new IOException("Invalid tar size/checksum", e); }
    }
    private static String field(byte[] bytes, int start, int size) {
        int end = start;
        while (end < start + size && bytes[end] != 0) end++;
        return new String(bytes, start, end - start, StandardCharsets.UTF_8);
    }
    private static boolean zero(byte[] bytes) { for (byte b : bytes) if (b != 0) return false; return true; }
    private static Path contained(Path root, String relative) throws IOException {
        safeRelative(relative);
        Path canonicalRoot = root.toRealPath(), path = root.resolve(relative).toRealPath();
        if (!path.startsWith(canonicalRoot) || !Files.isRegularFile(path)) throw new IOException("Source material outside directory: " + relative);
        return path;
    }
    private static void safeRelative(String path) throws IOException {
        if (path.isBlank() || path.startsWith("/") || path.contains("\\") || path.contains(":") || path.indexOf('\0') >= 0
                || Arrays.asList(path.split("/")).contains("..")) throw new IOException("Unsafe source material path");
    }
    private static byte[] zipBytes(ZipFile zip, String name, int maximum) throws IOException {
        var entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory() || entry.getSize() < 1 || entry.getSize() > maximum)
            throw new IOException("Missing/oversized native evidence entry: " + name);
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length != entry.getSize()) throw new IOException("Truncated/oversized native evidence entry: " + name);
            return bytes;
        }
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String hash(byte[] bytes) { return HexFormat.of().formatHex(digest().digest(bytes)); }
    private static String hash(Path file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[128 * 1024];
            for (int count; (count = input.read(buffer)) >= 0;) if (count != 0) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}

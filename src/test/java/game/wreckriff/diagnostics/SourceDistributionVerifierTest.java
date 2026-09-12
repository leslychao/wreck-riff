package game.wreckriff.diagnostics;

import com.google.gson.JsonParser;
import game.wreckriff.config.Configs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SourceDistributionVerifierTest {
    private static final Path ROOT = Path.of("src/tools/licenses");
    @TempDir Path temporary;

    @Test void realArchivesMatchTheNativeJarAndTheSelectedMicrosoftRuntime() throws Exception {
        var report = SourceDistributionVerifier.verify(temporary);
        assertEquals("SOURCE_AND_NOTICE_MATERIALS_VERIFIED", report.status());
        assertEquals("NOT_GRANTED", report.distributionApproval());
        assertEquals(2, report.sources().size());
        assertTrue(report.sources().stream().anyMatch(s -> s.id().equals("microsoft-openjdk") && s.archiveEntries() > 80_000));
        assertTrue(Files.size(temporary.resolve("source-distribution.json")) > 1000);
        Path materials = temporary.resolve("source-distribution-materials");
        assertTrue(Files.size(materials.resolve("sources/microsoft-openjdk-21.0.11+10-87e312d6.tar.gz")) > 100 * 1024 * 1024);
        var packaged = JsonParser.parseString(Files.readString(materials.resolve("source-index.json"))).getAsJsonObject();
        assertEquals(1, packaged.get("schemaVersion").getAsInt());
        assertFalse(packaged.getAsJsonArray("sources").get(1).getAsJsonObject().has("parts"));
        assertEquals(report.index().sha256(), sha(Files.readAllBytes(materials.resolve("source-index.json"))));
        assertEquals(report.inputIndexSha256(), sha(Files.readAllBytes(ROOT.resolve("source-index.json"))));
    }

    @Test void rejectsAnAlteredSourceArchiveBeforeReadingItsContents() throws Exception {
        var index = SourceDistributionVerifier.readIndex(ROOT);
        var source = index.sources().getFirst();
        Path altered = temporary.resolve("altered.tar.gz");
        byte[] bytes = Files.readAllBytes(ROOT.resolve(source.archive()));
        bytes[bytes.length / 2] ^= 1;
        Files.write(altered, bytes);
        assertThrows(IOException.class, () -> SourceDistributionVerifier.inspectArchive(altered, source));
    }

    @Test void rejectsANativeVersionThatDoesNotDescribeTheBundledDll() throws Exception {
        var tree = JsonParser.parseString(Files.readString(ROOT.resolve("source-index.json")));
        tree.getAsJsonObject().getAsJsonArray("sources").get(0).getAsJsonObject().addProperty("version", "1.24.2");
        var index = Configs.gson().fromJson(tree, SourceDistributionVerifier.Index.class);
        IOException error = assertThrows(IOException.class,
                () -> SourceDistributionVerifier.verify(ROOT, index, nativeJar(), Path.of(System.getProperty("java.home")), temporary.resolve("materials")));
        assertTrue(error.getMessage().contains("version"));
    }

    @Test void rejectsAJdkVersionOrSourceCommitMismatch() throws Exception {
        var index = SourceDistributionVerifier.readIndex(ROOT);
        Path jdk = temporary.resolve("jdk");
        Files.createDirectories(jdk);
        String actual = Files.readString(Path.of(System.getProperty("java.home"), "release"));
        for (String bad : List.of(actual.replace("21.0.11", "21.0.12"), actual.replace("87e312d67249", "000000000000"))) {
            Files.writeString(jdk.resolve("release"), bad);
            assertThrows(IOException.class, () -> SourceDistributionVerifier.verify(ROOT, index, nativeJar(), jdk, temporary.resolve("materials")));
        }
    }

    @Test void rejectsMissingOrChangedNoticeAndUnsafePaths() throws Exception {
        var tree = JsonParser.parseString(Files.readString(ROOT.resolve("source-index.json")));
        var notice = tree.getAsJsonObject().getAsJsonArray("documents").get(0).getAsJsonObject();
        for (String path : List.of("missing-notice.txt", "../AGENTS.md")) {
            notice.addProperty("path", path);
            var index = Configs.gson().fromJson(tree, SourceDistributionVerifier.Index.class);
            assertThrows(IOException.class, () -> SourceDistributionVerifier.verify(ROOT, index, nativeJar(), Path.of(System.getProperty("java.home")), temporary.resolve("materials")));
        }
        notice.addProperty("path", "openal/COPYING");
        notice.addProperty("sha256", "0".repeat(64));
        var index = Configs.gson().fromJson(tree, SourceDistributionVerifier.Index.class);
        assertThrows(IOException.class, () -> SourceDistributionVerifier.verify(ROOT, index, nativeJar(), Path.of(System.getProperty("java.home")), temporary.resolve("materials")));
    }

    @Test void rejectsAnUnknownIndexField() throws Exception {
        Files.writeString(temporary.resolve("source-index.json"),
                Files.readString(ROOT.resolve("source-index.json")).replaceFirst("\"schemaVersion\": [12]", "\"schemaVersion\": 2, \"approval\": true"));
        assertThrows(IOException.class, () -> SourceDistributionVerifier.readIndex(temporary));
    }

    @Test void streamsOrderedPartsIntoTheOriginalArchiveBytes() throws Exception {
        var source = partFixture();
        Path output = temporary.resolve("output/archive.tar.gz");
        SourceDistributionVerifier.assembleArchive(temporary, source, output);
        assertEquals("firstsecondthird", Files.readString(output));
        assertEquals(source.sha256(), sha(Files.readAllBytes(output)));
    }

    @Test void missingPartCannotLeaveACompleteOrPartialOutput() throws Exception {
        var source = partFixture();
        Files.delete(temporary.resolve(source.parts().get(1).path()));
        assertPartsFail(source);
    }

    @Test void reorderedPartsAreRejectedEvenWhenEveryIndividualPartIsIntact() throws Exception {
        var source = partFixture();
        var tree = Configs.gson().toJsonTree(source).getAsJsonObject();
        var reversed = new ArrayList<>(source.parts());
        Collections.reverse(reversed);
        tree.add("parts", Configs.gson().toJsonTree(reversed));
        assertPartsFail(Configs.gson().fromJson(tree, SourceDistributionVerifier.Source.class));
    }

    @Test void modifiedPartCannotLeaveACompleteOrPartialOutput() throws Exception {
        var source = partFixture();
        Files.writeString(temporary.resolve(source.parts().getFirst().path()), "wrong");
        assertPartsFail(source);
    }

    private void assertPartsFail(SourceDistributionVerifier.Source source) throws Exception {
        Path output = temporary.resolve("output/archive.tar.gz");
        assertThrows(IOException.class, () -> SourceDistributionVerifier.assembleArchive(temporary, source, output));
        assertFalse(Files.exists(output));
        if (Files.exists(output.getParent())) try (var files = Files.list(output.getParent())) { assertEquals(0, files.count()); }
    }

    private SourceDistributionVerifier.Source partFixture() throws Exception {
        var tree = Configs.gson().toJsonTree(SourceDistributionVerifier.readIndex(ROOT).sources().getFirst()).getAsJsonObject();
        List<SourceDistributionVerifier.Artifact> parts = new ArrayList<>();
        Files.createDirectories(temporary.resolve("sources"));
        for (String text : List.of("first", "second", "third")) {
            String path = "sources/fixture.tar.gz.part%03d".formatted(parts.size() + 1);
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            Files.write(temporary.resolve(path), bytes);
            parts.add(new SourceDistributionVerifier.Artifact(path, bytes.length, sha(bytes)));
        }
        tree.addProperty("archive", "sources/fixture.tar.gz");
        tree.addProperty("bytes", 16);
        tree.addProperty("sha256", sha("firstsecondthird".getBytes(StandardCharsets.UTF_8)));
        tree.add("parts", Configs.gson().toJsonTree(parts));
        return Configs.gson().fromJson(tree, SourceDistributionVerifier.Source.class);
    }

    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }

    private static Path nativeJar() throws IOException { return SourceDistributionVerifier.nativeJar(); }
}

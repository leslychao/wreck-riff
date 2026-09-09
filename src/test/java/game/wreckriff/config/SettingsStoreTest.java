package game.wreckriff.config;

import com.jme3.input.KeyInput;
import game.wreckriff.simulation.MatchSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SettingsStoreTest {
    @TempDir Path directory;
    @Test void v1MigrationPreservesVideoAudioAndAnExplicitBindingThatUsesNewModifierKey() throws Exception {
        String original="""
                {"schemaVersion":1,"width":1600,"height":900,"fullscreen":false,"music":0.35,
                 "keys":{"Throttle":29}}
                """;
        Path file=directory.resolve("settings.json");Files.writeString(file,original);
        SettingsStore store=new SettingsStore(directory);
        assertFalse(store.firstRun());assertEquals(2,store.settings().schemaVersion);
        assertEquals(1600,store.settings().width);assertEquals(900,store.settings().height);
        assertFalse(store.settings().fullscreen);assertEquals(.35f,store.settings().music);
        assertEquals(KeyInput.KEY_LCONTROL,store.settings().keys.get("Throttle"));
        assertEquals(0,store.settings().keys.get("Ability modifier"));assertTrue(store.bindingWarning().contains("Ability modifier"));
        assertEquals(original,Files.readString(directory.resolve("settings.json.v1.bak")));
        var reloaded=new SettingsStore(directory);assertEquals(store.settings().keys,reloaded.settings().keys);
        assertEquals(4,reloaded.settings().samples);
    }
    @Test void firstRunIsDifferentFromKnownOrDamagedUserSettings() throws Exception {
        assertTrue(new SettingsStore(directory).firstRun());
        Files.writeString(directory.resolve("settings.json"),"{bad");
        assertFalse(new SettingsStore(directory).firstRun(),"A broken preference file is not authorization to change display mode");
    }
    @Test void newerSchemaIsPreservedEvenAfterAttemptedSave() throws Exception {
        Path path=directory.resolve("settings.json");String future="{\"schemaVersion\":99,\"newOption\":\"preserve\"}";
        Files.writeString(path,future);
        SettingsStore store=new SettingsStore(directory);store.settings().master=.1f;store.saveSettings();
        assertEquals(future,Files.readString(path));assertTrue(store.warning().contains("preserved"));
    }
    @Test void damagedJsonIsRetainedAndDefaultsCanBeSavedAsUtf8WithoutBom() throws Exception {
        Files.writeString(directory.resolve("settings.json"),"{bad-json",StandardCharsets.UTF_8);
        SettingsStore store=new SettingsStore(directory);store.saveSettings();
        try(var files=Files.list(directory)) {
            Path backup=files.filter(p->p.getFileName().toString().startsWith("settings.json.broken-")).findFirst().orElseThrow();
            assertEquals("{bad-json",Files.readString(backup));
        }
        byte[] bytes=Files.readAllBytes(directory.resolve("settings.json"));
        assertEquals('{',bytes[0]);assertFalse(Files.exists(directory.resolve("settings.json.tmp")));
        assertEquals(1280,new SettingsStore(directory).settings().width);
    }
    @Test void invalidRangesAndMissingBindingsRestoreUsableDefaults() throws Exception {
        Files.writeString(directory.resolve("settings.json"),"""
                {"schemaVersion":1,"width":-10,"height":5,"master":9,"sensitivity":-4,
                 "deadZone":1,"keys":{"Throttle":0,"Recover":1,"nonsense":99}}
                """);
        SettingsStore store=new SettingsStore(directory);var settings=store.settings();
        assertEquals(1280,settings.width);assertEquals(720,settings.height);assertEquals(1,settings.master);
        assertEquals(.25f,settings.sensitivity);assertEquals(.45f,settings.deadZone);
        assertEquals(SettingsStore.defaultKeys().keySet(),settings.keys.keySet());
        assertEquals(KeyInput.KEY_W,settings.keys.get("Throttle"));assertEquals(KeyInput.KEY_R,settings.keys.get("Recover"));
    }
    @Test void partiallyEditedBindingFileCannotCreateConflictingActions() throws Exception {
        Files.writeString(directory.resolve("settings.json"),"{\"schemaVersion\":1,\"keys\":{\"Throttle\":"+KeyInput.KEY_A+"}}");
        var settings=new SettingsStore(directory).settings();
        assertEquals(settings.keys.size(),new HashSet<>(settings.keys.values()).size(),"Each keyboard action must have a distinct usable binding");
    }
    @Test void nonFiniteStatisticsCannotBreakMatchCompletion() throws Exception {
        Files.writeString(directory.resolve("stats.json"),"{\"schemaVersion\":1,\"totalDamage\":\"NaN\",\"wins\":-4}");
        SettingsStore store=new SettingsStore(directory);MatchSession session=new MatchSession(1,180);
        session.outcome=MatchSession.Outcome.VICTORY;session.vehicle(0).damageDealt=40;
        assertDoesNotThrow(()->store.record(session));
        assertTrue(Double.isFinite(store.stats().totalDamage));assertTrue(store.stats().wins>=1);
    }
    @Test void oneCompletedSessionIsRecordedOnceAndRetryCanRecordAnother() {
        SettingsStore store=new SettingsStore(directory);MatchSession one=new MatchSession(1,180);
        store.record(one);assertEquals(0,store.stats().completedMatches);
        one.outcome=MatchSession.Outcome.VICTORY;one.vehicle(0).damageDealt=123;one.vehicle(0).eliminations=2;
        store.record(one);store.record(one);assertEquals(1,store.stats().completedMatches);assertEquals(123,store.stats().totalDamage);
        MatchSession two=new MatchSession(1,180);two.outcome=MatchSession.Outcome.DRAW;store.record(two);
        assertEquals(2,store.stats().completedMatches);assertEquals(1,store.stats().wins);assertEquals(1,store.stats().draws);
    }
    @Test void unavailableDataDirectoryStillAllowsACompleteInMemoryMatch() throws Exception {
        Path path=directory.resolve("a-file");Files.writeString(path,"occupied");
        SettingsStore store=new SettingsStore(path);store.saveSettings();
        MatchSession session=new MatchSession(1,180);session.outcome=MatchSession.Outcome.DEFEAT;
        assertDoesNotThrow(()->store.record(session));assertEquals(1,store.stats().losses);
        assertEquals("occupied",Files.readString(path));
    }
}

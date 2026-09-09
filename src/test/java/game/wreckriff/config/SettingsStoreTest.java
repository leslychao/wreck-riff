package game.wreckriff.config;

import com.jme3.input.KeyInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SettingsStoreTest {
    @TempDir Path directory;
    @Test void interfaceSettingsDefaultForExistingProfilesAndPersistWithBounds() throws Exception {
        Files.writeString(directory.resolve("settings.json"),"{\"schemaVersion\":3,\"music\":0.35}");
        var store=new SettingsStore(directory);
        assertEquals(1,store.settings().uiScale);assertEquals(.6f,store.settings().flashes);assertTrue(store.settings().glow);assertTrue(store.settings().subtitles);
        store.settings().uiScale=99;store.settings().flashes=-1;store.settings().subtitles=false;store.settings().glow=false;store.saveSettings();
        var loaded=new SettingsStore(directory).settings();
        assertEquals(1.5f,loaded.uiScale);assertEquals(0,loaded.flashes);assertFalse(loaded.subtitles);assertFalse(loaded.glow);assertEquals(.35f,loaded.music);
    }
    @Test void v1MigrationPreservesVideoAudioAndAnExplicitBindingThatConflictsWithNewFreezeKey() throws Exception {
        String original="""
                {"schemaVersion":1,"width":1600,"height":900,"fullscreen":false,"music":0.35,
                 "keys":{"Throttle":44}}
                """;
        Path file=directory.resolve("settings.json");Files.writeString(file,original);
        SettingsStore store=new SettingsStore(directory);
        assertFalse(store.firstRun());assertEquals(3,store.settings().schemaVersion);
        assertEquals(1600,store.settings().width);assertEquals(900,store.settings().height);
        assertFalse(store.settings().fullscreen);assertEquals(.35f,store.settings().music);
        assertEquals(KeyInput.KEY_Z,store.settings().keys.get("Throttle"));
        assertEquals(0,store.settings().keys.get("Freeze"));assertTrue(store.bindingWarning().contains("Freeze"));
        assertEquals(original,Files.readString(directory.resolve("settings.json.v1.bak")));
        var reloaded=new SettingsStore(directory);assertEquals(store.settings().keys,reloaded.settings().keys);
        assertEquals(4,reloaded.settings().samples);
    }
    @Test void v2MigrationTransfersFormerSpecialAndPreservesDisabledKeysAndConflicts() throws Exception {
        String original="""
                {"schemaVersion":2,"width":1920,"height":1080,"samples":8,"sfx":0.42,
                 "keys":{"Feedback Pulse":20,"Ability modifier":29,"Recover":2,"Rear view":0}}
                """;
        Files.writeString(directory.resolve("settings.json"),original);
        SettingsStore store=new SettingsStore(directory);
        assertEquals(KeyInput.KEY_T,store.settings().keys.get("Shield"));
        assertEquals(KeyInput.KEY_1,store.settings().keys.get("Recover"));
        assertEquals(0,store.settings().keys.get("Select Homing"));
        assertEquals(0,store.settings().keys.get("Rear view"));
        assertTrue(store.bindingWarning().contains("Select Homing"));
        assertFalse(store.settings().keys.containsKey("Feedback Pulse"));
        assertFalse(store.settings().keys.containsKey("Ability modifier"));
        assertEquals(8,store.settings().samples);assertEquals(.42f,store.settings().sfx);
        assertEquals(original,Files.readString(directory.resolve("settings.json.v2.bak")));
        assertEquals(store.settings().keys,new SettingsStore(directory).settings().keys);
    }
    @Test void schemaThreeAddsNewWeaponsOnlyOnFreeKeysWithoutChangingExistingPreferences() throws Exception {
        Files.writeString(directory.resolve("settings.json"),"""
                {"schemaVersion":3,"width":1600,"height":900,"music":0.35,
                 "keys":{"Recover":6,"Shield":33,"Freeze":44,"Rear view":0}}
                """);
        var store=new SettingsStore(directory);var settings=store.settings();
        assertEquals(3,settings.schemaVersion);assertEquals(1600,settings.width);assertEquals(.35f,settings.music);
        assertEquals(KeyInput.KEY_5,settings.keys.get("Recover"));assertEquals(0,settings.keys.get("Select Ballistic"));
        assertEquals(KeyInput.KEY_6,settings.keys.get("Select Cannon"));assertEquals(0,settings.keys.get("Rear view"));
        assertEquals(KeyInput.KEY_F,settings.keys.get("Shield"));assertEquals(KeyInput.KEY_Z,settings.keys.get("Freeze"));
        assertFalse(settings.keys.containsValue(KeyInput.KEY_X));assertTrue(store.bindingWarning().contains("Select Ballistic"));
        store.saveSettings();assertEquals(settings.keys,new SettingsStore(directory).settings().keys);
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
    @Test void unavailableDataDirectoryStillAllowsInMemoryPreferences() throws Exception {
        Path path=directory.resolve("a-file");Files.writeString(path,"occupied");
        SettingsStore store=new SettingsStore(path);store.settings().music=.25f;
        assertDoesNotThrow(store::saveSettings);assertEquals(.25f,store.settings().music);
        assertEquals("occupied",Files.readString(path));
    }
    @Test void preferencesNeverReadMigrateOrRewriteTheProgressFile() throws Exception {
        String future="{\"schemaVersion\":99,\"campaign\":\"preserve\"}";
        Files.writeString(directory.resolve("stats.json"),future);
        SettingsStore store=new SettingsStore(directory);store.saveSettings();
        assertEquals(future,Files.readString(directory.resolve("stats.json")));
        assertEquals("",store.warning());
    }
}

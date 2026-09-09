package game.wreckriff.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jme3.input.KeyInput;
import game.wreckriff.combat.WeaponType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import static game.wreckriff.config.ProgressStore.*;
import static org.junit.jupiter.api.Assertions.*;

class RosterPersistenceTest {
    @TempDir Path directory;

    @Test void everyChassisRetainsItsFullHpAndSpecialCooldownAcrossReload() {
        for(var definition:VehicleDefinition.values()) {
            Path location=directory.resolve(definition.id());
            var checkpoint=checkpoint(definition.id(),definition.maximumHp());
            try(var store=new ProgressStore(location)) {
                var attempt=store.beginAttempt("construction_17",Mode.CAMPAIGN,false);
                store.saveCheckpoint(attempt,checkpoint);assertTrue(store.flush(Duration.ofSeconds(5)));
            }
            try(var loaded=new ProgressStore(location)) {
                assertEquals(checkpoint,loaded.snapshot().campaign().checkpoint());
                assertEquals(875L,loaded.snapshot().campaign().checkpoint().player().abilityCooldownTicks().get("special"));
            }
        }
    }

    @Test void checkpointChecksHpAgainstSelectedChassisRatherThanGlobalRivetCap() {
        assertDoesNotThrow(()->checkpoint("grinder",1040));
        assertThrows(IllegalArgumentException.class,()->checkpoint("rivet",801));
        assertThrows(IllegalArgumentException.class,()->checkpoint("spark",641));
        assertThrows(IllegalArgumentException.class,()->checkpoint("grinder",1041));
    }

    @Test void versionTwoMigrationPreservesAttemptRevisionResourcesAndExactOriginal() throws Exception {
        Snapshot previous;
        try(var store=new ProgressStore(directory)) {
            var attempt=store.beginAttempt("construction_17",Mode.CAMPAIGN,false);
            store.saveCheckpoint(attempt,checkpoint("rivet",730));assertTrue(store.flush(Duration.ofSeconds(5)));
            previous=store.snapshot();
        }
        JsonObject old=JsonParser.parseString(Files.readString(directory.resolve("stats.json"))).getAsJsonObject();
        old.addProperty("schemaVersion",2);
        JsonObject player=old.getAsJsonObject("campaign").getAsJsonObject("checkpoint").getAsJsonObject("player");
        player.getAsJsonObject("abilityCooldownTicks").remove("special");
        // Earlier serialized enum IDs may be uppercase; migration writes the current canonical IDs.
        player.addProperty("selectedWeapon","HOMING");
        for(String field:new String[]{"weapons","abilityCooldownTicks"}) {
            JsonObject upper=new JsonObject();
            for(var entry:player.getAsJsonObject(field).entrySet())upper.add(entry.getKey().toUpperCase(Locale.ROOT),entry.getValue());
            player.add(field,upper);
        }
        String original=old.toString();Files.writeString(directory.resolve("stats.json"),original);
        try(var loaded=new ProgressStore(directory)) {
            assertEquals(previous.revision()+1,loaded.snapshot().revision());
            assertEquals(previous.attemptSequence(),loaded.snapshot().attemptSequence());
            assertEquals(previous.activeAttempt(),loaded.snapshot().activeAttempt());
            assertEquals(previous.stats(),loaded.snapshot().stats());
            var saved=loaded.snapshot().campaign().checkpoint();
            assertEquals("rivet",saved.profileId());assertEquals(730,saved.player().hp());
            assertEquals(0L,saved.player().abilityCooldownTicks().get("special"));
            assertEquals(51L,saved.player().abilityCooldownTicks().get("freeze"));
            assertTrue(loaded.flush(Duration.ofSeconds(5)));
            assertEquals(original,Files.readString(directory.resolve("stats.json.v2.bak")));
        }
        try(var loaded=new ProgressStore(directory)) {assertEquals(previous.activeAttempt(),loaded.snapshot().activeAttempt());}
    }

    @Test void settingsMigrationPreservesAnExistingCBindingAndVisualPreferences() throws Exception {
        String original="""
                {"schemaVersion":3,"width":1600,"height":900,"uiScale":1.2,"flashes":0.2,"glow":false,
                 "keys":{"Recover":46,"Shield":33,"Freeze":44}}
                """;
        Files.writeString(directory.resolve("settings.json"),original);
        var store=new SettingsStore(directory);
        assertEquals("rivet",store.settings().selectedVehicleId);
        assertEquals(KeyInput.KEY_C,store.settings().keys.get("Recover"));
        assertEquals(0,store.settings().keys.get("Special"));
        assertEquals(1.2f,store.settings().uiScale);assertEquals(.2f,store.settings().flashes);assertFalse(store.settings().glow);
        assertEquals(original,Files.readString(directory.resolve("settings.json.v3.bak")));
        store.settings().selectedVehicleId="grinder";store.saveSettings();
        assertEquals("grinder",new SettingsStore(directory).settings().selectedVehicleId);
    }

    private static Checkpoint checkpoint(String profile,float hp) {
        var weapons=new LinkedHashMap<String,WeaponResource>();
        for(var type:WeaponType.values())weapons.put(type.id(),new WeaponResource(2,17));
        var resources=new PlayerResources(hp,38,"homing",weapons,Map.of("freeze",51L,"shield",31L,"special",875L),
                new ResourceTimers(3,5,7,9,11,13,15,17));
        return new Checkpoint("construction_17",profile,0,42,"normal",CheckpointStage.BOSS,resources,
                new SafePose(12,1.25,14,.2,"lower-road","player-spawn"),new ArenaState(Map.of(),Map.of(),Map.of(),12,71),1234);
    }
}

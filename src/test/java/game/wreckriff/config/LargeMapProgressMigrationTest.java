package game.wreckriff.config;

import com.google.gson.*;
import game.wreckriff.arena.ArenaRegistry;
import game.wreckriff.combat.WeaponType;
import game.wreckriff.simulation.MatchCheckpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static game.wreckriff.config.ProgressStore.*;
import static org.junit.jupiter.api.Assertions.*;

class LargeMapProgressMigrationTest {
    @TempDir Path directory;
    private static final Duration TIMEOUT=Duration.ofSeconds(5);
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private static final List<String> ORIGINAL=List.of("construction_17","neon_zero","euphoria_park","ash_necropolis","doomsday_arena");

    @ParameterizedTest @CsvSource({"construction_17,rivet,ARENA","construction_17,grinder,BOSS","construction_17,spark,BOSS",
            "neon_zero,rivet,BOSS","neon_zero,grinder,ARENA","neon_zero,spark,BOSS",
            "euphoria_park,rivet,BOSS","euphoria_park,grinder,BOSS","euphoria_park,spark,ARENA"})
    void migratesExistingCheckpointsToCurrentGeometryAndRetainsAllResources(String arenaId,String profile,CheckpointStage stage) throws Exception {
        var original=original(arenaId,profile,stage);String bytes=original.toString();Files.writeString(directory.resolve("stats.json"),bytes);
        var registry=ArenaRegistry.load();Snapshot saved;
        try(var store=new ProgressStore(directory,MatchCheckpoint.references(registry))) {
            assertTrue(store.writable(),store.warning());saved=store.snapshot();
            assertEquals(CURRENT_LAYOUT_REVISION,saved.layoutRevision());assertEquals(11,saved.revision());
            assertEquals(9,saved.attemptSequence());assertNull(saved.activeAttempt());
            assertEquals(arenaId,saved.campaign().currentArenaId());
            var cp=saved.campaign().checkpoint();assertNotNull(cp,store.warning());
            assertEquals(stage,cp.stage());assertEquals(profile,cp.profileId());assertEquals(773,cp.activeTicksBeforeBoss());
            var before=JSON.fromJson(original.getAsJsonObject("campaign").get("checkpoint"),HistoricalCheckpoint.class);
            assertEquals(before.player(),cp.player());assertEquals(before.seed(),cp.seed());assertEquals(before.liveryId(),cp.liveryId());
            assertNotEquals(before.safePose(),cp.safePose());assertNotEquals(before.arena(),cp.arena());
            assertEquals(CURRENT_LAYOUT_REVISION,cp.layoutRevision());
            assertDoesNotThrow(()->MatchCheckpoint.validateReferences(registry,cp));
            assertFalse(cp.arena().pickups().containsKey("old-pickup"));
            assertTrue(cp.arena().pickups().values().stream().allMatch(p->p.respawnTicks()==0));
            assertTrue(cp.arena().objects().values().stream().noneMatch(ObjectState::destroyed));
            assertEquals(before,saved.historicalLayouts().get("revision-1").checkpoint());
            assertFalse(saved.records().containsKey(arenaId));assertTrue(saved.records().containsKey(LEGACY_ARENA));
            assertTrue(store.flush(TIMEOUT),store.warning());assertEquals(MIGRATION_NOTICE,store.warning());
            assertEquals(bytes,Files.readString(directory.resolve("stats.json.v3.bak")));
            assertEquals(bytes,Files.readString(directory.resolve("stats.json.bak")));
        }
        byte[] committed=Files.readAllBytes(directory.resolve("stats.json"));
        try(var reopened=new ProgressStore(directory,MatchCheckpoint.references(registry))) {
            assertEquals(saved,reopened.snapshot());assertFalse(reopened.savePending());assertEquals("",reopened.warning());
        }
        assertArrayEquals(committed,Files.readAllBytes(directory.resolve("stats.json")),"Migration must be idempotent");
    }

    @ParameterizedTest @ValueSource(strings={"ash_necropolis","doomsday_arena"})
    void removedCurrentChapterCompletesTheNewCampaignAndArchivesItsResources(String current) throws Exception {
        var original=original(current,"grinder",CheckpointStage.BOSS);String bytes=original.toString();
        Files.writeString(directory.resolve("stats.json"),bytes);
        try(var store=new ProgressStore(directory)) {
            assertTrue(store.writable(),store.warning());assertNull(store.snapshot().campaign().currentArenaId());
            assertNull(store.snapshot().campaign().checkpoint());assertNull(store.snapshot().activeAttempt());
            assertEquals(Set.copyOf(CAMPAIGN_ARENAS),store.snapshot().campaign().completedArenaIds());
            assertEquals(Set.copyOf(CAMPAIGN_ARENAS),store.snapshot().campaign().unlockedArenaIds());
            var archived=store.snapshot().historicalLayouts().get("revision-1");
            assertEquals(current,archived.currentArenaId());assertEquals(current,archived.checkpoint().arenaId());
            assertEquals(900,archived.checkpoint().player().hp());assertTrue(archived.records().containsKey(current));
            assertEquals(original.getAsJsonObject("stats"),JSON.toJsonTree(store.snapshot().stats()));
            assertDoesNotThrow(()->store.beginAttempt("construction_17",Mode.BOSS_DUEL,false));
            assertTrue(store.flush(TIMEOUT));assertEquals(bytes,Files.readString(directory.resolve("stats.json.v3.bak")));
            assertThrows(IllegalArgumentException.class,()->store.beginAttempt(current,Mode.ARENA,false));
        }
    }

    @Test void oldRecordsCannotBeatOrReceiveVictoriesFromNewLayout() throws Exception {
        var original=original("ash_necropolis","rivet",CheckpointStage.ARENA);
        Files.writeString(directory.resolve("stats.json"),original.toString());
        try(var store=new ProgressStore(directory)) {
            var historical=store.snapshot().historicalLayouts().get("revision-1");
            assertEquals(120L,historical.records().get("construction_17").bestFullMapTicks());
            var fresh=store.beginAttempt("construction_17",Mode.ARENA,false);
            assertTrue(store.record(new Result(fresh,Outcome.VICTORY,50,1,9000,true)));
            assertEquals(9000L,store.snapshot().records().get("construction_17").bestFullMapTicks());
            assertEquals(1,store.snapshot().records().get("construction_17").victories());
            assertEquals(historical,store.snapshot().historicalLayouts().get("revision-1"));
            assertEquals(9000L,store.snapshot().records().get("construction_17").bestFullMapTicks());
        }
    }

    @Test void geometryMigrationFailurePreservesBothAuthoritativeFilesWithoutQuarantiningThem() throws Exception {
        String original=original("construction_17","rivet",CheckpointStage.BOSS).toString();
        Files.writeString(directory.resolve("stats.json"),original);Files.writeString(directory.resolve("stats.json.bak"),original);
        var invalid=new ReferenceValidator() {
            public void validate(Checkpoint cp) {}
            public Checkpoint migrateLayout(Checkpoint cp) {throw new IllegalStateException("No safe spawn in new geometry");}
        };
        try(var store=new ProgressStore(directory,invalid)) {
            assertFalse(store.writable());assertTrue(store.warning().contains("No safe spawn"));assertFalse(store.flush(TIMEOUT));
        }
        assertEquals(original,Files.readString(directory.resolve("stats.json")));
        assertEquals(original,Files.readString(directory.resolve("stats.json.bak")));
        try(var files=Files.list(directory)) {assertEquals(2,files.count());}
    }

    @Test void blockedAtomicMigrationWriteKeepsOriginalAndCanRetryWithoutLosingHistory() throws Exception {
        String original=original("ash_necropolis","spark",CheckpointStage.BOSS).toString();
        Files.writeString(directory.resolve("stats.json"),original);Files.createDirectory(directory.resolve("stats.json.tmp"));
        try(var store=new ProgressStore(directory)) {
            assertFalse(store.flush(TIMEOUT));assertTrue(store.savePending());
            assertEquals(original,Files.readString(directory.resolve("stats.json")));
            assertEquals(original,Files.readString(directory.resolve("stats.json.v3.bak")));
            Files.delete(directory.resolve("stats.json.tmp"));assertTrue(store.flush(TIMEOUT));
            assertEquals(MIGRATION_NOTICE,store.warning());
            assertNotNull(store.snapshot().historicalLayouts().get("revision-1").checkpoint());
        }
        try(var reloaded=new ProgressStore(directory)) {assertNull(reloaded.snapshot().campaign().currentArenaId());}
    }

    @Test void conflictingPermanentMigrationBackupIsNeverOverwritten() throws Exception {
        String original=original("doomsday_arena","rivet",CheckpointStage.BOSS).toString();
        Files.writeString(directory.resolve("stats.json"),original);Files.writeString(directory.resolve("stats.json.v3.bak"),"earlier valuable backup");
        try(var store=new ProgressStore(directory)) {assertFalse(store.writable());assertFalse(store.flush(TIMEOUT));}
        assertEquals(original,Files.readString(directory.resolve("stats.json")));
        assertEquals("earlier valuable backup",Files.readString(directory.resolve("stats.json.v3.bak")));
    }

    @Test void malformedOldProgressUsesValidatedBackupRatherThanSilentMigrationRepair() throws Exception {
        var valid=original("doomsday_arena","rivet",CheckpointStage.BOSS);
        Files.writeString(directory.resolve("stats.json.bak"),valid.toString());
        var invalid=valid.deepCopy();invalid.getAsJsonObject("stats").remove("totalDamage");
        Files.writeString(directory.resolve("stats.json"),invalid.toString());
        try(var store=new ProgressStore(directory)) {
            assertTrue(store.writable());assertEquals(6,store.snapshot().stats().wins());assertTrue(store.flush(TIMEOUT));
        }
        try(var files=Files.list(directory)) {assertTrue(files.anyMatch(p->p.getFileName().toString().startsWith("stats.json.broken-")));}
    }

    static JsonObject original(String current,String profile,CheckpointStage stage) {
        int index=ORIGINAL.indexOf(current);var tree=new JsonObject();
        tree.addProperty("schemaVersion",3);tree.addProperty("revision",10);tree.addProperty("attemptSequence",9);
        tree.add("stats",JSON.toJsonTree(new Stats(7,6,1,0,35,13820.25)));
        var campaign=new JsonObject();campaign.addProperty("currentArenaId",current);
        campaign.add("unlockedArenaIds",JSON.toJsonTree(ORIGINAL.subList(0,index+1)));
        campaign.add("completedArenaIds",JSON.toJsonTree(ORIGINAL.subList(0,index)));
        Map<String,WeaponResource> weapons=new LinkedHashMap<>();
        for(var type:WeaponType.values())weapons.put(type.id(),new WeaponResource(2,7+type.ordinal()));
        var resources=new PlayerResources(profile.equals("grinder")?900:510,37,"cannon",weapons,
                Map.of("freeze",31L,"shield",61L,"special",91L),new ResourceTimers(1,2,3,4,5,6,7,8));
        var checkpoint=new HistoricalCheckpoint(current,profile,3,437,"normal",stage,resources,
                new SafePose(12,1.25,14,.2,"old-road","old-anchor"),
                new ArenaState(Map.of("old-pickup",new PickupState(876)),Map.of("old-gate",new ObjectState(0,true,true)),
                        Map.of("old-event",new HazardState(HazardPhase.WARNING,111,222,7)),123,619),773);
        campaign.add("checkpoint",JSON.toJsonTree(checkpoint));tree.add("campaign",campaign);
        var attempt=new JsonObject();attempt.addProperty("sequence",9);attempt.addProperty("id","70f9f30b-9bce-4e9d-bd94-4b67d709603c");
        attempt.addProperty("arenaId",current);attempt.addProperty("mode","CAMPAIGN");attempt.addProperty("fromBossCheckpoint",stage==CheckpointStage.BOSS);
        tree.add("activeAttempt",attempt);
        Map<String,ArenaRecord> records=new LinkedHashMap<>();for(String arena:ORIGINAL)records.put(arena,new ArenaRecord(1,1,120L,null));
        records.put(LEGACY_ARENA,new ArenaRecord(1,0,200L,null));tree.add("records",JSON.toJsonTree(records));return tree;
    }
}

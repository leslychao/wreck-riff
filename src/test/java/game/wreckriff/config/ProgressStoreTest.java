package game.wreckriff.config;

import game.wreckriff.combat.WeaponType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static game.wreckriff.config.ProgressStore.*;
import static org.junit.jupiter.api.Assertions.*;

class ProgressStoreTest {
    @TempDir Path directory;
    private static final Duration TIMEOUT=Duration.ofSeconds(5);

    @Test void resolvedDeathDuringBossEntryWithNoActiveCombatTicksStillRecordsExactlyOnce() {
        try(var store=new ProgressStore(directory)) {
            Attempt attempt=store.beginAttempt("construction_17",Mode.CAMPAIGN,false);
            var result=new Result(attempt,Outcome.DEFEAT,0,0,0,false);
            assertTrue(store.record(result));assertFalse(store.record(result));
            assertEquals(1,store.snapshot().stats().losses());assertTrue(store.snapshot().records().isEmpty());
        }
    }

    @Test void migratesOriginalCountersAndPreservesExactVersionOneFile() throws Exception {
        String original="""
                {"schemaVersion":1,"completedMatches":3,"wins":1,"losses":1,"draws":1,
                 "totalEliminations":9,"totalDamage":1234.5}
                """;
        Files.writeString(directory.resolve("stats.json"),original);
        try(var store=new ProgressStore(directory)) {
            assertEquals(3,store.snapshot().stats().completedMatches());
            assertEquals(1234.5,store.snapshot().stats().totalDamage());
            assertEquals(Set.of("construction_17"),store.snapshot().campaign().unlockedArenaIds());
            assertTrue(store.flush(TIMEOUT));
            assertEquals(original,Files.readString(directory.resolve("stats.json.v1.bak")));
            assertEquals(original,Files.readString(directory.resolve("stats.json.bak")));
        }
        try(var reloaded=new ProgressStore(directory)) { assertEquals(3,reloaded.snapshot().stats().completedMatches()); }
    }

    @Test void oneResultAtomicallyRecordsStatsClearsCheckpointAndUnlocksExactlyNextMap() throws Exception {
        try(var store=new ProgressStore(directory)) {
            Attempt attempt=store.beginAttempt("construction_17",Mode.CAMPAIGN,false);
            store.saveCheckpoint(attempt,checkpoint("construction_17"));
            assertTrue(store.flush(TIMEOUT));
            Result result=new Result(attempt,Outcome.VICTORY,2311.5,7,6000,true);
            assertTrue(store.record(result));assertFalse(store.record(result));
            assertEquals(1,store.snapshot().stats().completedMatches());
            assertEquals(2311.5,store.snapshot().stats().totalDamage());
            assertNull(store.snapshot().campaign().checkpoint());
            assertEquals("neon_zero",store.snapshot().campaign().currentArenaId());
            assertEquals(Set.of("construction_17","neon_zero"),store.snapshot().campaign().unlockedArenaIds());
            assertEquals(6000L,store.snapshot().records().get("construction_17").bestFullMapTicks());
            assertTrue(store.flush(TIMEOUT));
            try(var reloaded=new ProgressStore(directory)) {
                assertEquals(store.snapshot(),reloaded.snapshot());assertFalse(reloaded.record(result));
                Attempt next=reloaded.beginAttempt("neon_zero",Mode.CAMPAIGN,false);
                assertFalse(reloaded.record(result));
                assertEquals(next,reloaded.snapshot().activeAttempt());
            }
        }
    }

    @Test void checkpointIsAnImmutableDeepCopyAndAllTimersSurviveReload() {
        Map<String,PickupState> pickups=new LinkedHashMap<>();pickups.put("W1",new PickupState(301));
        Checkpoint source=checkpoint("construction_17");
        ArenaState arena=new ArenaState(pickups,Map.of("shortcut",new ObjectState(0,true,true)),
                Map.of("crane",new HazardState(HazardPhase.WARNING,60,180,2)),37,4289);
        Checkpoint checkpoint=new Checkpoint(source.arenaId(),source.profileId(),source.liveryId(),source.seed(),
                "normal",CheckpointStage.BOSS,source.player(),source.safePose(),arena,1234);
        pickups.clear();
        try(var store=new ProgressStore(directory)) {
            Attempt attempt=store.beginAttempt("construction_17",Mode.CAMPAIGN,false);
            store.saveCheckpoint(attempt,checkpoint);
            assertThrows(UnsupportedOperationException.class,()->checkpoint.arena().pickups().clear());
            assertThrows(UnsupportedOperationException.class,()->checkpoint.player().weapons().clear());
            assertTrue(store.flush(TIMEOUT));
            try(var reloaded=new ProgressStore(directory)) {
                assertEquals(checkpoint,reloaded.snapshot().campaign().checkpoint());
                assertEquals(301,reloaded.snapshot().campaign().checkpoint().arena().pickups().get("W1").respawnTicks());
            }
        }
    }

    @Test void bossRetryRetainsCheckpointAndCannotCreateFullMapRecord() {
        try(var store=new ProgressStore(directory)) {
            Attempt first=store.beginAttempt("construction_17",Mode.CAMPAIGN,false);
            Checkpoint cp=checkpoint("construction_17");store.saveCheckpoint(first,cp);
            store.record(new Result(first,Outcome.DEFEAT,100,6,600,false));
            assertEquals(cp,store.snapshot().campaign().checkpoint());
            Attempt retry=store.beginAttempt("construction_17",Mode.CAMPAIGN,true);
            store.record(new Result(retry,Outcome.VICTORY,4000,1,1200,true));
            ArenaRecord record=store.snapshot().records().get("construction_17");
            assertNull(record.bestFullMapTicks());assertNull(record.bestBossDuelTicks());
            assertEquals(1,record.bossVictories());
        }
    }

    @Test void standaloneWinsDoNotAdvanceCampaignAndBossDuelsRequireCompletedCampaign() {
        try(var store=new ProgressStore(directory)) {
            Attempt standalone=store.beginAttempt("construction_17",Mode.ARENA,false);
            store.record(new Result(standalone,Outcome.VICTORY,4000,7,5000,true));
            assertEquals(Set.of("construction_17"),store.snapshot().campaign().unlockedArenaIds());
            assertEquals("construction_17",store.snapshot().campaign().currentArenaId());
            assertThrows(IllegalArgumentException.class,()->store.beginAttempt("neon_zero",Mode.ARENA,false));
            assertThrows(IllegalArgumentException.class,()->store.beginAttempt("construction_17",Mode.BOSS_DUEL,false));
            for(String arena:CAMPAIGN_ARENAS) {
                Attempt attempt=store.beginAttempt(arena,Mode.CAMPAIGN,false);
                store.record(new Result(attempt,Outcome.VICTORY,4000,1,5000,true));
            }
            assertNull(store.snapshot().campaign().currentArenaId());
            Attempt duel=store.beginAttempt("construction_17",Mode.BOSS_DUEL,false);
            store.record(new Result(duel,Outcome.VICTORY,4000,1,3000,true));
            assertEquals(3000L,store.snapshot().records().get("construction_17").bestBossDuelTicks());
            long completed=store.snapshot().stats().completedMatches();
            store.beginNewCampaign();
            assertEquals(completed,store.snapshot().stats().completedMatches());
            assertEquals(Set.copyOf(CAMPAIGN_ARENAS),store.snapshot().campaign().unlockedArenaIds());
            assertEquals("construction_17",store.snapshot().campaign().currentArenaId());
        }
    }

    @Test void corruptedPrimaryRecoversLastValidatedBackupAndPreservesDamagedBytes() throws Exception {
        Snapshot backup;
        try(var store=new ProgressStore(directory)) {
            store.beginAttempt("dead-air-yard",Mode.LEGACY,false);assertTrue(store.flush(TIMEOUT));
            backup=store.snapshot();
            store.beginAttempt("construction_17",Mode.ARENA,false);assertTrue(store.flush(TIMEOUT));
        }
        Files.writeString(directory.resolve("stats.json"),"{damaged");
        try(var recovered=new ProgressStore(directory)) {
            assertEquals(backup,recovered.snapshot());assertTrue(recovered.warning().contains("backup"));
            assertTrue(recovered.writable());
            recovered.beginAttempt("dead-air-yard",Mode.LEGACY,false);assertTrue(recovered.flush(TIMEOUT));
        }
        try(var files=Files.list(directory)) {
            Path damaged=files.filter(p->p.getFileName().toString().startsWith("stats.json.broken-")).findFirst().orElseThrow();
            assertEquals("{damaged",Files.readString(damaged));
        }
    }

    @Test void futurePrimaryIsPreservedEvenWhenAnOlderBackupExists() throws Exception {
        try(var store=new ProgressStore(directory)) {
            store.beginAttempt("dead-air-yard",Mode.LEGACY,false);assertTrue(store.flush(TIMEOUT));
            store.beginNewCampaign();assertTrue(store.flush(TIMEOUT));
        }
        String future="{\"schemaVersion\":99,\"valuableFutureData\":true}";
        Files.writeString(directory.resolve("stats.json"),future);
        byte[] backup=Files.readAllBytes(directory.resolve("stats.json.bak"));
        try(var store=new ProgressStore(directory)) {
            assertFalse(store.writable());
            Attempt attempt=store.beginAttempt("dead-air-yard",Mode.LEGACY,false);
            assertTrue(store.record(new Result(attempt,Outcome.DEFEAT,12,0,25,false)));
            assertFalse(store.flush(TIMEOUT));assertEquals(1,store.snapshot().stats().completedMatches());
        }
        assertEquals(future,Files.readString(directory.resolve("stats.json")));
        assertArrayEquals(backup,Files.readAllBytes(directory.resolve("stats.json.bak")));
    }

    @Test void failedWriteKeepsLastValidFileAndLaterWriteStillSucceeds() throws Exception {
        try(var store=new ProgressStore(directory)) {
            store.beginAttempt("dead-air-yard",Mode.LEGACY,false);assertTrue(store.flush(TIMEOUT));
            String original=Files.readString(directory.resolve("stats.json"));
            // A directory at the temporary-file path is a deterministic filesystem failure on Windows and Linux.
            Files.createDirectory(directory.resolve("stats.json.tmp"));
            store.beginNewCampaign();assertFalse(store.flush(TIMEOUT));
            assertTrue(store.savePending());assertEquals(original,Files.readString(directory.resolve("stats.json")));
            Files.delete(directory.resolve("stats.json.tmp"));
            store.beginAttempt("construction_17",Mode.CAMPAIGN,false);
            assertTrue(store.flush(TIMEOUT));assertFalse(store.savePending());
            try(var loaded=new ProgressStore(directory)) { assertEquals(store.snapshot(),loaded.snapshot()); }
        }
    }

    @Test void serialWriterCoalescesBurstsWithoutLettingOlderRevisionsWin() throws Exception {
        try(var store=new ProgressStore(directory)) {
            ExecutorService callers=Executors.newFixedThreadPool(3);
            try {
                List<Future<?>> futures=new ArrayList<>();
                for(int i=0;i<150;i++) futures.add(callers.submit(store::beginNewCampaign));
                for(Future<?> future:futures) future.get(5,TimeUnit.SECONDS);
            } finally { callers.shutdownNow(); }
            Snapshot newest=store.snapshot();assertEquals(150,newest.revision());
            assertTrue(store.flush(TIMEOUT));
            try(var loaded=new ProgressStore(directory)) { assertEquals(newest,loaded.snapshot()); }
            assertFalse(Files.exists(directory.resolve("stats.json.tmp")));
        }
    }

    @Test void invalidIdentifiersResourcesAndNonFiniteValuesCannotEnterSnapshot() {
        assertThrows(IllegalArgumentException.class,()->new Stats(1,0,0,0,0,0));
        assertThrows(IllegalArgumentException.class,()->new Stats(0,0,0,0,0,Double.NaN));
        assertThrows(IllegalArgumentException.class,()->new SafePose(0,Double.NaN,0,0,"road","spawn"));
        PlayerResources player=checkpoint("construction_17").player();
        Map<String,WeaponResource> invalid=new HashMap<>(player.weapons());invalid.put("laser",new WeaponResource(1,0));
        assertThrows(IllegalArgumentException.class,()->new PlayerResources(520,40,"homing",invalid,player.abilityCooldownTicks(),player.timers()));
        assertThrows(IllegalArgumentException.class,()->new WeaponResource(-1,0));
        try(var store=new ProgressStore(directory,cp->{
            if(!cp.safePose().surfaceId().equals("lower-road")) throw new IllegalArgumentException("Missing surface");
        })) {
            Attempt attempt=store.beginAttempt("construction_17",Mode.CAMPAIGN,false);
            assertThrows(IllegalArgumentException.class,()->store.saveCheckpoint(attempt,checkpoint("neon_zero")));
            Checkpoint valid=checkpoint("construction_17");
            Checkpoint bad=new Checkpoint(valid.arenaId(),valid.profileId(),valid.liveryId(),valid.seed(),valid.difficulty(),valid.stage(),
                    valid.player(),new SafePose(0,1,0,0,"missing","spawn"),valid.arena(),0);
            assertThrows(IllegalArgumentException.class,()->store.saveCheckpoint(attempt,bad));
        }
    }

    @Test void invalidCurrentSchemaFallsBackToBackupInsteadOfSilentlyResettingFields() throws Exception {
        try(var store=new ProgressStore(directory)) {
            store.beginAttempt("dead-air-yard",Mode.LEGACY,false);assertTrue(store.flush(TIMEOUT));
            store.beginNewCampaign();assertTrue(store.flush(TIMEOUT));
        }
        String broken=Files.readString(directory.resolve("stats.json")).replace("\"totalDamage\": 0.0","\"totalDamage\": \"NaN\"");
        Files.writeString(directory.resolve("stats.json"),broken);
        try(var store=new ProgressStore(directory)) { assertTrue(store.warning().contains("backup"));assertEquals(1,store.snapshot().revision()); }
    }

    @Test void missingPrimitiveInCurrentSchemaIsDamageRatherThanAnImplicitZero() throws Exception {
        try(var store=new ProgressStore(directory)) {
            Attempt attempt=store.beginAttempt("dead-air-yard",Mode.LEGACY,false);
            store.record(new Result(attempt,Outcome.VICTORY,88,2,300,false));assertTrue(store.flush(TIMEOUT));
        }
        var tree=com.google.gson.JsonParser.parseString(Files.readString(directory.resolve("stats.json"))).getAsJsonObject();
        tree.getAsJsonObject("stats").remove("totalDamage");
        Files.writeString(directory.resolve("stats.json"),tree.toString());
        try(var store=new ProgressStore(directory)) {
            assertTrue(store.warning().contains("preserved")||store.warning().contains("backup"));
            assertNotEquals(1,store.snapshot().stats().wins(),"A partial result must never be accepted with a silently zeroed damage field");
        }
    }

    @Test void unavailableDirectoryDoesNotDiscardInMemoryResultsAndCanBeRetried() throws Exception {
        Path blocked=directory.resolve("blocked");Files.writeString(blocked,"retain");
        try(var store=new ProgressStore(blocked)) {
            Attempt attempt=store.beginAttempt("dead-air-yard",Mode.LEGACY,false);
            store.record(new Result(attempt,Outcome.DEFEAT,20,0,300,false));
            assertFalse(store.flush(TIMEOUT));assertEquals(1,store.snapshot().stats().losses());
            assertEquals("retain",Files.readString(blocked));
            Files.delete(blocked);assertTrue(store.flush(TIMEOUT));
            try(var loaded=new ProgressStore(blocked)) { assertEquals(store.snapshot(),loaded.snapshot()); }
        }
    }

    @Test void malformedVersionOneStatisticsDoNotPreventNewMatchResults() throws Exception {
        Files.writeString(directory.resolve("stats.json"),"{\"schemaVersion\":1,\"totalDamage\":\"NaN\",\"wins\":-4}");
        try(var store=new ProgressStore(directory)) {
            Attempt attempt=store.beginAttempt(LEGACY_ARENA,Mode.LEGACY,false);
            assertTrue(store.record(new Result(attempt,Outcome.VICTORY,40,1,500,false)));
            assertEquals(40,store.snapshot().stats().totalDamage());assertEquals(1,store.snapshot().stats().wins());
            assertTrue(store.flush(TIMEOUT));
        }
    }

    @Test void legacyAttemptRecordsOnceAndRetryGetsANewStableIdentity() {
        try(var store=new ProgressStore(directory)) {
            Attempt first=store.beginAttempt(LEGACY_ARENA,Mode.LEGACY,false);
            assertEquals(0,store.snapshot().stats().completedMatches());
            Result victory=new Result(first,Outcome.VICTORY,123,2,500,false);
            assertTrue(store.record(victory));assertFalse(store.record(victory));
            Attempt retry=store.beginAttempt(LEGACY_ARENA,Mode.LEGACY,false);
            assertNotEquals(first.id(),retry.id());assertTrue(retry.sequence()>first.sequence());
            assertTrue(store.record(new Result(retry,Outcome.DRAW,17,0,1000,false)));
            assertEquals(2,store.snapshot().stats().completedMatches());assertEquals(140,store.snapshot().stats().totalDamage());
            assertEquals(1,store.snapshot().stats().wins());assertEquals(1,store.snapshot().stats().draws());
        }
    }

    static Checkpoint checkpoint(String arenaId) {
        Map<String,WeaponResource> weapons=new LinkedHashMap<>();
        for(WeaponType type:WeaponType.values()) weapons.put(type.id(),new WeaponResource(2,17));
        PlayerResources player=new PlayerResources(520,38,"homing",weapons,Map.of("freeze",401L,"shield",61L),
                new ResourceTimers(3,5,7,9,11,13,15,17));
        return new Checkpoint(arenaId,"rivet",2,812,"normal",CheckpointStage.BOSS,player,
                new SafePose(12,1.25,14,.2,"lower-road","player-spawn"),
                new ArenaState(Map.of(),Map.of(),Map.of(),12,71),0);
    }
}

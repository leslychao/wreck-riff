package game.wreckriff.config;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import game.wreckriff.combat.WeaponType;

import java.io.*;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/** One owner for lifetime statistics, campaign progress and immutable fixed-tick checkpoints. */
public final class ProgressStore implements AutoCloseable {
    public static final int SCHEMA_VERSION=3;
    public static final String LEGACY_ARENA="dead-air-yard";
    public static final List<String> CAMPAIGN_ARENAS=List.of("construction_17","neon_zero","euphoria_park","ash_necropolis","doomsday_arena");
    private static final Set<String> WEAPONS=Arrays.stream(WeaponType.values()).map(WeaponType::id).collect(Collectors.toUnmodifiableSet());
    private static final Set<String> ABILITIES=Set.of("freeze","shield","special");
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    private static final int MAX_FILE_BYTES=4*1024*1024, MAX_ARENA_OBJECTS=512;
    private static final Set<String> NULLABLE=Set.of("Snapshot.activeAttempt","Campaign.currentArenaId","Campaign.checkpoint",
            "ArenaRecord.bestFullMapTicks","ArenaRecord.bestBossDuelTicks");

    public enum Mode { LEGACY, CAMPAIGN, ARENA, BOSS_DUEL }
    public enum Outcome { VICTORY, DEFEAT, DRAW }
    public enum CheckpointStage { ARENA, BOSS }
    public enum HazardPhase { READY, WARNING, ACTIVE, COOLDOWN, DISABLED }

    /** The caller may check geometry/object references against the loaded immutable arena registry. */
    @FunctionalInterface public interface ReferenceValidator { void validate(Checkpoint checkpoint); }

    public record Stats(long completedMatches,long wins,long losses,long draws,long totalEliminations,double totalDamage) {
        public Stats {
            nonNegative(completedMatches,"completedMatches");nonNegative(wins,"wins");nonNegative(losses,"losses");
            nonNegative(draws,"draws");nonNegative(totalEliminations,"totalEliminations");finiteNonNegative(totalDamage,"totalDamage");
            require(Math.addExact(Math.addExact(wins,losses),draws)==completedMatches,"Statistics outcomes do not total completedMatches");
        }
        static Stats empty() { return new Stats(0,0,0,0,0,0); }
    }

    public record Attempt(long sequence,UUID id,String arenaId,Mode mode,boolean fromBossCheckpoint) {
        public Attempt {
            require(sequence>0,"Attempt sequence must be positive");Objects.requireNonNull(id,"attempt.id");Objects.requireNonNull(mode,"attempt.mode");
            arena(arenaId);require((mode==Mode.LEGACY)==LEGACY_ARENA.equals(arenaId),"Legacy mode requires the legacy arena");
            require(!fromBossCheckpoint||mode==Mode.CAMPAIGN||mode==Mode.ARENA,"This mode cannot resume a boss checkpoint");
        }
    }

    /** Actual resolved game results only; this store never inspects or changes live vehicle/native state. */
    public record Result(Attempt attempt,Outcome outcome,double damageDealt,long eliminations,long activeTicks,boolean bossDefeated) {
        public Result {
            Objects.requireNonNull(attempt,"result.attempt");Objects.requireNonNull(outcome,"result.outcome");
            finiteNonNegative(damageDealt,"damageDealt");nonNegative(eliminations,"eliminations");nonNegative(activeTicks,"activeTicks");
            require(!bossDefeated||attempt.mode()!=Mode.LEGACY,"Legacy arena has no boss");
            require(!bossDefeated||outcome==Outcome.VICTORY,"Boss destruction resolves as victory, including simultaneous player death");
            require(outcome!=Outcome.VICTORY||attempt.mode()==Mode.LEGACY||bossDefeated,"A new-arena victory requires the boss defeat");
        }
    }

    public record WeaponResource(int ammunition,long cooldownTicks) {
        public WeaponResource {
            require(ammunition>=0,"Invalid ammunition");require(cooldownTicks>=0&&cooldownTicks<=Integer.MAX_VALUE,"Invalid weapon cooldown");
        }
    }

    public record ResourceTimers(long machineGunCooldown,long turboQuietTicks,long recoveryCooldown,long protectionTicks,
                                 long frozenTicks,long shieldTicks,long controlImmunityTicks,long impactStabilizerTicks) {
        public ResourceTimers {
            for(long value:new long[]{machineGunCooldown,turboQuietTicks,recoveryCooldown,protectionTicks,frozenTicks,shieldTicks,controlImmunityTicks,impactStabilizerTicks})
                require(value>=0&&value<=Integer.MAX_VALUE,"Resource timer exceeds simulation tick range");
        }
    }

    public record PlayerResources(float hp,float turbo,String selectedWeapon,Map<String,WeaponResource> weapons,
                                  Map<String,Long> abilityCooldownTicks,ResourceTimers timers) {
        public PlayerResources {
            require(Float.isFinite(hp)&&hp>0,"Checkpoint HP must be positive and finite");
            require(Float.isFinite(turbo)&&turbo>=0&&turbo<=100,"Checkpoint turbo must be in [0, 100]");
            require(WEAPONS.contains(selectedWeapon),"Unknown selectedWeapon: "+selectedWeapon);
            weapons=immutableMap(weapons);require(weapons.keySet().equals(WEAPONS),"Checkpoint must contain exactly the existing six weapons");
            abilityCooldownTicks=immutableMap(abilityCooldownTicks);
            require(abilityCooldownTicks.keySet().equals(ABILITIES),"Checkpoint must contain Freeze, Shield and Special cooldowns");
            abilityCooldownTicks.forEach((id,ticks)->require(ticks>=0&&ticks<=Integer.MAX_VALUE,"Invalid ability cooldown: "+id));
            Objects.requireNonNull(timers,"player.timers");
        }
    }

    /** Coordinates are world x/y/z in metres; yaw is radians. The runtime validates road support and full clearance. */
    public record SafePose(double x,double y,double z,double yaw,String surfaceId,String anchorId) {
        public SafePose {
            require(Float.isFinite((float)x)&&Float.isFinite((float)y)&&Float.isFinite((float)z)&&Float.isFinite((float)yaw),"Safe pose must fit finite simulation coordinates");
            identifier(surfaceId,"surfaceId");identifier(anchorId,"anchorId");
        }
    }

    public record PickupState(long respawnTicks) {
        public PickupState { nonNegative(respawnTicks,"pickup.respawnTicks"); }
    }
    public record ObjectState(float hp,boolean destroyed,boolean open) {
        public ObjectState { finiteNonNegative(hp,"object.hp");require(!destroyed||hp==0,"Destroyed object must have zero HP"); }
    }
    public record HazardState(HazardPhase phase,long remainingTicks,long cooldownTicks,long cycle) {
        public HazardState {
            Objects.requireNonNull(phase,"hazard.phase");nonNegative(remainingTicks,"hazard.remainingTicks");
            nonNegative(cooldownTicks,"hazard.cooldownTicks");nonNegative(cycle,"hazard.cycle");
        }
    }
    public record ArenaState(Map<String,PickupState> pickups,Map<String,ObjectState> objects,Map<String,HazardState> hazards,
                             long eventCooldownTicks,long randomState) {
        public ArenaState {
            pickups=immutableMap(pickups);objects=immutableMap(objects);hazards=immutableMap(hazards);
            nonNegative(eventCooldownTicks,"arena.eventCooldownTicks");
        }
    }
    public record Checkpoint(String arenaId,String profileId,int liveryId,long seed,String difficulty,CheckpointStage stage,
                             PlayerResources player,SafePose safePose,ArenaState arena,long activeTicksBeforeBoss) {
        public Checkpoint {
            campaignArena(arenaId);VehicleDefinition definition=VehicleDefinition.forId(profileId);
            require(liveryId>=0&&liveryId<5,"Unknown liveryId: "+liveryId);require("normal".equals(difficulty),"Unsupported difficulty: "+difficulty);
            Objects.requireNonNull(stage,"checkpoint.stage");Objects.requireNonNull(player,"checkpoint.player");
            require(player.hp()<=definition.maximumHp(),"Checkpoint HP exceeds this profile");
            Objects.requireNonNull(safePose,"checkpoint.safePose");Objects.requireNonNull(arena,"checkpoint.arena");
            nonNegative(activeTicksBeforeBoss,"activeTicksBeforeBoss");
        }
    }

    /** Unlocks and completed arenas are lifetime achievements; currentArenaId is the current campaign run. */
    public record Campaign(String currentArenaId,Set<String> unlockedArenaIds,Set<String> completedArenaIds,Checkpoint checkpoint) {
        public Campaign {
            unlockedArenaIds=Set.copyOf(unlockedArenaIds);completedArenaIds=Set.copyOf(completedArenaIds);
            unlockedArenaIds.forEach(ProgressStore::campaignArena);completedArenaIds.forEach(ProgressStore::campaignArena);
            require(unlockedArenaIds.contains(CAMPAIGN_ARENAS.getFirst()),"First arena must be unlocked");
            require(unlockedArenaIds.containsAll(completedArenaIds),"Completed arena must be unlocked");
            int prefix=0;while(prefix<CAMPAIGN_ARENAS.size()&&completedArenaIds.contains(CAMPAIGN_ARENAS.get(prefix))) prefix++;
            require(completedArenaIds.size()==prefix,"Completed campaign arenas must form a prefix");
            require(unlockedArenaIds.equals(Set.copyOf(CAMPAIGN_ARENAS.subList(0,Math.min(prefix+1,CAMPAIGN_ARENAS.size())))),"Unexpected unlocked arena");
            if(currentArenaId!=null) { campaignArena(currentArenaId);require(unlockedArenaIds.contains(currentArenaId),"Current arena is locked"); }
            else require(completedArenaIds.size()==CAMPAIGN_ARENAS.size(),"Unfinished campaign needs a current arena");
            if(checkpoint!=null) require(checkpoint.arenaId().equals(currentArenaId),"Checkpoint belongs to a different campaign arena");
        }
        static Campaign initial() { return new Campaign(CAMPAIGN_ARENAS.getFirst(),Set.of(CAMPAIGN_ARENAS.getFirst()),Set.of(),null); }
    }
    public record ArenaRecord(long victories,long bossVictories,Long bestFullMapTicks,Long bestBossDuelTicks) {
        public ArenaRecord {
            nonNegative(victories,"record.victories");nonNegative(bossVictories,"record.bossVictories");
            require(bossVictories<=victories,"Boss victories exceed victories");
            require(bestFullMapTicks==null||bestFullMapTicks>=0,"Invalid full-map time");require(bestBossDuelTicks==null||bestBossDuelTicks>=0,"Invalid duel time");
            require(bestFullMapTicks==null||victories>0,"A full-map record requires a victory");
            require(bestBossDuelTicks==null||bossVictories>0,"A duel record requires a boss victory");
        }
        static ArenaRecord empty() { return new ArenaRecord(0,0,null,null); }
    }
    public record Snapshot(int schemaVersion,long revision,long attemptSequence,Stats stats,Campaign campaign,
                           Attempt activeAttempt,Map<String,ArenaRecord> records) {
        public Snapshot {
            require(schemaVersion==SCHEMA_VERSION,"Unsupported progress schema");nonNegative(revision,"revision");nonNegative(attemptSequence,"attemptSequence");
            require(revision>=attemptSequence,"Attempt sequence exceeds progress revision");
            Objects.requireNonNull(stats,"stats");Objects.requireNonNull(campaign,"campaign");records=immutableMap(records);records.keySet().forEach(ProgressStore::arena);
            long recordedWins=0;for(ArenaRecord record:records.values()) recordedWins=Math.addExact(recordedWins,record.victories());
            require(recordedWins<=stats.wins(),"Arena records exceed lifetime victories");
            if(activeAttempt!=null) {
                require(activeAttempt.sequence()==attemptSequence,"Attempt sequence does not match snapshot");
                require(activeAttempt.mode()!=Mode.CAMPAIGN||activeAttempt.arenaId().equals(campaign.currentArenaId()),"Active campaign attempt is on the wrong arena");
                require(activeAttempt.mode()==Mode.LEGACY||campaign.unlockedArenaIds().contains(activeAttempt.arenaId()),"Active attempt arena is locked");
                require(activeAttempt.mode()!=Mode.BOSS_DUEL||campaign.completedArenaIds().size()==CAMPAIGN_ARENAS.size(),"Boss duels are locked");
                require(activeAttempt.mode()!=Mode.CAMPAIGN||!activeAttempt.fromBossCheckpoint()
                        ||campaign.checkpoint()!=null&&campaign.checkpoint().stage()==CheckpointStage.BOSS,"Active attempt has no boss checkpoint");
            }
        }
        static Snapshot initial() { return new Snapshot(SCHEMA_VERSION,0,0,Stats.empty(),Campaign.initial(),null,Map.of()); }
    }

    private final Path directory;
    private final ReferenceValidator references;
    private final ExecutorService writer;
    private Snapshot snapshot=Snapshot.initial(),pending;
    private long persistedRevision;
    private byte[] persistedBytes;
    private boolean workerScheduled,readOnly,closed;
    private String warning="";

    public ProgressStore() { this(SettingsStore.defaultDirectory()); }
    public ProgressStore(Path directory) { this(directory,checkpoint->{}); }
    public ProgressStore(Path directory,ReferenceValidator references) {
        this.directory=Objects.requireNonNull(directory).toAbsolutePath().normalize();this.references=Objects.requireNonNull(references);
        writer=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),task->{
            Thread thread=new Thread(task,"wreck-riff-progress-writer");thread.setDaemon(true);return thread;
        });
        load();
    }

    public synchronized Snapshot snapshot() { return snapshot; }
    public synchronized boolean writable() { return !readOnly; }
    public synchronized boolean savePending() { return snapshot.revision()>persistedRevision; }
    public record WriterDiagnostics(int pendingSnapshotCount,boolean workerScheduled,long revision,long persistedRevision) {}
    public synchronized WriterDiagnostics diagnostics() {
        return new WriterDiagnostics(pending==null?0:1,workerScheduled,snapshot.revision(),persistedRevision);
    }
    public synchronized String warning() { return warning; }
    public Path directory() { return directory; }

    public synchronized void beginNewCampaign() {
        checkOpen();Campaign previous=snapshot.campaign();
        replace(new Campaign(CAMPAIGN_ARENAS.getFirst(),previous.unlockedArenaIds(),previous.completedArenaIds(),null),null,snapshot.stats(),snapshot.records(),snapshot.attemptSequence());
    }

    public synchronized Attempt beginAttempt(String arenaId,Mode mode,boolean fromBossCheckpoint) {
        checkOpen();arena(arenaId);Objects.requireNonNull(mode,"mode");Campaign campaign=snapshot.campaign();
        if(mode!=Mode.LEGACY) require(campaign.unlockedArenaIds().contains(arenaId),"Arena is locked: "+arenaId);
        if(mode==Mode.CAMPAIGN) {
            require(arenaId.equals(campaign.currentArenaId()),"Campaign continues on "+campaign.currentArenaId());
            if(fromBossCheckpoint) require(campaign.checkpoint()!=null&&campaign.checkpoint().stage()==CheckpointStage.BOSS,"No boss checkpoint to resume");
        }
        if(mode==Mode.BOSS_DUEL) require(campaign.completedArenaIds().size()==CAMPAIGN_ARENAS.size(),"Boss duels unlock after completing the campaign");
        Attempt attempt=new Attempt(Math.incrementExact(snapshot.attemptSequence()),UUID.randomUUID(),arenaId,mode,fromBossCheckpoint);
        replace(campaign,attempt,snapshot.stats(),snapshot.records(),attempt.sequence());return attempt;
    }

    /** Called with an immutable snapshot captured by the simulation owner at the fixed-step boundary. */
    public synchronized void saveCheckpoint(Attempt attempt,Checkpoint checkpoint) {
        checkOpen();Objects.requireNonNull(checkpoint,"checkpoint");
        require(Objects.equals(snapshot.activeAttempt(),attempt),"Checkpoint belongs to a stale attempt");
        require(attempt.mode()==Mode.CAMPAIGN,"Only the campaign persists a continuation checkpoint");
        require(attempt.arenaId().equals(checkpoint.arenaId()),"Checkpoint arena differs from the active attempt");references.validate(checkpoint);
        Campaign current=snapshot.campaign();
        replace(new Campaign(current.currentArenaId(),current.unlockedArenaIds(),current.completedArenaIds(),checkpoint),attempt,snapshot.stats(),snapshot.records(),snapshot.attemptSequence());
    }

    /** Returns false for all duplicate or obsolete results, including duplicates replayed after a restart. */
    public synchronized boolean record(Result result) {
        checkOpen();Objects.requireNonNull(result,"result");if(!Objects.equals(snapshot.activeAttempt(),result.attempt())) return false;
        Stats previous=snapshot.stats();
        Stats statistics=new Stats(Math.incrementExact(previous.completedMatches()),Math.addExact(previous.wins(),result.outcome()==Outcome.VICTORY?1:0),
                Math.addExact(previous.losses(),result.outcome()==Outcome.DEFEAT?1:0),Math.addExact(previous.draws(),result.outcome()==Outcome.DRAW?1:0),
                Math.addExact(previous.totalEliminations(),result.eliminations()),previous.totalDamage()+result.damageDealt());
        Map<String,ArenaRecord> records=new LinkedHashMap<>(snapshot.records());Campaign campaign=snapshot.campaign();Attempt attempt=result.attempt();
        if(result.outcome()==Outcome.VICTORY) {
            ArenaRecord old=records.getOrDefault(attempt.arenaId(),ArenaRecord.empty());
            Long full=old.bestFullMapTicks(),duel=old.bestBossDuelTicks();
            if(attempt.mode()==Mode.BOSS_DUEL) duel=best(duel,result.activeTicks());
            else if(!attempt.fromBossCheckpoint()) full=best(full,result.activeTicks());
            records.put(attempt.arenaId(),new ArenaRecord(Math.incrementExact(old.victories()),Math.addExact(old.bossVictories(),result.bossDefeated()?1:0),full,duel));
            if(attempt.mode()==Mode.CAMPAIGN) {
                int index=CAMPAIGN_ARENAS.indexOf(attempt.arenaId());String next=index+1<CAMPAIGN_ARENAS.size()?CAMPAIGN_ARENAS.get(index+1):null;
                Set<String> completed=new HashSet<>(campaign.completedArenaIds()),unlocked=new HashSet<>(campaign.unlockedArenaIds());
                completed.add(attempt.arenaId());if(next!=null) unlocked.add(next);
                campaign=new Campaign(next,unlocked,completed,null);
            }
        }
        replace(campaign,null,statistics,records,snapshot.attemptSequence());return true;
    }

    private void replace(Campaign campaign,Attempt attempt,Stats stats,Map<String,ArenaRecord> records,long attemptSequence) {
        snapshot=new Snapshot(SCHEMA_VERSION,Math.incrementExact(snapshot.revision()),attemptSequence,stats,campaign,attempt,records);enqueueLatest();
    }
    private void checkOpen() { if(closed) throw new IllegalStateException("Progress store is closed"); }
    private void enqueueLatest() {
        if(readOnly||writer.isShutdown()||snapshot.revision()<=persistedRevision) return;
        pending=snapshot;
        if(!workerScheduled) { workerScheduled=true;writer.execute(this::drain); }
    }
    private void drain() {
        while(true) {
            Snapshot next;
            synchronized(this) {
                next=pending;pending=null;
                if(next==null) { workerScheduled=false;notifyAll();return; }
                if(next.revision()<=persistedRevision) continue;
            }
            try {
                byte[] bytes=JSON.toJson(next).getBytes(StandardCharsets.UTF_8);
                if(bytes.length>MAX_FILE_BYTES) throw new IOException("Progress snapshot exceeds size limit");
                // Validate exactly the bytes that will become authoritative before touching either valid file.
                decode(bytes);writeAtomic(bytes);
                synchronized(this) { persistedBytes=bytes;persistedRevision=next.revision();warning="";notifyAll(); }
            } catch(IOException|RuntimeException error) {
                synchronized(this) { warning="Progress was not saved; the latest state remains in memory. "+error.getMessage();notifyAll(); }
            }
        }
    }

    /** Bounded wait intended for shutdown/checkpoints in menus, never for an active physics tick. A later call retries failures. */
    public synchronized boolean flush(Duration timeout) {
        Objects.requireNonNull(timeout,"timeout");require(!timeout.isNegative(),"Negative flush timeout");
        if(readOnly) return false;
        long target=snapshot.revision(),remaining=timeout.toNanos(),deadline=System.nanoTime()+remaining;
        enqueueLatest();
        while(persistedRevision<target&&workerScheduled&&remaining>0) {
            try { TimeUnit.NANOSECONDS.timedWait(this,remaining); }
            catch(InterruptedException e) { Thread.currentThread().interrupt();return false; }
            remaining=deadline-System.nanoTime();
        }
        return persistedRevision>=target;
    }

    @Override public void close() {
        synchronized(this) { if(closed) return;closed=true; }
        flush(Duration.ofSeconds(5));writer.shutdown();
        try { if(!writer.awaitTermination(5,TimeUnit.SECONDS)) writer.shutdownNow(); }
        catch(InterruptedException e) { writer.shutdownNow();Thread.currentThread().interrupt(); }
    }

    private void load() {
        Path main=directory.resolve("stats.json"),backup=directory.resolve("stats.json.bak");
        try { Files.createDirectories(directory); }
        catch(IOException|RuntimeException e) { warning="Progress directory is unavailable; this session stays in memory.";return; }
        Loaded loaded=null;
        if(Files.exists(main)) {
            try { loaded=decode(readBytes(main)); }
            catch(FutureSchema e) { readOnly=true;warning="Unsupported stats.json version; original file preserved, progress is read-only.";return; }
            catch(IOException|RuntimeException e) {
                try { Files.move(main,directory.resolve("stats.json.broken-"+System.currentTimeMillis()+"-"+UUID.randomUUID())); }
                catch(IOException|RuntimeException preserveError) { readOnly=true;warning="Damaged progress could not be preserved; progress is read-only.";return; }
                warning="Damaged progress preserved; starting with defaults.";
            }
        }
        if(loaded==null&&Files.exists(backup)) {
            try { loaded=decode(readBytes(backup));warning="Damaged or missing progress recovered from the last valid backup."; }
            catch(FutureSchema e) { readOnly=true;warning="Unsupported backup version; original backup preserved, progress is read-only.";return; }
            catch(IOException|RuntimeException e) { warning="Progress and backup are damaged; start the map again. Original files are preserved."; }
        }
        if(loaded==null) return;
        snapshot=loaded.snapshot();persistedBytes=loaded.bytes();persistedRevision=snapshot.revision();
        if(loaded.sourceVersion()<SCHEMA_VERSION) {
            try {
                Path original=directory.resolve("stats.json.v"+loaded.sourceVersion()+".bak");
                if(!Files.exists(original)) writeForced(original,persistedBytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
                snapshot=new Snapshot(SCHEMA_VERSION,Math.incrementExact(snapshot.revision()),snapshot.attemptSequence(),snapshot.stats(),snapshot.campaign(),snapshot.activeAttempt(),snapshot.records());
                synchronized(this) { enqueueLatest(); }
            } catch(IOException|RuntimeException e) { warning="Statistics migration stays in memory; the original file is preserved.";readOnly=true; }
        }
    }

    private record Loaded(Snapshot snapshot,byte[] bytes,int sourceVersion) {}
    private static final class FutureSchema extends IOException {}

    private Loaded decode(byte[] bytes) throws IOException {
        JsonObject tree;
        try(JsonReader reader=new JsonReader(new StringReader(new String(bytes,StandardCharsets.UTF_8)))) {
            reader.setStrictness(Strictness.STRICT);JsonElement parsed=JsonParser.parseReader(reader);
            require(parsed.isJsonObject()&&reader.peek()==JsonToken.END_DOCUMENT,"Progress must be exactly one JSON object");tree=parsed.getAsJsonObject();
        }
        JsonElement schema=tree.get("schemaVersion");
        require(schema!=null&&schema.isJsonPrimitive()&&schema.getAsJsonPrimitive().isNumber(),"Missing progress schemaVersion");
        int version=schema.getAsBigDecimal().intValueExact();if(version>SCHEMA_VERSION) throw new FutureSchema();
        require(version>=1&&version<=SCHEMA_VERSION,"Unsupported progress schemaVersion");
        if(version==1) {
            Set<String> fields=Set.of("schemaVersion","completedMatches","wins","losses","draws","totalEliminations","totalDamage");
            require(fields.containsAll(tree.keySet()),"Unknown version-one statistics field");
            Stats stats=new Stats(oldLong(tree,"completedMatches"),oldLong(tree,"wins"),oldLong(tree,"losses"),oldLong(tree,"draws"),
                    oldLong(tree,"totalEliminations"),oldDouble(tree,"totalDamage"));
            return new Loaded(new Snapshot(SCHEMA_VERSION,0,0,stats,Campaign.initial(),null,Map.of()),bytes,version);
        }
        if(version==2) {
            validateTree(tree,Snapshot.class,"Snapshot",false);
            var checkpoint=tree.getAsJsonObject("campaign").get("checkpoint");
            if(!checkpoint.isJsonNull()) {
                var saved=checkpoint.getAsJsonObject();
                require("rivet".equals(saved.get("profileId").getAsString()),"Unknown version-two player profile");
                var player=saved.getAsJsonObject("player");
                player.addProperty("selectedWeapon",player.get("selectedWeapon").getAsString().toLowerCase(Locale.ROOT));
                canonicalResourceIds(player,"weapons");canonicalResourceIds(player,"abilityCooldownTicks");
                var abilities=player.getAsJsonObject("abilityCooldownTicks");
                require(abilities.keySet().equals(Set.of("freeze","shield")),"Invalid version-two abilities");
                abilities.addProperty("special",0);
            }
            tree.addProperty("schemaVersion",SCHEMA_VERSION);
        }
        validateTree(tree,Snapshot.class,"Snapshot",false);
        Snapshot decoded=JSON.fromJson(tree,Snapshot.class);
        if(decoded.campaign().checkpoint()!=null) references.validate(decoded.campaign().checkpoint());
        return new Loaded(decoded,bytes,version);
    }

    private static void canonicalResourceIds(JsonObject player,String field) {
        JsonObject canonical=new JsonObject();
        for(var entry:player.getAsJsonObject(field).entrySet()) {
            String id=entry.getKey().toLowerCase(Locale.ROOT);
            require(!canonical.has(id),"Duplicate resource ID: "+id);canonical.add(id,entry.getValue());
        }
        player.add(field,canonical);
    }

    private void writeAtomic(byte[] bytes) throws IOException {
        Files.createDirectories(directory);
        Path main=directory.resolve("stats.json"),temporary=directory.resolve("stats.json.tmp"),backup=directory.resolve("stats.json.bak"),backupTemp=directory.resolve("stats.json.bak.tmp");
        writeForced(temporary,bytes,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE);
        if(persistedBytes!=null) {
            writeForced(backupTemp,persistedBytes,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE);
            Files.move(backupTemp,backup,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }
        // No non-atomic overwrite fallback: unsupported filesystems keep the previous correct file and a visible unsaved state.
        Files.move(temporary,main,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }

    private static byte[] readBytes(Path path) throws IOException {
        if(Files.size(path)>MAX_FILE_BYTES) throw new IOException("Progress file exceeds size limit");
        try(InputStream input=Files.newInputStream(path)) {
            byte[] bytes=input.readNBytes(MAX_FILE_BYTES+1);if(bytes.length>MAX_FILE_BYTES) throw new IOException("Progress file exceeds size limit");return bytes;
        }
    }
    private static void writeForced(Path path,byte[] bytes,OpenOption... options) throws IOException {
        try(FileChannel channel=FileChannel.open(path,options)) {
            ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining()) channel.write(buffer);channel.force(true);
        }
    }
    private static long oldLong(JsonObject tree,String field) {
        if(!tree.has(field)) return 0;numeric(tree.get(field),field);return tree.get(field).getAsBigDecimal().longValueExact();
    }
    private static double oldDouble(JsonObject tree,String field) {
        if(!tree.has(field)) return 0;numeric(tree.get(field),field);return tree.get(field).getAsDouble();
    }

    /** Strict shape validation prevents Gson's missing-primitive defaults from silently erasing saved state. */
    private static void validateTree(JsonElement value,Type type,String path,boolean nullable) {
        require(value!=null,"Missing progress field "+path);
        if(value.isJsonNull()) { require(nullable,"Null progress field "+path);return; }
        if(type instanceof ParameterizedType generic) {
            if(generic.getRawType()==Map.class) {
                require(value.isJsonObject(),path+" must be an object");require(value.getAsJsonObject().size()<=MAX_ARENA_OBJECTS,path+" exceeds object limit");
                for(var entry:value.getAsJsonObject().entrySet()) validateTree(entry.getValue(),generic.getActualTypeArguments()[1],path+"."+entry.getKey(),false);
            } else {
                require(value.isJsonArray(),path+" must be an array");require(value.getAsJsonArray().size()<=MAX_ARENA_OBJECTS,path+" exceeds item limit");
                for(JsonElement entry:value.getAsJsonArray()) validateTree(entry,generic.getActualTypeArguments()[0],path+"[]",false);
            }
            return;
        }
        Class<?> cls=(Class<?>)type;
        if(cls.isRecord()) {
            require(value.isJsonObject(),path+" must be an object");Set<String> expected=new HashSet<>();
            for(RecordComponent field:cls.getRecordComponents()) {
                expected.add(field.getName());validateTree(value.getAsJsonObject().get(field.getName()),field.getGenericType(),path+"."+field.getName(),NULLABLE.contains(cls.getSimpleName()+"."+field.getName()));
            }
            require(expected.equals(value.getAsJsonObject().keySet()),"Unknown field in "+path);
        } else if(cls==String.class||cls==UUID.class||cls.isEnum()) require(value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString(),path+" must be text");
        else if(cls==boolean.class) require(value.isJsonPrimitive()&&value.getAsJsonPrimitive().isBoolean(),path+" must be boolean");
        else {
            numeric(value,path);
            if(cls==long.class||cls==Long.class) value.getAsBigDecimal().longValueExact();
            if(cls==int.class) value.getAsBigDecimal().intValueExact();
            if(cls==float.class) require(Float.isFinite(value.getAsFloat()),path+" exceeds float range");
        }
    }
    private static void numeric(JsonElement value,String field) {
        require(value.isJsonPrimitive()&&value.getAsJsonPrimitive().isNumber()&&Double.isFinite(value.getAsDouble()),field+" must be a finite number");
    }
    private static <T> Map<String,T> immutableMap(Map<String,T> source) {
        Objects.requireNonNull(source,"state map");require(source.size()<=MAX_ARENA_OBJECTS,"State map exceeds object limit");source.keySet().forEach(id->identifier(id,"state ID"));return Map.copyOf(source);
    }
    private static void identifier(String id,String name) { require(id!=null&&id.matches("[A-Za-z0-9][A-Za-z0-9_.:/-]{0,127}"),"Invalid "+name+": "+id); }
    private static void arena(String id) { require(LEGACY_ARENA.equals(id)||CAMPAIGN_ARENAS.contains(id),"Unknown arenaId: "+id); }
    private static void campaignArena(String id) { require(CAMPAIGN_ARENAS.contains(id),"Unknown campaign arenaId: "+id); }
    private static void nonNegative(long value,String name) { require(value>=0,name+" must not be negative"); }
    private static void finiteNonNegative(double value,String name) { require(Double.isFinite(value)&&value>=0,name+" must be finite and nonnegative"); }
    private static void require(boolean condition,String message) { if(!condition) throw new IllegalArgumentException(message); }
    private static Long best(Long previous,long ticks) { return previous==null?Long.valueOf(ticks):Long.valueOf(Math.min(previous,ticks)); }
}

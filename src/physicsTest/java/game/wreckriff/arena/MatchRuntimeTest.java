package game.wreckriff.arena;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.math.*;
import game.wreckriff.ai.BotController;
import game.wreckriff.config.*;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Ten complete combats using the same MatchRuntime.tick called by GameApplication. */
class MatchRuntimeTest {
    private record DriverResult(int id,float hp,int recoveries,long aliveTicks,long maximumStationaryTicks,
            float damageDealt,int eliminations,float x,float y,float z) {}
    private record BattleResult(int seed,long ticks,String outcome,String reason,float seconds,int shotEvents,
            float externalDamage,int pickups,int maximumProjectiles,float firstEncounterSeconds,float firstShotSeconds,float firstExternalDamageSeconds,
            int fatalRecoveries,List<DriverResult> drivers) {}
    private record RecoveryEpisode(int seed,int vehicleId,long tick,String cause,Vector3f before,Vector3f after,
            Vector3f forward,Vector3f velocity,float upright,Vector3f goal,List<Integer> path,
            long ticksSinceExternalDamage,String lastExternalDamage,CannonHistory lastDirectCannon) {}
    private record UpSample(long tick,String phase,long teleportGeneration,float upright,int wheelContacts,Vector3f position,
            Vector3f velocity,Vector3f angularVelocity) {}
    private record CannonHistory(long eventId,long acceptedTick,float appliedDamage,Vector3f impactPoint,
            int shieldTicks,int protectionTicks,UpSample beforeHit,long firstUpsideDownTick,
            float timeToUpsideDownSeconds,long stableSupportResetTick,boolean continuousEvidence,List<UpSample> upHistory) {}
    private record VerifiedCannonExclusion(int seed,int vehicleId,long recoveryTick,long cannonEventId,long acceptedTick,String reason) {}
    /** Observation only: no gameplay state or acceptance count is changed by attribution. */
    private static final class CannonTrace {
        final long eventId,acceptedTick;
        final float appliedDamage;
        final Vector3f point;
        final int shieldTicks,protectionTicks;
        final UpSample beforeHit;
        final List<UpSample> history=new ArrayList<>();
        long firstUpsideDownTick=-1,stableSupportResetTick=-1;
        long lastObservedTick;
        boolean continuousEvidence=true;
        int stableSupportTicks;
        CannonTrace(GameEvent impact,GameEvent damage,VehicleState state,UpSample beforeHit,long tick) {
            eventId=impact.eventId();acceptedTick=tick;appliedDamage=damage.value();point=impact.position();
            shieldTicks=state.shieldTicks;protectionTicks=state.protectionTicks;this.beforeHit=beforeHit;
            lastObservedTick=tick-1;
        }
        void observe(UpSample sample,boolean force) {
            if(stableSupportResetTick>=0||!continuousEvidence)return;
            if(sample.tick()!=lastObservedTick+1||sample.teleportGeneration()!=beforeHit.teleportGeneration()
                    ||!validSample(sample)) {continuousEvidence=false;history.add(sample);return;}
            lastObservedTick=sample.tick();
            boolean firstUpsideDown=firstUpsideDownTick<0&&sample.upright()<=-.5f;
            if(firstUpsideDown)firstUpsideDownTick=sample.tick();
            stableSupportTicks=sample.wheelContacts()==4&&sample.upright()>.85f?stableSupportTicks+1:0;
            if(stableSupportTicks>=60)stableSupportResetTick=sample.tick();
            if(force||firstUpsideDown||stableSupportResetTick>=0||history.isEmpty()
                    ||sample.tick()-history.getLast().tick()>=12)history.add(sample);
        }
        CannonHistory snapshot() {
            return new CannonHistory(eventId,acceptedTick,appliedDamage,point,shieldTicks,protectionTicks,beforeHit,
                    firstUpsideDownTick,firstUpsideDownTick<0?-1:(firstUpsideDownTick-acceptedTick)*MatchSession.DT,
                    stableSupportResetTick,continuousEvidence,List.copyOf(history));
        }
    }
    private static UpSample observePose(PhysicsWorld world,int id,long tick,String phase) {
        return new UpSample(tick,phase,world.teleportGeneration(id),world.rotation(id).mult(Vector3f.UNIT_Y).y,world.wheelContacts(id),
                world.position(id),world.velocity(id),world.containsVehicle(id)?world.vehicle(id).getAngularVelocity():Vector3f.ZERO);
    }
    private static boolean validSample(UpSample sample) {
        return sample!=null&&Float.isFinite(sample.upright())&&sample.wheelContacts()>=0&&sample.wheelContacts()<=4
                &&finite(sample.position())&&finite(sample.velocity())&&finite(sample.angularVelocity());
    }
    private static boolean finite(Vector3f value) {
        return value!=null&&Float.isFinite(value.x)&&Float.isFinite(value.y)&&Float.isFinite(value.z);
    }
    private static boolean outsideArena(Vector3f point) {return point.y < -8||Math.abs(point.x)>82||Math.abs(point.z)>72;}
    private static CannonTrace acceptedCannon(GameEvent explosion,List<GameEvent> events,VehicleState state,UpSample before,long tick) {
        if(explosion.type()!=GameEvent.Type.EXPLOSION||!explosion.kind().equals("cannon")||explosion.subjectId()!=state.id
                ||state.protectionTicks!=0||!validSample(before)||before.tick()!=tick)return null;
        GameEvent damage=events.stream().filter(e->e.type()==GameEvent.Type.DAMAGE&&e.kind().equals("cannon")
                &&e.eventId()==explosion.eventId()&&e.subjectId()==state.id&&e.sourceId()==explosion.sourceId()
                &&Float.isFinite(e.value())&&e.value()>0).findFirst().orElse(null);
        GameEvent impact=events.stream().filter(e->e.type()==GameEvent.Type.IMPACT&&e.kind().equals("cannon")
                &&e.eventId()==explosion.eventId()&&e.subjectId()==state.id&&e.sourceId()==explosion.sourceId()).findFirst().orElse(null);
        return damage==null||impact==null?null:new CannonTrace(impact,damage,state,before,tick);
    }
    private static Optional<VerifiedCannonExclusion> verifiedCannon(RecoveryEpisode episode) {
        CannonHistory trace=episode.lastDirectCannon();
        if(!episode.cause().equals("recovery")||trace==null||!trace.continuousEvidence()||trace.stableSupportResetTick()>=0
                ||trace.protectionTicks()!=0||!Float.isFinite(trace.appliedDamage())||trace.appliedDamage()<=0
                ||!validSample(trace.beforeHit())||!finite(episode.before())||trace.upHistory().isEmpty()
                ||trace.upHistory().getFirst().tick()!=trace.acceptedTick()||trace.upHistory().getLast().tick()!=episode.tick()
                ||trace.acceptedTick()>=episode.tick())return Optional.empty();
        boolean newOverturn=trace.beforeHit().upright()>0&&trace.firstUpsideDownTick()>trace.acceptedTick()
                &&trace.firstUpsideDownTick()<=episode.tick();
        boolean ejected=!outsideArena(trace.beforeHit().position())&&outsideArena(episode.before());
        if(!newOverturn&&!ejected)return Optional.empty();
        return Optional.of(new VerifiedCannonExclusion(episode.seed(),episode.vehicleId(),episode.tick(),trace.eventId(),trace.acceptedTick(),
                ejected?"arena-ejection":"new-overturn"));
    }
    private record BatchReport(int schemaVersion,String scenario,String os,String javaVersion,double wallSeconds,
            double botMinutes,int recoveries,double recoveriesPerTenBotMinutes,long maximumStationaryTicks,
            List<BattleResult> battles,List<RecoveryEpisode> recoveryEpisodes,List<VerifiedCannonExclusion> verifiedCannonExclusions,
            int unattributedCount,double unattributedRecoveriesPerTenBotMinutes,List<String> failures) {}

    @Test void tenSeededMatchesRespectNavigationAndRecoveryAcceptance() throws Exception {
        long started=System.nanoTime();
        ArenaDefinition arena=ArenaDefinition.load();VehicleRules vehicleRules=VehicleRules.load();
        var assets=NativeArenaAssets.MANAGER;
        List<BattleResult> results=new ArrayList<>();List<String> failures=new ArrayList<>();
        List<RecoveryEpisode> recoveryEpisodes=new ArrayList<>();
        long totalAliveTicks=0,maximumStationaryTicks=0;int totalRecoveries=0;
        for (int seed=0;seed<10;seed++) {
            MatchSession session=new MatchSession(seed,360);
            PhysicsWorld world=new PhysicsWorld(vehicleRules);
            ArenaContent content=new ArenaFactory(assets).build(arena);
            for (var body:content.bodies()) world.addStatic(body.shape(),body.position(),body.rotation());
            List<ArenaDefinition.Spawn> spawns=arena.shuffledSpawns(seed);
            for (var vehicle:session.vehicles) {
                var spawn=spawns.get(vehicle.id);
                world.addVehicle(vehicle.id,spawn.position().vector().add(0,.85f,0),
                        new Quaternion().fromAngleAxis(spawn.yawDegrees()*FastMath.DEG_TO_RAD,Vector3f.UNIT_Y));
            }
            try (MatchRuntime runtime=new MatchRuntime(session,world,arena,content.graph(),vehicleRules)) {
                for (int settle=0;settle<240;settle++) world.step();
                int shots=0,pickups=0,maxProjectiles=0,fatalRecoveries=0,finished=0;
                float damage=0,firstEncounter=-1,firstShot=-1,firstDamage=-1;
                long[] lastExternalTick={-1,-1,-1,-1,-1};String[] lastExternalKind={"none","none","none","none","none"};
                CannonTrace[] cannonTraces=new CannonTrace[5];
                while (session.outcome==MatchSession.Outcome.NONE && session.tick<360L*120) {
                    Vector3f[] before=new Vector3f[5],forward=new Vector3f[5],velocity=new Vector3f[5];float[] upright=new float[5];
                    UpSample[] beforeSamples=new UpSample[5];
                    for (int id=0;id<5;id++) {
                        beforeSamples[id]=observePose(world,id,session.tick,"before-step");
                        before[id]=beforeSamples[id].position();forward[id]=world.forward(id);velocity[id]=beforeSamples[id].velocity();
                        upright[id]=beforeSamples[id].upright();
                    }
                    List<GameEvent> events=runtime.tick(VehicleCommand.NONE,true);
                    // Only the terminal direct vehicle explosion plus accepted damage establishes
                    // a Cannon chain. Splash, spawn-protected hits and later MG/fire do not.
                    for(var explosion:events)if(explosion.type()==GameEvent.Type.EXPLOSION
                            &&explosion.kind().equals("cannon")&&explosion.subjectId()>=0) {
                        int id=explosion.subjectId();
                        CannonTrace accepted=acceptedCannon(explosion,events,session.vehicle(id),beforeSamples[id],session.tick-1);
                        cannonTraces[id]=accepted;
                    }
                    boolean[] recovered=new boolean[5];
                    for(var event:events)if(event.type()==GameEvent.Type.DAMAGE&&(event.kind().equals("recovery")||event.kind().equals("out-of-bounds")))recovered[event.subjectId()]=true;
                    for(int id=0;id<5;id++)if(cannonTraces[id]!=null) {
                        // Recovery teleports in prepare(), so its pre-step pose is the final
                        // physical sample; the upright replacement must not clear the chain.
                        UpSample sample=recovered[id]?beforeSamples[id]:observePose(world,id,session.tick-1,"after-step");
                        cannonTraces[id].observe(sample,recovered[id]||cannonTraces[id].acceptedTick==session.tick-1);
                    }
                    maxProjectiles=Math.max(maxProjectiles,runtime.combat().projectiles().size());
                    if (firstEncounter<0) for (int id=0;id<5;id++) {
                        if (!runtime.bots().observation(id).visible().isEmpty()) {
                            firstEncounter=runtime.bots().observation(id).tick()/120f;break;
                        }
                    }
                    for (var event:events) {
                        if (event.type()==GameEvent.Type.SHOT) { shots++;if (firstShot<0) firstShot=session.seconds(); }
                        if (event.type()==GameEvent.Type.PICKUP) pickups++;
                        if (event.type()==GameEvent.Type.MATCH_FINISHED) finished++;
                        if (event.type()==GameEvent.Type.DAMAGE && event.sourceId()>=0 && event.sourceId()!=event.subjectId()) {
                            damage+=event.value();if (firstDamage<0 && event.value()>0) firstDamage=session.seconds();
                            lastExternalTick[event.subjectId()]=session.tick-1;lastExternalKind[event.subjectId()]=event.kind();
                        }
                        if (event.type()==GameEvent.Type.DAMAGE && (event.kind().equals("recovery")||event.kind().equals("out-of-bounds"))) {
                            int id=event.subjectId();
                            recoveryEpisodes.add(new RecoveryEpisode(seed,id,session.tick-1,event.kind(),before[id],world.position(id),forward[id],velocity[id],
                                    upright[id],runtime.bots().metrics(id).destination(),runtime.bots().route(id),
                                    lastExternalTick[id]<0?-1:session.tick-1-lastExternalTick[id],lastExternalKind[id],
                                    cannonTraces[id]==null?null:cannonTraces[id].snapshot()));
                            cannonTraces[id]=null;
                        }
                        if (event.type()==GameEvent.Type.DAMAGE && event.kind().equals("out-of-bounds")) fatalRecoveries++;
                    }
                }
                List<DriverResult> drivers=new ArrayList<>();
                for (var state:session.vehicles) {
                    BotController.Metrics metrics=runtime.bots().metrics(state.id);
                    Vector3f position=world.position(state.id);
                    drivers.add(new DriverResult(state.id,state.hp,state.recoveries,metrics.aliveTicks(),
                            metrics.maximumUnplannedStationaryTicks(),state.damageDealt,state.eliminations,position.x,position.y,position.z));
                    totalAliveTicks+=metrics.aliveTicks(); totalRecoveries+=state.recoveries;
                    maximumStationaryTicks=Math.max(maximumStationaryTicks,metrics.maximumUnplannedStationaryTicks());
                    if (metrics.maximumUnplannedStationaryTicks()>600) failures.add("seed="+seed+" id="+state.id+" unplanned stationary >5 s: "+metrics.maximumUnplannedStationaryTicks());
                }
                if (session.outcome==MatchSession.Outcome.NONE) failures.add("seed="+seed+" did not finish within360game seconds");
                if (finished!=1) failures.add("seed="+seed+" emitted "+finished+" MatchFinished events");
                if (shots==0 || damage==0) failures.add("seed="+seed+" never produced actual combat damage");
                if (fatalRecoveries>0) failures.add("seed="+seed+" left the arena without a valid safe pose");
                if (maxProjectiles>64) failures.add("seed="+seed+" exceeded projectile limit");
                if (!runtime.tick(VehicleCommand.NONE,true).isEmpty()) failures.add("seed="+seed+" produced events after its final result");
                results.add(new BattleResult(seed,session.tick,session.outcome.name(),session.outcomeReason,session.seconds(),
                        shots,damage,pickups,maxProjectiles,firstEncounter,firstShot,firstDamage,fatalRecoveries,List.copyOf(drivers)));
                System.out.printf(Locale.ROOT,"AI BATCH seed=%d outcome=%s seconds=%.2f shots=%d damage=%.1f pickups=%d recoveries=%d maxStationaryTicks=%d%n",
                        seed,session.outcome,session.seconds(),shots,damage,pickups,
                        session.vehicles.stream().mapToInt(v->v.recoveries).sum(),drivers.stream().mapToLong(DriverResult::maximumStationaryTicks).max().orElse(0));
            }
        }
        double botMinutes=totalAliveTicks/(120.0*60),recoveryRate=totalRecoveries*10.0/botMinutes;
        List<VerifiedCannonExclusion> verified=recoveryEpisodes.stream().map(MatchRuntimeTest::verifiedCannon).flatMap(Optional::stream).toList();
        int unattributed=totalRecoveries-verified.size();double unattributedRate=unattributed*10.0/botMinutes;
        if(unattributed<0)failures.add("Verified Cannon exclusions exceeded the raw recovery count");
        if (unattributedRate>1) failures.add("Unattributed recovery limit exceeded: "+unattributed+" / "+botMinutes
                +" bot-minutes = "+unattributedRate+" per10bot-minutes; raw="+totalRecoveries+", verified Cannon="+verified.size());
        BatchReport report=new BatchReport(3,"seed 0..9, five AI, actual native physics and full MatchRuntime, no render",
                System.getProperty("os.name"),System.getProperty("java.version"),(System.nanoTime()-started)/1e9,
                botMinutes,totalRecoveries,recoveryRate,maximumStationaryTicks,List.copyOf(results),List.copyOf(recoveryEpisodes),verified,
                unattributed,unattributedRate,List.copyOf(failures));
        Path output=Path.of("build","reports","ai-batch.json");Files.createDirectories(output.getParent());
        Files.writeString(output,Configs.gson().toJson(report),StandardCharsets.UTF_8);
        assertTrue(failures.isEmpty(),String.join("\n",failures)+"\nSee "+output.toAbsolutePath());
    }
    private static UpSample sample(long tick,float upright,int wheels,long generation,boolean outside) {
        return new UpSample(tick,"after-step",generation,upright,wheels,new Vector3f(0,1,outside?73:0),
                new Vector3f(0,8,12),new Vector3f(0,0,4));
    }
    private static List<GameEvent> cannonEvents() {
        return List.of(new GameEvent(GameEvent.Type.EXPLOSION,100,1,0,Vector3f.ZERO,"cannon",4),
                new GameEvent(GameEvent.Type.IMPACT,100,1,0,Vector3f.ZERO,"cannon",0),
                new GameEvent(GameEvent.Type.DAMAGE,100,1,0,Vector3f.ZERO,"cannon",65));
    }
    private static CannonTrace trace(float beforeUp) {
        var state=new MatchSession(1,360).vehicle(1);var events=cannonEvents();
        CannonTrace trace=acceptedCannon(events.getFirst(),events,state,sample(0,beforeUp,0,0,false),0);
        assertNotNull(trace);return trace;
    }
    private static RecoveryEpisode episode(CannonTrace trace,long tick,boolean outside,String cause) {
        UpSample last=trace==null?sample(tick,-1,0,0,outside):trace.history.getLast();
        return new RecoveryEpisode(0,1,tick,cause,new Vector3f(0,1,outside?73:0),Vector3f.ZERO,
                Vector3f.UNIT_Z,Vector3f.ZERO,last.upright(),Vector3f.ZERO,List.of(),-1,"none",trace==null?null:trace.snapshot());
    }
    @Test void cannonAttributionAcceptsNewOverturnAndSeparatelyProvenArenaEjection() {
        CannonTrace overturned=trace(1);
        for(int tick=0;tick<=24;tick++)overturned.observe(sample(tick,tick<12?1:-.8f,0,0,false),false);
        assertEquals("new-overturn",verifiedCannon(episode(overturned,24,false,"recovery")).orElseThrow().reason());
        CannonTrace ejected=trace(1);
        for(int tick=0;tick<=24;tick++)ejected.observe(sample(tick,1,0,0,tick==24),false);
        assertEquals("arena-ejection",verifiedCannon(episode(ejected,24,true,"recovery")).orElseThrow().reason());
        // A new ejection is distinct from merely recovering an already inverted car.
        CannonTrace alreadyInvertedButNewlyEjected=trace(-1);
        for(int tick=0;tick<=24;tick++)alreadyInvertedButNewlyEjected.observe(sample(tick,-1,0,0,tick==24),false);
        assertEquals("arena-ejection",verifiedCannon(episode(alreadyInvertedButNewlyEjected,24,true,"recovery")).orElseThrow().reason());
    }
    @Test void cannonAttributionRejectsPreExistingOverturnAndLateOrdinaryNavigation() {
        for(float up:new float[]{-1,1}) {
            CannonTrace trace=trace(up);
            for(int tick=0;tick<=120;tick++)trace.observe(sample(tick,up,1,0,false),false);
            assertTrue(verifiedCannon(episode(trace,120,false,"recovery")).isEmpty());
        }
    }
    @Test void cannonAttributionEndsAfterHalfASecondOfStableFourWheelSupport() {
        CannonTrace trace=trace(1);
        for(int tick=0;tick<60;tick++)trace.observe(sample(tick,1,4,0,false),false);
        assertEquals(59,trace.stableSupportResetTick);
        for(int tick=60;tick<=120;tick++)trace.observe(sample(tick,-1,0,0,false),false);
        assertTrue(verifiedCannon(episode(trace,120,false,"recovery")).isEmpty());
    }
    @Test void cannonAttributionFailsClosedOnMissingTicksTeleportOrInvalidNativeSample() {
        for(String loss:List.of("tick-gap","teleport","invalid-pose")) {
            CannonTrace trace=trace(1);trace.observe(sample(0,1,0,0,false),false);
            long tick=loss.equals("tick-gap")?2:1;
            trace.observe(sample(tick,loss.equals("invalid-pose")?Float.NaN:-1,0,loss.equals("teleport")?1:0,false),false);
            assertFalse(trace.continuousEvidence);assertTrue(verifiedCannon(episode(trace,tick,false,"recovery")).isEmpty());
        }
        assertTrue(verifiedCannon(episode(null,24,false,"recovery")).isEmpty());
    }
    @Test void cannonAttributionRequiresMatchingAcceptedDamageAndImpactWithoutSpawnProtection() {
        var state=new MatchSession(1,360).vehicle(1);var events=cannonEvents();
        for(var omitted:List.of(GameEvent.Type.DAMAGE,GameEvent.Type.IMPACT)) {
            var incomplete=events.stream().filter(event->event.type()!=omitted).toList();
            assertNull(acceptedCannon(events.getFirst(),incomplete,state,sample(0,1,4,0,false),0));
        }
        state.protectionTicks=1;
        assertNull(acceptedCannon(events.getFirst(),events,state,sample(0,1,4,0,false),0));
    }
    @Test void cannonAttributionNeverExcludesFatalRecoveryFailure() {
        CannonTrace trace=trace(1);
        for(int tick=0;tick<=24;tick++)trace.observe(sample(tick,tick<12?1:-1,0,0,tick==24),false);
        assertTrue(verifiedCannon(episode(trace,24,true,"out-of-bounds")).isEmpty());
    }
}

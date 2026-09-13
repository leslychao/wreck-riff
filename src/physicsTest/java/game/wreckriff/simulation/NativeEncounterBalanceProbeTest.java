package game.wreckriff.simulation;

import com.jme3.math.*;
import game.wreckriff.arena.*;
import game.wreckriff.combat.*;
import game.wreckriff.config.*;
import game.wreckriff.input.VehicleCommand;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;

/** Three seeds per registered map: authored autonomous PvE, or a scripted boss weapon opening followed by AI. */
@org.junit.jupiter.api.extension.ExtendWith(game.wreckriff.arena.NativeArenaAssets.class)
class NativeEncounterBalanceProbeTest {
    private static final VehicleRules VEHICLES=VehicleRules.load();
    private static final CombatRules COMBAT=Configs.load("combat",CombatRules.class);
    private static final ArenaRegistry REGISTRY=ArenaRegistry.load();
    private static final List<Long> SEEDS=List.of(42L,73L,101L);
    private static final Path OUTPUT=Path.of("build","diagnostics","encounter-balance");
    private static final String START=System.getenv().getOrDefault("WRECK_RIFF_ENCOUNTER_START","controlled");
    private static final List<Map<String,Object>> RESULTS=new ArrayList<>();
    private static final Set<String> WEAPON_DAMAGE=Set.of("machine-gun","homing","power","mine","napalm","napalm-fire","cannon","ballistic");
    private static final Set<String> WEAPON_LAUNCH=Set.of("machine-gun","homing","power","mine","napalm","cannon","ballistic");
    static Stream<Arguments> scenarios() {
        var rows=new ArrayList<Arguments>();
        for(long seed:SEEDS)for(var entry:REGISTRY.entries())rows.add(Arguments.of(entry.id(),seed));
        return rows.stream();
    }
    @BeforeAll static void prepare() throws IOException {
        assertTrue(Set.of("authored","controlled").contains(START),"Unknown encounter starting condition");
        RESULTS.clear();Files.createDirectories(OUTPUT);
    }
    record StartingPose(Vector3f position,Quaternion rotation) {}
    private static boolean supported(ArenaDefinition arena,PhysicsWorld world,VehicleProfile profile,Vector3f surface,Quaternion rotation) {
        for(int wheel=0;wheel<4;wheel++) {
            var connection=profile.wheelConnection(wheel);connection.y=0;
            var point=surface.add(rotation.mult(connection));var support=world.support(point.add(0,1,0),2);
            if(support==null||Math.abs(support.point().y-surface.y)>.15f||support.normal().y<.9f||!arena.bounds().contains(point))return false;
        }
        return world.freeSpawnPose(profile,surface.add(0,profile.roadOffset(),0),rotation);
    }
    static StartingPose controlledStart(ArenaDefinition arena,PhysicsWorld world,VehicleProfile player) {
        var boss=arena.bosses().getFirst();var bossProfile=VehicleProfile.boss(boss.profileId(),VEHICLES);
        var entrances=new ArrayList<>(boss.entrances());entrances.addAll(arena.spawns());
        for(var spawn:entrances) {
            var surface=spawn.position().vector();var bossRotation=new Quaternion().fromAngleAxis(spawn.yawDegrees()*FastMath.DEG_TO_RAD,Vector3f.UNIT_Y);
            if(surface.y!=0||!supported(arena,world,bossProfile,surface,bossRotation))continue;
            var bossPosition=surface.add(0,bossProfile.roadOffset(),0);
            for(float distance=35;distance<=90;distance+=5)for(float angle:new float[]{0,30,-30,60,-60,90,-90,120,-120,180}) {
                var direction=new Quaternion().fromAngleAxis(angle*FastMath.DEG_TO_RAD,Vector3f.UNIT_Y).mult(bossRotation.mult(Vector3f.UNIT_Z));
                var playerSurface=surface.add(direction.mult(distance));var support=world.support(playerSurface.add(0,1,0),2);
                if(support==null||Math.abs(support.point().y)>.15f)continue;
                playerSurface.y=support.point().y;
                var rotation=new Quaternion().lookAt(direction.negate(),Vector3f.UNIT_Y);
                if(!supported(arena,world,player,playerSurface,rotation))continue;
                var position=playerSurface.add(0,player.roadOffset(),0);
                if((boss.primary()==WeaponType.CANNON||boss.secondary()==WeaponType.CANNON)
                        &&!cannonOpening(bossProfile,bossPosition,bossRotation,player,position,rotation))continue;
                if(world.ray(bossPosition.add(bossRotation.mult(bossProfile.muzzle())),position.add(0,.5f,0),-1)!=null)continue;
                return new StartingPose(position,rotation);
            }
        }
        throw new AssertionError("No supported visible starting pose near boss entrance: "+arena.id());
    }
    private static boolean cannonOpening(VehicleProfile boss,Vector3f bossPosition,Quaternion bossRotation,
            VehicleProfile player,Vector3f playerPosition,Quaternion playerRotation) {
        // Visibility alone is insufficient for a tall, fixed forward cannon: at the
        // current 80 m/s its arc clears Rivet's roof at the old 35-45 m fixture. Choose
        // a supported starting pose inside the configured
        // ballistic lane; only actual runtime DAMAGE events can pass the encounter.
        Vector3f muzzle=bossPosition.add(bossRotation.mult(boss.muzzle()));
        Vector3f forward=bossRotation.mult(Vector3f.UNIT_Z).setY(0).normalizeLocal();
        float along=playerPosition.subtract(muzzle).dot(forward),seconds=along/COMBAT.cannon().speed();
        if(seconds<=0||seconds>=COMBAT.cannon().ttlSeconds())return false;
        Vector3f point=muzzle.add(forward.mult(along)).addLocal(0,
                COMBAT.cannon().upwardSpeed()*seconds-.5f*COMBAT.cannon().gravity()*seconds*seconds,0);
        Vector3f local=playerRotation.inverse().mult(point.subtract(playerPosition));float radius=COMBAT.cannon().radius();
        return player.hullBoxes().stream().anyMatch(box->Math.abs(local.x-box.x())<=box.halfWidth()+radius
                &&Math.abs(local.y-box.y())<=box.halfHeight()+radius&&Math.abs(local.z-box.z())<=box.halfLength()+radius);
    }
    private static void save() throws IOException {
        RESULTS.sort(Comparator.comparing(row->row.get("scenario").toString()));
        Files.writeString(OUTPUT.resolve("encounters.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(RESULTS));
        if(RESULTS.isEmpty())return;
        StringBuilder csv=new StringBuilder(String.join(",",RESULTS.getFirst().keySet())).append('\n');
        for(var row:RESULTS)csv.append(String.join(",",row.values().stream().map(value->"\""+value.toString().replace("\"","\"\"")+"\"").toList())).append('\n');
        Files.writeString(OUTPUT.resolve("encounters.csv"),csv);
    }
    @AfterAll static void everyEncounterHasActualNativeWeaponCombat() throws IOException {
        save();assertEquals(REGISTRY.entries().size()*SEEDS.size(),RESULTS.size());
        for(String arena:REGISTRY.entries().stream().map(ArenaRegistry.Entry::id).toList()) {
            var rows=RESULTS.stream().filter(row->row.get("arena").equals(arena)).toList();
            assertEquals(SEEDS.size(),rows.size());
            assertTrue(rows.stream().anyMatch(row->((Number)row.get("playerWeaponDamage")).floatValue()>0),arena+" player never landed a real weapon hit");
            String damage=arena.equals("dead-air-yard")?"enemyWeaponDamage":"bossWeaponDamage";
            assertTrue(rows.stream().anyMatch(row->((Number)row.get(damage)).floatValue()>0),arena+" enemy/boss never landed a real weapon hit");
            if(!arena.equals("dead-air-yard"))assertTrue(rows.stream().anyMatch(row->((Number)row.get("bossBoundWeaponShots")).intValue()>0),arena+" boss never fired its bound arsenal");
        }
    }
    @ParameterizedTest @MethodSource("scenarios")
    void realArenaProbePreservesResourcesAndRecordsActualDriverPolicy(String arenaId,long seed) throws IOException {
        var arena=REGISTRY.definition(arenaId);boolean duel=!arena.bosses().isEmpty();
        boolean controlled=duel&&START.equals("controlled");
        var session=new MatchSession(seed,arena,duel?MatchSession.Mode.BOSS_DUEL:MatchSession.Mode.LEGACY,COMBAT);
        try(var world=new PhysicsWorld(VEHICLES)) {
            var content=new ArenaFactory(NativeArenaAssets.MANAGER).build(arena);
            for(var body:content.bodies())world.addStatic(body);
            StartingPose initialPlayer=null;
            for(var state:session.vehicles) {
                var profile=VehicleDefinition.forId(state.profileId).profile(VEHICLES);var spawn=arena.spawns().get(state.id);
                var pose=controlled&&state.id==0?controlledStart(arena,world,profile):new StartingPose(
                        spawn.position().vector().add(0,profile.roadOffset(),0),new Quaternion().fromAngleAxis(spawn.yawDegrees()*FastMath.DEG_TO_RAD,Vector3f.UNIT_Y));
                if(state.id==0)initialPlayer=pose;
                world.addVehicle(state.id,pose.position(),pose.rotation(),profile);
            }
            try(var runtime=new MatchRuntime(session,world,arena,content.graph(),VEHICLES)) {
                assertTrue(session.vehicles.stream().flatMap(v->v.weapons().stream()).allMatch(slot->slot.ammo==0));
                for(int tick=0;tick<360;tick++)world.step();
                runtime.drivers().values().forEach(driver->driver.recordSafePose(0));runtime.skipIntro();
                float damage10=0,damage30=0,playerDamage=0,enemyDamage=0,bossDamage=0,playerDamage10=0,playerDamage30=0,bossDamage10=0,bossDamage30=0;
                int bossBoundShots=0,shots=0,pickups=0,steps=0;boolean nativeBoss=false,openingArmed=false;
                var shotKinds=new TreeMap<String,Integer>();
                Set<String> bound=duel?Set.of(arena.bosses().getFirst().primary().id(),arena.bosses().getFirst().secondary().id()):Set.of();
                while(steps<180*120&&session.outcome==MatchSession.Outcome.NONE&&session.phase!=MatchSession.Phase.ERROR) {
                    if(controlled&&!openingArmed&&session.phase==MatchSession.Phase.BOSS_COMBAT) {
                        armControlledOpening(session);openingArmed=true;
                    }
                    List<GameEvent> events;
                    if(controlled&&steps<30*120) {
                        var overrides=new HashMap<Integer,VehicleCommand>();
                        overrides.put(0,steps<10*120?VehicleCommand.NONE:stationaryFire(steps%240<120?WeaponType.HOMING:WeaponType.POWER));
                        if(session.bossParticipantId>=0) {
                            var boss=arena.bosses().getFirst();
                            overrides.put(session.bossParticipantId,stationaryFire(steps%240<120?boss.primary():boss.secondary()));
                        }
                        events=runtime.tick(overrides,true);
                    } else events=runtime.tick(VehicleCommand.NONE,true);
                    steps++;
                    if(session.bossParticipantId>=0)nativeBoss|=world.containsVehicle(session.bossParticipantId);
                    for(var event:events) {
                        if((event.type()==GameEvent.Type.SHOT||event.type()==GameEvent.Type.MINE_PLACED)&&WEAPON_LAUNCH.contains(event.kind())) {
                            shots++;shotKinds.merge(event.sourceId()+":"+event.kind(),1,Integer::sum);
                            if(event.sourceId()==session.bossParticipantId&&bound.contains(event.kind()))bossBoundShots++;
                        }
                        if(event.type()==GameEvent.Type.PICKUP)pickups++;
                        if(event.type()!=GameEvent.Type.DAMAGE)continue;
                        if(steps<=10*120)damage10+=event.value();if(steps<=30*120)damage30+=event.value();
                        if(!WEAPON_DAMAGE.contains(event.kind()))continue;
                        if(event.sourceId()==0&&event.subjectId()!=0) {
                            playerDamage+=event.value();if(steps<=10*120)playerDamage10+=event.value();if(steps<=30*120)playerDamage30+=event.value();
                        }
                        if(event.sourceId()>0&&event.subjectId()!=event.sourceId())enemyDamage+=event.value();
                        if(event.sourceId()==session.bossParticipantId&&event.subjectId()==0) {
                            bossDamage+=event.value();if(steps<=10*120)bossDamage10+=event.value();if(steps<=30*120)bossDamage30+=event.value();
                        }
                    }
                }
                var row=new LinkedHashMap<String,Object>();row.put("scenario",arenaId+"/"+seed);row.put("arena",arenaId);row.put("seed",seed);
                row.put("layoutRevision",arena.layoutRevision());
                row.put("mode",session.mode.name());row.put("start",controlled?"controlled-visible-entrance":"authored");
                row.put("driver",controlled?"one-explicit-full-arsenal-fixture-after-empty-spawn; 30s-scripted-stationary-bound-weapon-rotation-player-fires-after-10s-then-existing-ai-both":"existing-ai-player");
                row.put("startingPosePolicy",controlled?"supported-visible-35-to-90m; cannon requires its configured ballistic lane to intersect player hull":"authored");
                row.put("initialPlayerPosition",initialPlayer.position().toString());row.put("initialPlayerRotation",initialPlayer.rotation().toString());
                row.put("seconds",steps/120f);row.put("activeSeconds",session.seconds());
                row.put("outcome",session.outcome.name());row.put("reason",session.outcomeReason);row.put("phase",session.phase.name());row.put("timeout",session.outcome==MatchSession.Outcome.NONE&&steps>=180*120);
                row.put("damage10",damage10);row.put("damage30",damage30);row.put("playerWeaponDamage",playerDamage);row.put("playerWeaponDamage10",playerDamage10);row.put("playerWeaponDamage30",playerDamage30);
                row.put("enemyWeaponDamage",enemyDamage);row.put("bossWeaponDamage",bossDamage);row.put("bossWeaponDamage10",bossDamage10);row.put("bossWeaponDamage30",bossDamage30);
                row.put("bossBoundWeaponShots",bossBoundShots);row.put("shots",shots);row.put("shotKinds",shotKinds);row.put("pickups",pickups);row.put("nativeBoss",nativeBoss);
                row.put("hp",session.vehicles.stream().map(v->v.hp).toList());row.put("maximumHp",session.vehicles.stream().map(v->v.maximumHp).toList());
                row.put("ammoRemaining",session.vehicles.stream().map(v->v.weapons().stream().map(w->w.ammo).toList()).toList());
                RESULTS.add(row);save();System.out.println("ENCOUNTER_PROBE "+new com.google.gson.Gson().toJson(row));
                assertNotEquals(MatchSession.Phase.ERROR,session.phase,session.outcomeReason);
                assertEquals(800,session.vehicle(0).maximumHp);if(duel)assertTrue(nativeBoss,"Runtime must spawn the actual boss body");
                assertTrue(shots>0,"No real weapon launches in "+arenaId+"/"+seed);
            }
        }
    }
    static VehicleCommand stationaryFire(WeaponType weapon) {
        return new VehicleCommand(0,0,0,true,false,true,true,weapon,0,false,false,AbilityId.NONE);
    }
    /** One explicit test loadout exercises firing lanes; the authored probe never receives it. */
    static void armControlledOpening(MatchSession session) {
        assertEquals(MatchSession.Phase.BOSS_COMBAT,session.phase);
        for(var state:session.vehicles)for(var slot:state.weapons())slot.ammo=slot.maximumAmmo;
    }
}

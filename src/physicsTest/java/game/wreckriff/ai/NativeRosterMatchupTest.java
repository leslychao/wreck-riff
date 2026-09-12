package game.wreckriff.ai;

import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import game.wreckriff.arena.ArenaDefinition;
import game.wreckriff.arena.NavGraph;
import game.wreckriff.combat.AbilityId;
import game.wreckriff.combat.CombatRules;
import game.wreckriff.combat.WeaponType;
import game.wreckriff.config.Configs;
import game.wreckriff.config.VehicleDefinition;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.GameEvent;
import game.wreckriff.simulation.MatchRuntime;
import game.wreckriff.simulation.MatchSession;
import game.wreckriff.simulation.PhysicsWorld;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Bounded AI/native matchup regressions. A parked equal-HP rival isolates the melee approach, not win rate. */
class NativeRosterMatchupTest {
    @ParameterizedTest @ValueSource(strings={"rivet","grinder","spark"})
    void grinderBotActuallyReachesCapturesAndDamagesEachNativeChassis(String targetProfile) {
        try(var fight=new Fight(targetProfile)) {
            assertAiCapture(fight,targetProfile);
        }
    }

    @Test void grinderBotWithRangedArsenalUnavailableStillCaptures() {
        try(var fight=new Fight("rivet")) {
            // Legitimate depleted/on-cooldown resources isolate navigation without rewriting any AI command.
            for(var weapon:fight.session.vehicle(0).weapons())weapon.ammo=0;
            fight.session.vehicle(0).machineGunCooldown=10_000;
            fight.session.vehicle(0).abilityCooldown(AbilityId.FREEZE,10_000);
            fight.arsenal="depleted; MG and Freeze on cooldown";
            assertAiCapture(fight,"rivet with ranged attacks unavailable");
        }
    }

    @Test void grinderBotUsesMachineGunThroughTheHoldAndSavesPowerForItsFinish() {
        try(var fight=new Fight("rivet")) {
            for(var weapon:fight.session.vehicle(0).weapons())assertTrue(weapon.ammo>0,
                    "The production bot must choose its combo with the full selectable arsenal available");
            assertAiCapture(fight,"rivet with full arsenal for the complete hold");
            long latePowerId=-1;
            int heldPowerShots=0;
            float heldMachineGunDamage=0,powerDamage=0;
            // At most 1.7 s after the existing, bounded six-second capture approach.
            // Observe real commands and their events; never force a weapon or alter a cooldown.
            for(int tick=0;tick<204;tick++) {
                var truck=fight.session.vehicle(0);
                boolean heldBefore=truck.grinding()&&fight.session.vehicle(1).grabbedBy==truck.id;
                int remainingBefore=truck.specialTicks;
                List<GameEvent> events=fight.tick();
                for(var event:events) {
                    if(event.type()!=GameEvent.Type.SHOT||event.sourceId()!=0)continue;
                    boolean selectedWeapon=false;
                    for(var type:WeaponType.values())selectedWeapon|=type.id().equals(event.kind());
                    if(heldBefore&&remainingBefore>24)assertFalse(selectedWeapon,
                            ()->"The bot must preserve at least the first 1.3 s of its hold instead of lifting the target with an early explosive; pre-tick remaining="
                                    +remainingBefore+fight.trace());
                    if(heldBefore&&event.kind().equals("power")) {
                        assertTrue(remainingBefore>0&&remainingBefore<=24,
                                ()->"Power must finish an existing hold, after sustained contact damage"+fight.trace());
                        heldPowerShots++;latePowerId=event.eventId();
                    }
                }
                for(var event:events) {
                    if(event.type()!=GameEvent.Type.DAMAGE||event.sourceId()!=0||event.subjectId()!=1)continue;
                    if(heldBefore&&event.kind().equals("machine-gun"))heldMachineGunDamage+=event.value();
                    if(event.eventId()==latePowerId&&event.kind().equals("power"))powerDamage+=event.value();
                }
            }
            assertTrue(heldMachineGunDamage>0,()->"The bot's roof machine gun must actually damage the held native hull"+fight.trace());
            assertEquals(1,heldPowerShots,()->"The bot must finish its sustained hold with one actual Power launch"+fight.trace());
            long finishingShot=latePowerId;
            assertTrue(powerDamage>0||fight.runtime.combat().projectiles().stream().anyMatch(projectile->projectile.id()==finishingShot),
                    ()->"The finishing Power must reach the target or remain a real projectile in its flight tail"+fight.trace());
        }
    }

    private static void assertAiCapture(Fight fight,String scenario) {
        boolean activated=false,captured=false;float grinderDamage=0;
        for(int tick=0;tick<6*MatchSession.TICKS_PER_SECOND&&grinderDamage==0;tick++) {
            for(var event:fight.tick()) {
                activated|=event.type()==GameEvent.Type.SPECIAL_STARTED&&event.sourceId()==0;
                captured|=event.type()==GameEvent.Type.GRAB_STARTED&&event.sourceId()==0&&event.subjectId()==1;
                if(event.type()==GameEvent.Type.DAMAGE&&event.sourceId()==0&&event.subjectId()==1&&"grinder".equals(event.kind()))grinderDamage+=event.value();
            }
            assertTrue(fight.session.vehicle(0).alive()&&fight.session.vehicle(1).alive(),()->"Both participants must survive long enough to test the approach"+fight.trace());
        }
        assertTrue(activated,()->"The full bot policy must request Grinder's special against "+scenario+fight.trace());
        assertTrue(captured,()->"A ready melee bot must capture its visible stationary target, not repeatedly route around it: "+scenario+fight.trace());
        assertTrue(grinderDamage>0,()->"Native contact must reach common damage resolution against "+scenario+fight.trace());
        assertEquals(0,fight.session.vehicle(1).grabbedBy);assertEquals(1,fight.world.grabCount());
        assertTrue(fight.world.touchingVehicles(0,1),"A reported capture requires an actual Bullet contact");
        assertTrue(fight.session.vehicle(1).hp<fight.session.vehicle(1).maximumHp);
    }

    @Test void anActiveGrinderBotStillAvoidsARealWallThatAppearsBeforeItsSelectedTarget() {
        try(var fight=new Fight("rivet")) {
            boolean activated=false;
            for(int tick=0;tick<2*MatchSession.TICKS_PER_SECOND&&!activated;tick++)
                activated=fight.tick().stream().anyMatch(event->event.type()==GameEvent.Type.SPECIAL_STARTED&&event.sourceId()==0);
            assertTrue(activated,()->"The obstacle scenario must start with a real AI-activated special"+fight.trace());
            assertTrue(fight.session.vehicle(0).specialActive());
            float wallNear=fight.world.position(0).z+8;
            fight.world.addStatic("melee-blocker",new BoxCollisionShape(new Vector3f(200,3,.5f)),new Vector3f(0,3,wallNear+.5f),new Quaternion());
            boolean usedAvoidance=false;
            for(int tick=0;tick<3*MatchSession.TICKS_PER_SECOND;tick++) {
                List<GameEvent> events=fight.tick();
                usedAvoidance|=fight.lastBotCommand.brakeReverse()>0||Math.abs(fight.lastBotCommand.steer())>.2f;
                assertFalse(events.stream().anyMatch(event->event.type()==GameEvent.Type.GRAB_STARTED&&event.sourceId()==0),()->"A target-only melee exception cannot grab through a wall"+fight.trace());
                assertFalse(events.stream().anyMatch(event->event.type()==GameEvent.Type.DAMAGE&&"grinder".equals(event.kind())),()->"No grinder damage may cross the intervening static hull"+fight.trace());
                assertTrue(fight.world.arenaContacts().stream().noneMatch(contact->contact.vehicleId()==0&&contact.objectId().equals("melee-blocker")),
                        ()->"The melee approach must keep the driver's real static-obstacle avoidance"+fight.trace());
                assertTrue(fight.world.position(0).z<wallNear,()->"The native chassis cannot tunnel to the target side"+fight.trace());
            }
            assertTrue(usedAvoidance,()->"The actual AI commands must respond to the new blocker"+fight.trace());
            assertEquals(-1,fight.session.vehicle(1).grabbedBy);assertEquals(0,fight.world.grabCount());
        }
    }

    private static final class Fight implements AutoCloseable {
        final VehicleRules vehicleRules=VehicleRules.load();
        final MatchSession session;
        final PhysicsWorld world;
        final MatchRuntime runtime;
        private final List<String> frames=new ArrayList<>();
        private final Map<String,Integer> intervalEvents=new LinkedHashMap<>();
        private final Map<String,Float> intervalDamage=new LinkedHashMap<>();
        private long lastSnapshotTick=-1;
        String arsenal="full";
        VehicleCommand lastBotCommand=VehicleCommand.NONE;
        Fight(String targetProfile) {
            session=MatchSession.balanced(42,List.of("grinder",targetProfile),Configs.load("combat",CombatRules.class));
            world=new PhysicsWorld(vehicleRules);
            world.addStatic("floor",new BoxCollisionShape(new Vector3f(200,.5f,200)),new Vector3f(0,-.5f,0),new Quaternion());
            for(var state:session.vehicles) {
                var profile=VehicleDefinition.forId(state.profileId).profile(vehicleRules);
                world.addVehicle(state.id,new Vector3f(0,profile.roadOffset()+.5f,state.id==0?-18:0),new Quaternion(),profile);
                assertEquals(VehicleDefinition.forId(state.profileId).maximumHp(),state.maximumHp,"The fixture must not apply the campaign bot HP handicap");
            }
            for(int tick=0;tick<240;tick++)world.step();
            assertEquals(4,world.supportedWheelContacts(0));assertEquals(4,world.supportedWheelContacts(1));
            ArenaDefinition arena=openArena();runtime=new MatchRuntime(session,world,arena,new NavGraph(arena),vehicleRules);
            snapshot();
        }
        List<GameEvent> tick() {
            // Observe unchanged production AI commands, then send them through the real runtime once.
            var commands=new HashMap<>(runtime.bots().commands(world));lastBotCommand=commands.get(0);
            commands.put(1,VehicleCommand.NONE);
            List<GameEvent> events=runtime.tick(commands,false);
            for(var event:events) {
                String key=event.kind()+"["+event.sourceId()+"->"+event.subjectId()+"]";
                if(event.type()==GameEvent.Type.DAMAGE)intervalDamage.merge(key,event.value(),Float::sum);
                else if(switch(event.type()) {
                    case SHOT, SPECIAL_STARTED, SPECIAL_ENDED, GRAB_STARTED, CONTROL_ENDED, FREEZE, SHIELD -> true;
                    default -> false;
                })intervalEvents.merge(event.type()+":"+key,1,Integer::sum);
            }
            if(session.tick%30==0)snapshot();
            return events;
        }
        private void snapshot() {
            if(frames.size()>=32||lastSnapshotTick==session.tick)return;
            Vector3f intake=world.position(0).add(world.rotation(0).mult(world.profile(0).grinderIntake()));
            Vector3f contact=world.rotation(0).inverse().mult(world.closestHullPoint(1,intake).subtract(world.position(0)));
            frames.add(String.format(Locale.ROOT,
                    "t=%.2f tick=%d actor{%s} target{%s} nativeContact=%s intakeGap=%.2f contactLocal=%s brain=%s targetId=%d route=%s command=%s events=%s damage=%s",
                    session.tick/(float)MatchSession.TICKS_PER_SECOND,session.tick,participant(0),participant(1),world.touchingVehicles(0,1),
                    world.distanceToHull(1,intake),vector(contact),runtime.bots().state(0),runtime.bots().targetId(0),
                    runtime.bots().route(0).stream().limit(8).toList(),lastBotCommand,intervalEvents,intervalDamage));
            lastSnapshotTick=session.tick;intervalEvents.clear();intervalDamage.clear();
        }
        private String participant(int id) {
            var state=session.vehicle(id);
            return String.format(Locale.ROOT,"id=%d profile=%s p=%s v=%s fwd=%s hp=%.1f phase=%s/%d specialTarget=%d frozen=%d immune=%d shield=%d grabbedBy=%d wheels=%d weapon=%s",
                    id,state.profileId,vector(world.position(id)),vector(world.velocity(id)),vector(world.forward(id)),state.hp,state.specialPhase,
                    state.specialTicks,state.specialTargetId,state.frozenTicks,state.controlImmunityTicks,state.shieldTicks,state.grabbedBy,
                    world.supportedWheelContacts(id),state.selectedWeapon);
        }
        private static String vector(Vector3f value) {return String.format(Locale.ROOT,"(%.2f,%.2f,%.2f)",value.x,value.y,value.z);}
        String trace() {
            snapshot();
            return "\nNative capture trace (arsenal="+arsenal+", 0.25 s samples, at most 32):\n"+String.join("\n",frames);
        }
        @Override public void close() {runtime.close();}
    }

    private static ArenaDefinition openArena() {
        var base=ArenaDefinition.load();
        var floor=new ArenaDefinition.BoxPart("floor",new ArenaDefinition.Vec3(0,-.5f,0),new ArenaDefinition.Vec3(400,1,400),"concrete",true);
        var nodes=new ArrayList<ArenaDefinition.NavNode>();var edges=new ArrayList<ArenaDefinition.NavEdge>();
        for(int id=0;id<9;id++) {
            nodes.add(new ArenaDefinition.NavNode(id,new ArenaDefinition.Vec3(0,0,-80+id*20),"road-ground"));
            if(id>0)edges.add(new ArenaDefinition.NavEdge("open-"+id,id-1,id,20,24,ArenaDefinition.Transition.ROAD,"",true));
        }
        return new ArenaDefinition(base.schemaVersion(),"roster-contact-yard",base.metadata(),new ArenaDefinition.Bounds(-200,200,-200,200,-8),
                List.of(floor),List.of(),base.spawns(),List.of(),List.of(),nodes,edges,
                List.of(new ArenaDefinition.Surface("road-ground","floor",0,1)),List.of(),List.of(),List.of(),List.of(),List.of(),List.of());
    }
}

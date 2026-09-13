package game.wreckriff.combat;

import com.google.gson.GsonBuilder;
import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.math.*;
import game.wreckriff.arena.ArenaDefinition;
import game.wreckriff.config.*;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.*;
import game.wreckriff.vehicle.VehicleController;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** Real hulls and driver commands: no projectile injection or motion writes during the attack. */
class NativeFreezeBallisticComboTest {
    private static final VehicleRules VEHICLES=VehicleRules.load();
    private static final List<Map<String,Object>> EVIDENCE=new ArrayList<>();
    private static final VehicleCommand DRIVE=new VehicleCommand(.3f,0,0,false,false,false,false,null,0,false,false,AbilityId.NONE);
    private static Stream<Arguments> cases() {
        return Arrays.stream(VehicleDefinition.values()).flatMap(vehicle->Stream.of(15,35,55).map(distance->Arguments.of(vehicle,distance)));
    }
    @ParameterizedTest(name="{0} at {1} m") @MethodSource("cases")
    void fourChargesDamageTheMovingTargetBeforeItsRealFreezeExpires(VehicleDefinition definition,int distance)throws Exception {
        var session=MatchSession.balanced(42,List.of("rivet",definition.id()),Configs.load("combat",CombatRules.class));
        NativeCombatSupplies.halfLoad(session);
        var combat=new CombatSystem(session,session.combatRules);
        try(var world=new PhysicsWorld(VEHICLES)) {
            world.addStatic("road",new BoxCollisionShape(new Vector3f(150,.5f,150)),new Vector3f(0,-.5f,0),new Quaternion());
            for(var state:session.vehicles) {
                var profile=VehicleDefinition.forId(state.profileId).profile(VEHICLES);
                world.addVehicle(state.id,new Vector3f(0,profile.roadOffset()+.3f,state.id==0?0:distance),
                        state.id==0?new Quaternion():new Quaternion().fromAngleAxis(FastMath.HALF_PI,Vector3f.UNIT_Y),profile);
            }
            for(int tick=0;tick<360;tick++)world.step();
            var driver=new VehicleController(world,session.vehicle(1),VEHICLES,new ArenaDefinition.Bounds(-150,150,-150,150,-8));
            for(int tick=0;tick<60;tick++) {driver.drive(DRIVE);world.step();}
            assertTrue(world.velocity(1).length()>.25f,"Freeze must intercept an actually moving hull");
            long frozenAt=-1,ballisticAt=-1,endedAt=-1,firstHit=-1,lastHit=-1;
            Set<Long> damagingCharges=new HashSet<>();
            float totalDamage=0;
            for(int step=0;step<720;step++) {
                VehicleCommand attack=VehicleCommand.NONE;
                if(step==0)attack=new VehicleCommand(0,0,0,false,false,false,false,WeaponType.BALLISTIC,0,false,false,AbilityId.FREEZE);
                if(frozenAt>=0&&session.tick==frozenAt+30) {
                    ballisticAt=session.tick;
                    attack=new VehicleCommand(0,0,0,false,false,false,true,WeaponType.BALLISTIC,0,false,false,AbilityId.NONE);
                }
                combat.beginTick(Map.of(0,attack,1,DRIVE),world);
                assertFalse(driver.prepare(DRIVE,session.tick).recovered());driver.drive(DRIVE);
                world.step();combat.advanceProjectiles(world);combat.resolveDamage(world);
                for(var event:combat.drainEvents()) {
                    if(event.type()==GameEvent.Type.FREEZE&&event.subjectId()==1) {
                        assertEquals(-1,frozenAt,"Exactly one real freeze hit");frozenAt=session.tick;
                        assertEquals(480,session.vehicle(1).frozenTicks);
                    }
                    if(event.type()==GameEvent.Type.DAMAGE&&event.kind().equals("ballistic")&&event.subjectId()==1&&event.sourceId()==0) {
                        assertTrue(session.vehicle(1).frozenTicks>0,"Every damaging charge must arrive during Freeze");
                        assertTrue(damagingCharges.add(event.eventId()),"A charge cannot damage the hull twice");
                        if(firstHit<0)firstHit=session.tick;lastHit=session.tick;totalDamage+=event.value();
                    }
                    if(event.type()==GameEvent.Type.CONTROL_ENDED&&event.kind().equals("freeze")&&event.subjectId()==1)endedAt=session.tick;
                }
                MatchRuntime.finishTick(session);
            }
            assertTrue(frozenAt>=0,"Freeze projectile must reach the target");
            assertEquals(4,damagingCharges.size(),"The complete salvo must hit "+definition+" at "+distance+" m");
            assertEquals(480,endedAt-frozenAt,"Four seconds of authoritative physical control");
            assertEquals(30,ballisticAt-frozenAt);
            float firstSeconds=(firstHit-ballisticAt)*MatchSession.DT,lastSeconds=(lastHit-ballisticAt)*MatchSession.DT;
            assertTrue(firstSeconds>1.8f&&firstSeconds<2.9f,"Native first-hit time: "+firstSeconds);
            assertTrue(lastSeconds>2.65f&&lastSeconds<3.75f,"Native last-hit time: "+lastSeconds);
            assertTrue(lastHit<endedAt);assertEquals(0,world.teleportGeneration(1));
            assertEquals(0,combat.occupiedProjectileSlots());assertTrue(combat.ballisticWarnings().isEmpty());
            EVIDENCE.add(Map.of("vehicle",definition.id(),"distanceMetres",distance,"freezeTicks",endedAt-frozenAt,
                    "delayAfterFreezeSeconds",.25,"firstImpactSeconds",firstSeconds,"lastImpactSeconds",lastSeconds,
                    "damagingCharges",damagingCharges.size(),"damage",totalDamage,"teleports",world.teleportGeneration(1)));
        } finally {combat.clear();}
    }
    @AfterAll static void writeEvidence()throws Exception {
        Path output=Path.of("build/reports/freeze-ballistic/native-combo.json");Files.createDirectories(output.getParent());
        Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("method",
                "Bundled rules, native hulls, normal driver/ability/weapon commands. Target already moving before Freeze, Ballistic 30 ticks after actual Freeze impact.","cases",EVIDENCE))+"\n");
    }
}

package game.wreckriff.combat;

import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.math.*;
import game.wreckriff.arena.ArenaDefinition;
import game.wreckriff.config.VehicleDefinition;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.*;
import game.wreckriff.vehicle.VehicleController;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Reachable evasions use the real controller, suspension and gravity, without transform/velocity writes. */
class NativeBallisticGuidanceTest {
    private static final VehicleRules VEHICLES=VehicleRules.load();
    private static final VehicleCommand GAS=command(1,0,0,false,AbilityId.NONE);
    private static VehicleCommand command(float gas,float brake,float steer,boolean turbo,AbilityId ability) {
        return new VehicleCommand(gas,brake,steer,false,turbo,false,false,null,0,false,false,ability);
    }
    private record Result(float damage,float travelled,int explosions,long teleportGeneration) {}

    @ParameterizedTest @EnumSource(VehicleDefinition.class)
    void stationaryHullIsHitButOrdinaryTurnTurboAndBrakingCanReduceTheSameSalvo(VehicleDefinition definition) {
        Result stationary=salvo(definition,"stationary",false);
        assertTrue(stationary.damage>20,"Stationary "+definition+" must remain a useful target");
        for(String manoeuvre:List.of("turn","turbo","brake")) {
            Result moving=salvo(definition,manoeuvre,false);
            assertEquals(4,moving.explosions);assertEquals(0,moving.teleportGeneration);
            assertTrue(moving.travelled>5,"The native controller must execute "+manoeuvre+" for "+definition);
            assertTrue(moving.damage<stationary.damage*.5f,definition+" "+manoeuvre+" damage="+moving.damage+" stationary="+stationary.damage);
        }
    }
    @Test void sparkCanEvadeWithItsActualSpecialDash() {
        Result stationary=salvo(VehicleDefinition.SPARK,"stationary",false),dash=salvo(VehicleDefinition.SPARK,"dash",false);
        assertTrue(dash.travelled>5);assertEquals(0,dash.teleportGeneration);
        assertTrue(dash.damage<stationary.damage*.5f,"A real lateral dash must defeat committed artillery");
    }
    @ParameterizedTest @EnumSource(VehicleDefinition.class)
    void realRoofStopsTheSalvoAndProtectsEveryPlayableHull(VehicleDefinition definition) {
        Result roof=salvo(definition,"stationary",true);assertEquals(4,roof.explosions);assertEquals(0,roof.damage);
    }
    @ParameterizedTest @EnumSource(VehicleDefinition.class)
    void realWallStopsTheCarrierBeforeItCanReleaseAnyCharges(VehicleDefinition definition) {
        Result wall=salvo(definition,"wall",false);assertEquals(1,wall.explosions);assertEquals(0,wall.damage);
    }
    private Result salvo(VehicleDefinition definition,String manoeuvre,boolean roof) {
        var session=MatchSession.balanced(42,List.of("rivet",definition.id()),game.wreckriff.config.Configs.load("combat",CombatRules.class));
        NativeCombatSupplies.halfLoad(session);
        var combat=new CombatSystem(session,session.combatRules);
        try(var world=new PhysicsWorld(VEHICLES)) {
            world.addStatic("road",new BoxCollisionShape(new Vector3f(400,.5f,400)),new Vector3f(0,-.5f,0),new Quaternion());
            for(var state:session.vehicles) {
                var profile=VehicleDefinition.forId(state.profileId).profile(VEHICLES);
                world.addVehicle(state.id,new Vector3f(0,profile.roadOffset()+.3f,state.id==0?0:32),new Quaternion(),profile);
            }
            if(roof)world.addStatic("roof",new BoxCollisionShape(new Vector3f(15,.25f,15)),new Vector3f(0,6,32),new Quaternion());
            if(manoeuvre.equals("wall"))world.addStatic("wall",new BoxCollisionShape(new Vector3f(20,20,.25f)),new Vector3f(0,20,16),new Quaternion());
            for(int tick=0;tick<360;tick++)world.step();
            var driver=new VehicleController(world,session.vehicle(1),VEHICLES,new ArenaDefinition.Bounds(-400,400,-400,400,-8));
            if(manoeuvre.equals("brake"))for(int tick=0;tick<120;tick++) {driver.drive(GAS);world.step();}
            Vector3f start=world.position(1);boolean warned=false,dashed=false;float damage=0;int warnedAt=-1;
            Set<Long> explosions=new HashSet<>();
            for(int tick=0;tick<960;tick++) {
                warned|=!combat.ballisticWarnings().isEmpty();
                if(warned&&warnedAt<0)warnedAt=tick;
                VehicleCommand movement=warned?switch(manoeuvre) {
                    case "turn"->command(1,0,tick-warnedAt<90?1:0,false,AbilityId.NONE);
                    case "turbo"->command(1,0,0,true,AbilityId.NONE);
                    case "brake"->command(0,1,0,false,AbilityId.NONE);
                    case "dash"->command(0,0,1,false,dashed?AbilityId.NONE:AbilityId.SPECIAL);
                    default->VehicleCommand.NONE;
                }:manoeuvre.equals("brake")?GAS:VehicleCommand.NONE;
                if(movement.ability()==AbilityId.SPECIAL)dashed=true;
                var fire=new VehicleCommand(0,0,0,false,false,false,true,WeaponType.BALLISTIC,0,false,false,AbilityId.NONE);
                combat.beginTick(tick==0?Map.of(0,fire,1,movement):Map.of(1,movement),world);
                assertFalse(driver.prepare(movement,session.tick).recovered());driver.drive(movement);
                world.step();combat.advanceProjectiles(world);combat.resolveDamage(world);
                for(var event:combat.drainEvents()) {
                    if(event.type()==GameEvent.Type.EXPLOSION&&event.kind().equals("ballistic"))explosions.add(event.eventId());
                    if(event.type()==GameEvent.Type.DAMAGE&&event.kind().equals("ballistic")&&event.sourceId()==0&&event.subjectId()==1)damage+=event.value();
                }
                MatchRuntime.finishTick(session);
            }
            assertEquals(!manoeuvre.equals("wall"),warned);assertEquals(0,combat.occupiedProjectileSlots());assertTrue(combat.ballisticWarnings().isEmpty());
            return new Result(damage,world.position(1).subtract(start).setY(0).length(),explosions.size(),world.teleportGeneration(1));
        }
    }
}

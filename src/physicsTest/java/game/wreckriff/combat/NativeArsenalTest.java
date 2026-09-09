package game.wreckriff.combat;

import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.math.*;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeArsenalTest {
    private final MatchSession session=new MatchSession(31,360);
    private final CombatSystem combat=new CombatSystem(session,session.combatRules);
    private PhysicsWorld world() {
        var world=new PhysicsWorld(VehicleRules.load());
        world.addStatic(new BoxCollisionShape(new Vector3f(100,.5f,100)),new Vector3f(0,-.5f,0),new Quaternion());
        for(int id=0;id<5;id++)world.addVehicle(id,new Vector3f(id==0?0:60+id*5,1,id==0?0:50),new Quaternion());
        for(int tick=0;tick<240;tick++)world.step();
        return world;
    }
    private List<GameEvent> tick(PhysicsWorld world,Map<Integer,VehicleCommand> commands) {
        combat.beginTick(commands,world);world.step();combat.advanceProjectiles(world);combat.resolveDamage(world);game.wreckriff.simulation.MatchRuntime.finishTick(session);return combat.drainEvents();
    }
    private static VehicleCommand ability(AbilityId ability) {return new VehicleCommand(0,0,0,false,false,false,false,null,0,false,false,ability);}
    private static VehicleCommand fire() {return new VehicleCommand(0,0,0,false,false,false,true,null,0,false,false,AbilityId.NONE);}
    @Test void realFreezeBoltHitsCompoundHullAndMaintainsExactly240ConstrainedSteps() {
        try(var world=world()) {
            world.teleport(1,new Vector3f(0,.5f,8),new Quaternion());
            tick(world,Map.of(0,ability(AbilityId.FREEZE)));
            for(int tick=0;tick<30&&session.vehicle(1).frozenTicks==0;tick++)tick(world,Map.of());
            assertEquals(240,session.vehicle(1).frozenTicks);assertEquals(1,world.immobilizerCount());assertEquals(400,session.vehicle(1).hp);
            Vector3f start=world.position(1);
            for(int tick=0;tick<239;tick++) {
                world.impulse(1,new Vector3f(1000,0,0),Vector3f.ZERO,2);tick(world,Map.of());
                assertEquals(1,world.immobilizerCount());assertTrue(Math.abs(world.position(1).x-start.x)<.15f);
            }
            tick(world,Map.of(1,fire()));assertEquals(0,world.immobilizerCount());assertEquals(0,session.vehicle(1).frozenTicks);
            assertEquals(5,session.vehicle(1).weapon(WeaponType.HOMING).ammo,"The final frozen tick still permits weapons");
            assertEquals(360,session.vehicle(1).controlImmunityTicks);
        }finally{combat.clear();}
    }
    @Test void nativeMineRequiresSupportedPlacementAndDetonatesOnlyAfterArming() {
        try(var world=world()) {
            session.vehicle(0).selectedWeapon=WeaponType.MINE;
            tick(world,Map.of(0,fire()));assertEquals(1,combat.mines().size());
            Vector3f mine=combat.mines().getFirst().position();world.teleport(1,mine.add(0,.5f,0),new Quaternion());
            for(int tick=1;tick<72;tick++) {tick(world,Map.of());assertEquals(1,combat.mines().size());}
            var events=tick(world,Map.of());assertTrue(combat.mines().isEmpty());assertTrue(session.vehicle(1).hp<400);
            assertEquals(1,events.stream().filter(e->e.type()==GameEvent.Type.EXPLOSION&&e.kind().equals("mine")).count());
            world.teleport(0,new Vector3f(0,15,0),new Quaternion());session.vehicle(0).weapon(WeaponType.MINE).cooldownTicks=0;
            tick(world,Map.of(0,fire()));assertEquals(2,session.vehicle(0).weapon(WeaponType.MINE).ammo);
        }finally{combat.clear();}
    }
    @Test void nativeNapalmUsesBallisticSweepAndItsFireCannotDamageThroughUpperDeck() {
        try(var world=world()) {
            world.addStatic(new BoxCollisionShape(new Vector3f(10,.3f,8)),new Vector3f(0,6,33),new Quaternion());
            world.teleport(1,new Vector3f(0,.5f,33),new Quaternion());
            world.teleport(2,new Vector3f(0,6.8f,33),new Quaternion());
            session.vehicle(0).selectedWeapon=WeaponType.NAPALM;
            tick(world,Map.of(0,fire()));assertEquals(1,combat.reservedFireZones());
            for(int tick=0;tick<360&&combat.fireZones().isEmpty();tick++)tick(world,Map.of());
            assertEquals(1,combat.fireZones().size());assertEquals(0,combat.reservedFireZones());
            float lowerHp=session.vehicle(1).hp;
            for(int tick=0;tick<60;tick++)tick(world,Map.of());
            assertTrue(session.vehicle(1).hp<lowerHp,"Fire must reach the lower compound hull");
            assertEquals(400,session.vehicle(2).hp,"A real deck separates upper hull from impact and fire");
            assertTrue(combat.fireZones().getFirst().surfacePoints().stream().allMatch(point->point.y<.1f));
        }finally{combat.clear();}
    }
}

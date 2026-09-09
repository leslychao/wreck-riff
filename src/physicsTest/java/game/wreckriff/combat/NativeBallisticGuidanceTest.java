package game.wreckriff.combat;

import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.math.*;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeBallisticGuidanceTest {
    @ParameterizedTest @ValueSource(floats={6,12,18})
    void instantOffAxisSalvoHitsAMovingNativeHullBeyondTheOldAreaLimit(float speed) {
        MatchSession session=new MatchSession(42,360);CombatSystem combat=new CombatSystem(session,session.combatRules);
        try(var world=new PhysicsWorld(VehicleRules.load())) {
            world.addStatic(new BoxCollisionShape(new Vector3f(150,.5f,150)),new Vector3f(0,-.5f,0),new Quaternion());
            world.addVehicle(0,new Vector3f(0,1,0),new Quaternion());
            world.addVehicle(1,new Vector3f(12,1,25),new Quaternion());
            for(int id=2;id<5;id++)world.addVehicle(id,new Vector3f(-60+id*10,1,-50),new Quaternion());
            for(int id=0;id<5;id++) {
                world.vehicle(id).setGravity(Vector3f.ZERO);world.vehicle(id).setDamping(0,0);
                world.vehicle(id).setMaxSuspensionForce(0);world.vehicle(id).setFrictionSlip(0);
            }
            world.vehicle(1).setLinearVelocity(new Vector3f(speed,0,0));
            VehicleCommand fire=new VehicleCommand(0,0,0,false,false,false,true,WeaponType.BALLISTIC,0,false,false,AbilityId.NONE);
            List<GameEvent> events=new ArrayList<>();
            for(int tick=0;tick<900;tick++) {
                combat.beginTick(tick==0?Map.of(0,fire):Map.of(),world);
                if(tick==0)assertEquals(-1,combat.lockTarget(0));
                world.step();combat.advanceProjectiles(world);combat.resolveDamage(world);
                events.addAll(combat.drainEvents());game.wreckriff.simulation.MatchRuntime.finishTick(session);
            }
            assertTrue(session.vehicle(1).hp<400,"Guided splash/contact must damage a real moving Bullet body");
            var hits=events.stream().filter(e->e.type()==GameEvent.Type.EXPLOSION&&e.kind().equals("ballistic")).toList();
            assertEquals(4,hits.size());assertEquals(4,hits.stream().map(GameEvent::eventId).distinct().count());
            assertTrue(hits.stream().anyMatch(e->e.position().x>18),"The volley must follow outside the original six-metre area");
            assertEquals(0,combat.occupiedProjectileSlots());assertTrue(combat.ballisticWarnings().isEmpty());
            combat.clear();
        }
    }
}

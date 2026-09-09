package game.wreckriff.simulation;

import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.math.*;
import game.wreckriff.arena.*;
import game.wreckriff.combat.*;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.input.VehicleCommand;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeScriptedRuntimeTest {
    @Test void explicitCommandsUseLivePhysicsCombatAndTimersWhileUnspecifiedBotsReceiveNone() {
        MatchSession session=new MatchSession(42,360);VehicleRules rules=VehicleRules.load();
        PhysicsWorld world=new PhysicsWorld(rules);ArenaDefinition arena=ArenaDefinition.load();
        world.addStatic(new BoxCollisionShape(new Vector3f(100,.5f,100)),new Vector3f(0,-.5f,0),new Quaternion());
        Vector3f[] positions={new Vector3f(0,1,-12),new Vector3f(0,1,0),new Vector3f(-30,1,20),new Vector3f(30,1,20),new Vector3f(0,1,40)};
        for(int id=0;id<5;id++)world.addVehicle(id,positions[id],new Quaternion());
        for(int i=0;i<240;i++)world.step();
        try(MatchRuntime runtime=new MatchRuntime(session,world,arena,new NavGraph(arena),rules)) {
            VehicleCommand shoot=new VehicleCommand(0,0,0,false,false,true,false,null,0,false,false,AbilityId.NONE);
            VehicleCommand shield=new VehicleCommand(0,0,0,false,false,false,false,null,0,false,false,AbilityId.SHIELD);
            Vector3f parked=world.position(2);List<GameEvent> events=new ArrayList<>();
            events.addAll(runtime.tick(Map.of(0,shoot,1,shield),false));
            for(int i=1;i<120;i++)events.addAll(runtime.tick(Map.of(0,shoot),false));
            assertEquals(120,session.tick);assertTrue(session.vehicle(1).hp<400);assertTrue(session.vehicle(1).hp>390);
            assertEquals(181,session.vehicle(1).shieldTicks);assertTrue(events.stream().anyMatch(e->e.type()==GameEvent.Type.SHIELD_HIT));
            assertEquals(10,events.stream().filter(e->e.type()==GameEvent.Type.SHOT).count());
            assertTrue(events.stream().filter(e->e.type()==GameEvent.Type.SHOT).allMatch(e->e.sourceId()==0));
            for(int id=0;id<5;id++)assertEquals(0,runtime.bots().metrics(id).aliveTicks(),"Scripted runs do not secretly advance AI decisions");
            assertTrue(world.position(2).distance(parked)<.02f);assertEquals(6,session.vehicle(2).weapon(WeaponType.HOMING).ammo);
            Vector3f before=world.position(2);
            VehicleCommand gas=new VehicleCommand(1,0,0,false,false,false,false,null,0,false,false,AbilityId.NONE);
            for(int i=0;i<120;i++)runtime.tick(Map.of(2,gas),false);
            assertTrue(world.position(2).z>before.z+1,"Explicit scripts still drive the actual native vehicle");
        }
    }
}

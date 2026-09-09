package game.wreckriff.simulation;

import com.jme3.math.*;
import game.wreckriff.arena.*;
import game.wreckriff.combat.CombatRules;
import game.wreckriff.config.*;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.vehicle.VehicleController;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeArenaDrivingTest {
    @Test void gravelChangesAllFourWheelCoefficientsAndLeavingItRestoresGrip() {
        var arena=ArenaRegistry.load().definition("construction_17");var rules=VehicleRules.load();
        try(var world=new PhysicsWorld(rules)) {
            var content=new ArenaFactory(NativeArenaAssets.MANAGER).build(arena);
            content.bodies().forEach(body->world.addStatic(body.id(),body.shape(),body.position(),body.rotation()));world.configureArena(arena);
            var session=new MatchSession(42,arena,MatchSession.Mode.BOSS_DUEL,Configs.load("combat",CombatRules.class));
            var body=world.addVehicle(0,new Vector3f(200,2,81),new Quaternion());
            var driver=new VehicleController(world,session.vehicle(0),rules,arena.bounds(),arena.metadata().recoveryCost());
            for(int i=0;i<360;i++)world.step();
            assertEquals("gravel",world.roadContext(0).surfaceId());
            for(int i=0;i<120;i++)driver.drive(VehicleCommand.NONE);
            for(int i=0;i<4;i++)assertEquals(rules.frictionSlip()*.8f,body.getWheel(i).getFrictionSlip(),.001f);
            world.teleport(0,new Vector3f(24,2,24),new Quaternion());for(int i=0;i<360;i++)world.step();
            assertEquals(1,world.roadContext(0).grip());
            for(int i=0;i<120;i++)driver.drive(VehicleCommand.NONE);
            for(int i=0;i<4;i++)assertEquals(rules.frictionSlip(),body.getWheel(i).getFrictionSlip(),.001f);
        }
    }
    @Test void encounterAccelerationChangesOnlyEngineForceAndCanBeReset() {
        var rules=VehicleRules.load();
        try(var world=new PhysicsWorld(rules)) {
            var session=new MatchSession(42,360);var body=world.addVehicle(0,new Vector3f(0,2,0),new Quaternion());
            var driver=new VehicleController(world,session.vehicle(0),rules);
            var forward=new VehicleCommand(1,0,0,false,false,false,false,null,0,false,false,game.wreckriff.combat.AbilityId.NONE);
            driver.drive(forward);float base=body.getWheel(0).getEngineForce();assertTrue(base>0);
            driver.encounterAcceleration(1.1f);driver.drive(forward);assertEquals(base*1.1f,body.getWheel(0).getEngineForce(),.01f);
            driver.encounterAcceleration(1);driver.drive(forward);assertEquals(base,body.getWheel(0).getEngineForce(),.01f);
        }
    }
}

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
    @Test void excavationGravelChangesAllFourWheelCoefficientsAndLeavingItRestoresGrip() {
        var arena=ArenaRegistry.load().definition("construction_17");var rules=VehicleRules.load();
        try(var world=new PhysicsWorld(rules)) {
            var content=new ArenaFactory(NativeArenaAssets.MANAGER).build(arena);
            content.bodies().forEach(body->world.addStatic(body.id(),body.shape(),body.position(),body.rotation()));world.configureArena(arena);
            var session=new MatchSession(42,arena,MatchSession.Mode.BOSS_DUEL,Configs.load("combat",CombatRules.class));
            var gravel=arena.surfaces().stream().filter(s->s.id().equals("pit-floor")).findFirst().orElseThrow();
            assertEquals(.8f,gravel.grip(),"Excavation gravel must retain its authored lower grip");
            var pit=arena.meshes().stream().filter(b->b.id().equals(gravel.geometryId())).findFirst().orElseThrow();
            Vector3f point=null;float largest=0;
            for(int index=0;index<pit.indices().size();index+=3) {
                var a=pit.vertices().get(pit.indices().get(index)).vector();
                var b=pit.vertices().get(pit.indices().get(index+1)).vector();
                var c=pit.vertices().get(pit.indices().get(index+2)).vector();
                var center=a.add(b).addLocal(c).divideLocal(3);
                float area=b.subtract(a).cross(c.subtract(a)).length();
                if(area>largest&&arena.surfaceAt(center,3,.1f).map(surface->surface.id().equals(gravel.id())).orElse(false)) {point=center;largest=area;}
            }
            assertNotNull(point,"The working terrace must contain a full vehicle footprint on gravel");
            var body=world.addVehicle(0,point.add(0,2,0),new Quaternion());
            var driver=new VehicleController(world,session.vehicle(0),rules,arena.bounds(),arena.metadata().recoveryCost());
            for(int i=0;i<360;i++)world.step();
            assertEquals(gravel.id(),world.roadContext(0).surfaceId());
            for(int i=0;i<120;i++)driver.drive(VehicleCommand.NONE);
            for(int i=0;i<4;i++)assertEquals(rules.frictionSlip()*.8f,body.getWheel(i).getFrictionSlip(),.001f);
            world.teleport(0,arena.spawns().getFirst().position().vector().add(0,2,0),new Quaternion());for(int i=0;i<360;i++)world.step();
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

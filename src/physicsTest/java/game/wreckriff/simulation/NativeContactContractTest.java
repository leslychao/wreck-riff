package game.wreckriff.simulation;

import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.math.*;
import game.wreckriff.arena.ArenaDefinition;
import game.wreckriff.config.VehicleRules;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeContactContractTest {
    @Test void movingRotatingSweepCapturesTargetLocalAnchorAtActualContactTime() {
        try(var world=new PhysicsWorld(VehicleRules.load())) {
            world.addVehicle(0,new Vector3f(0,5,0),new Quaternion());
            world.vehicle(0).setLinearVelocity(new Vector3f(120,0,0));
            world.vehicle(0).setAngularVelocity(new Vector3f(0,6,0));world.step();
            var hit=world.sweep(new Vector3f(0,5,-8),new Vector3f(0,5,8),.1f,-1,0,1);
            assertNotNull(hit);assertEquals(0,hit.vehicleId());assertNotNull(hit.vehicleContact());
            var pose=world.interpolatedPose(0,hit.fraction());
            Vector3f restored=pose.position().add(pose.rotation().mult(hit.vehicleContact().localPoint()));
            assertTrue(restored.distance(hit.point())<.0001f,"Local point must use the collision pose, not end-of-tick pose");
            assertTrue(pose.rotation().mult(hit.vehicleContact().localNormal()).distance(hit.normal())<.0001f);
            Vector3f wrong=world.position(0).add(world.rotation(0).mult(hit.vehicleContact().localPoint()));
            assertTrue(wrong.distance(hit.point())>.05f,"Fixture must have meaningful movement after contact");
            assertEquals(ContactSurface.METAL,hit.surface());
        }
    }
    @Test void realRayAndSupportUseAuthoredStaticMaterialAndDefensiveCoordinates() {
        try(var world=new PhysicsWorld(VehicleRules.load())) {
            var arena=ArenaDefinition.load();world.configureArena(arena);
            var box=arena.boxes().stream().filter(b->b.material().equals("concrete")).findFirst().orElseThrow();
            world.addStatic(box.id(),new BoxCollisionShape(new Vector3f(2,.5f,2)),new Vector3f(0,-.5f,0),new Quaternion());
            var hit=world.ray(new Vector3f(0,2,0),new Vector3f(0,-2,0),-1);
            assertNotNull(hit);assertEquals(ContactSurface.CONCRETE,hit.surface());assertNull(hit.vehicleContact());
            var support=world.support(new Vector3f(0,1,0),2);assertNotNull(support);
            assertEquals(ContactSurface.CONCRETE,support.surface());support.point().setY(99);
            assertEquals(0,support.point().y,.001f);
        }
    }
    @Test void nativeRamRetainsTwoOutwardLocalContactNormals() {
        try(var world=new PhysicsWorld(VehicleRules.load())) {
            world.addVehicle(0,new Vector3f(-1.2f,4,0),new Quaternion());
            world.addVehicle(1,new Vector3f(1.2f,4,0),new Quaternion());
            world.vehicle(0).setLinearVelocity(new Vector3f(20,0,0));world.vehicle(1).setLinearVelocity(new Vector3f(-20,0,0));
            for(int tick=0;tick<20&&world.rams().isEmpty();tick++)world.step();
            assertFalse(world.rams().isEmpty());var ram=world.rams().getFirst();
            assertNotNull(ram.firstContact());assertNotNull(ram.secondContact());
            assertTrue(ram.firstContact().localNormal().x>.8f);
            assertTrue(ram.secondContact().localNormal().x<-.8f);
            assertTrue(ram.firstContact().localPoint().x>0);assertTrue(ram.secondContact().localPoint().x<0);
        }
    }
}

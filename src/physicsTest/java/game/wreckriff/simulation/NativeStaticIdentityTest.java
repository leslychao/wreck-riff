package game.wreckriff.simulation;

import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.math.*;
import game.wreckriff.config.VehicleRules;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeStaticIdentityTest {
    @Test void rayAndSweepReportTheSameAuthoredObjectAndRemovalExposesTheNextCollider() {
        try(var world=new PhysicsWorld(VehicleRules.load())) {
            world.addStatic("shortcut-panel",new BoxCollisionShape(new Vector3f(2,2,.3f)),new Vector3f(0,2,5),new Quaternion());
            world.addStatic("back-wall",new BoxCollisionShape(new Vector3f(2,2,.3f)),new Vector3f(0,2,9),new Quaternion());
            Vector3f from=new Vector3f(0,2,0),to=new Vector3f(0,2,12);
            assertEquals("shortcut-panel",world.ray(from,to,-1).objectId());
            assertEquals("shortcut-panel",world.staticSweep(from,to,.2f).objectId());
            assertTrue(world.removeStatic("shortcut-panel"));assertFalse(world.removeStatic("shortcut-panel"));
            assertEquals("back-wall",world.ray(from,to,-1).objectId());
            assertEquals("back-wall",world.staticSweep(from,to,.2f).objectId());
            world.addVehicle(0,new Vector3f(0,1,2),new Quaternion());
            var hit=world.ray(new Vector3f(0,1.2f,-2),new Vector3f(0,1.2f,12),-1);
            assertEquals(0,hit.vehicleId());assertNull(hit.objectId());
        }
    }
}

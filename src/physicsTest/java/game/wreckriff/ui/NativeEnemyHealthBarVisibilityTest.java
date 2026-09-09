package game.wreckriff.ui;

import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.simulation.PhysicsWorld;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeEnemyHealthBarVisibilityTest {
    @Test void realWallHidesHullButExposedCabinAboveLowCoverRemainsVisible() {
        try(var world=new PhysicsWorld(VehicleRules.load())) {
            world.addVehicle(1,new Vector3f(0,1,20),new Quaternion());
            Camera camera=camera(new Vector3f(0,1.2f,0),new Vector3f(0,1.2f,20));
            assertNotNull(project(camera,world));
            world.addStatic("wall",new BoxCollisionShape(new Vector3f(8,5,.3f)),new Vector3f(0,5,10),new Quaternion());
            assertNull(project(camera,world),"A fully occluded native hull must not reveal health through the wall");
            world.removeStatic("wall");
            world.addStatic("cover",new BoxCollisionShape(new Vector3f(8,.75f,.3f)),new Vector3f(0,.75f,10),new Quaternion());
            assertFalse(world.visible(camera.getLocation(),world.position(1).add(0,.2f,0),1));
            assertNotNull(project(camera,world),"A visible cabin above cover is sufficient even with its centre hidden");
        }
    }

    @Test void nativeUpperRoadOccludesOtherFloorAndSameFloorRemainsVisible() {
        try(var world=new PhysicsWorld(VehicleRules.load())) {
            world.addVehicle(1,new Vector3f(0,1,20),new Quaternion());
            Camera above=camera(new Vector3f(0,8,0),new Vector3f(0,1,20));
            assertNotNull(project(above,world));
            world.addStatic("upper-road",new BoxCollisionShape(new Vector3f(12,.3f,20)),new Vector3f(0,4,15),new Quaternion());
            assertNull(project(above,world),"The upper road must hide enemies below it");
            Camera below=camera(new Vector3f(0,2,0),new Vector3f(0,1,20));
            assertNotNull(project(below,world),"The same lower-floor target is visible from below the slab");
            world.removeStatic("upper-road");assertNotNull(project(above,world));
        }
    }

    private static EnemyHealthBars.Marker project(Camera camera,PhysicsWorld world) {
        return EnemyHealthBarProjection.project(camera,world,1,world.interpolatedPose(1,.5f),75,100,world.profile(1));
    }
    private static Camera camera(Vector3f position,Vector3f target) {
        Camera camera=new Camera(1920,1080);camera.setFrustumPerspective(60,1920f/1080,.1f,500);
        camera.setLocation(position);camera.lookAt(target,Vector3f.UNIT_Y);camera.update();return camera;
    }
}

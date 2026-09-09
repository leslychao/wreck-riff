package game.wreckriff.ui;

import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import game.wreckriff.arena.TestWorld;
import game.wreckriff.config.VehicleProfile;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.simulation.PhysicsWorld.Pose;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EnemyHealthBarProjectionTest {
    @Test void healthTracksFullDamageRepairAndDeathWithMaximumClamp() {
        var camera=camera();var world=world(new Vector3f(0,0,20));var profile=VehicleProfile.rivet();
        assertEquals(1,project(camera,world,profile,800,800).healthFraction());
        assertEquals(.25f,project(camera,world,profile,200,800).healthFraction());
        assertEquals(.75f,project(camera,world,profile,600,800).healthFraction());
        assertEquals(1,project(camera,world,profile,1000,800).healthFraction());
        assertNull(project(camera,world,profile,0,800));assertNull(project(camera,world,profile,-1,800));
        assertThrows(IllegalArgumentException.class,()->project(camera,world,profile,1,0));
    }

    @Test void currentCameraDeterminesFrontRearSideAndFarVisibility() {
        var camera=camera();var world=world(new Vector3f(0,0,20));var profile=VehicleProfile.rivet();
        assertNotNull(project(camera,world,profile,1,1));
        world.positions[1]=new Vector3f(0,0,-20);assertNull(project(camera,world,profile,1,1));
        camera.lookAtDirection(Vector3f.UNIT_Z.negate(),Vector3f.UNIT_Y);camera.update();
        assertNotNull(project(camera,world,profile,1,1));
        world.positions[1]=new Vector3f(200,0,-20);assertNull(project(camera,world,profile,1,1));
        world.positions[1]=new Vector3f(0,0,-600);assertNull(project(camera,world,profile,1,1));
    }

    @Test void occludedEnemiesAreHiddenAndNearbyNearPlaneCrossingsStayFinite() {
        var camera=camera();var world=world(new Vector3f(0,0,20));var profile=VehicleProfile.rivet();
        world.wall=true;assertNull(project(camera,world,profile,1,1));
        world.wall=false;world.positions[1]=new Vector3f(0,0,1);
        var marker=project(camera,world,profile,1,1);
        assertNotNull(marker);assertTrue(Float.isFinite(marker.x()));assertTrue(Float.isFinite(marker.y()));
        assertTrue(marker.x()>=0&&marker.x()<=1920);assertTrue(marker.y()>=0&&marker.y()<=1080);
    }

    @Test void interpolatedHullTopAndProfileDrivePositionWithoutMutatingCameraOrWorld() {
        var camera=camera();var world=world(new Vector3f(4,0,25));var profile=VehicleProfile.rivet();
        Pose pose=new Pose(new Vector3f(2,0,25),new Quaternion().fromAngles(0,0,.2f));
        Vector3f positionBefore=world.position(1),cameraBefore=camera.getLocation().clone();
        var marker=EnemyHealthBarProjection.project(camera,world,1,pose,1,1,profile);
        assertNotNull(marker);
        float maxY=Float.NEGATIVE_INFINITY;var b=profile.hullBounds();
        for(float x:new float[]{b.minX(),b.maxX()})for(float y:new float[]{b.minY(),b.maxY()})for(float z:new float[]{b.minZ(),b.maxZ()})
            maxY=Math.max(maxY,camera.getScreenCoordinates(pose.position().add(pose.rotation().mult(new Vector3f(x,y,z)))).y);
        assertEquals(maxY,marker.y(),.001f);assertEquals(positionBefore,world.position(1));assertEquals(cameraBefore,camera.getLocation());
        var boss=VehicleProfile.boss("boss_director",VehicleRules.load());
        var bossMarker=EnemyHealthBarProjection.project(camera,world,1,pose,1,1,boss);
        assertNotNull(bossMarker);assertTrue(bossMarker.y()>marker.y());
    }

    private static EnemyHealthBars.Marker project(Camera camera,TestWorld world,VehicleProfile profile,float hp,float max) {
        return EnemyHealthBarProjection.project(camera,world,1,new Pose(world.position(1),world.rotation(1)),hp,max,profile);
    }
    private static TestWorld world(Vector3f position) {var world=new TestWorld();world.positions[1]=position;return world;}
    private static Camera camera() {
        Camera camera=new Camera(1920,1080);camera.setFrustumPerspective(60,1920f/1080,.1f,500);
        camera.setLocation(new Vector3f(0,2,0));camera.lookAtDirection(Vector3f.UNIT_Z,Vector3f.UNIT_Y);camera.update();return camera;
    }
}

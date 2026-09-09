package game.wreckriff.presentation;

import com.jme3.math.*;
import com.jme3.renderer.Camera;
import game.wreckriff.arena.ArenaDefinition;
import game.wreckriff.simulation.WorldQuery;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticCameraTourTest {
    @Test void missedFramesDoNotLoseDueShotsAndEachShotIsCapturedExactlyOnce() {
        DiagnosticCameraTour tour=new DiagnosticCameraTour(ArenaDefinition.load());Camera camera=new Camera(1280,720);
        camera.setLocation(new Vector3f(1,2,3));WorldQuery world=new ReadOnlyWorld();
        assertNull(tour.apply(camera,world,7.99));assertEquals("chase",tour.apply(camera,world,8));
        assertEquals(new Vector3f(1,2,3),camera.getLocation(),"Chase evidence uses the actual chase camera");
        // No drawable frame during the original garage window; overdue shots must catch up one frame each.
        assertEquals("garage",tour.apply(camera,world,16.1));
        assertTrue(camera.getLocation().x< -60,"Garage shot is outside the existing west entrance");
        assertEquals("ramp",tour.apply(camera,world,16.2));assertNull(tour.apply(camera,world,16.3));
        assertEquals("car",tour.apply(camera,world,25));assertNull(tour.apply(camera,world,26));
    }
    private static final class ReadOnlyWorld implements WorldQuery {
        public Vector3f position(int id){return new Vector3f(0,1,0);}
        public Quaternion rotation(int id){return Quaternion.IDENTITY;}
        public Vector3f velocity(int id){return Vector3f.ZERO;}
        public boolean grounded(int id){return true;}
        public float mass(int id){return 1100;}
        public Hit ray(Vector3f a,Vector3f b,int id){return null;}
        public Hit sweep(Vector3f a,Vector3f b,float radius,int id){throw new AssertionError("Tour needs only read-only visibility rays");}
        public boolean visible(Vector3f a,Vector3f b,int id){return true;}
        public float distanceToHull(int id,Vector3f point){return 0;}
        public Vector3f closestHullPoint(int id,Vector3f from){return position(id);}
        public void impulse(int id,Vector3f linear,Vector3f torque,float maximumAngularDeltaSpeed){throw new AssertionError("Camera tour must never change simulation");}
        public void immobilize(int id,boolean frozen){throw new AssertionError("Camera tour must never change simulation");}
    }
}

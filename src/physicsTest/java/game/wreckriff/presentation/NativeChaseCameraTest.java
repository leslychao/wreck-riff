package game.wreckriff.presentation;

import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import game.wreckriff.arena.ArenaDefinition;
import game.wreckriff.arena.ArenaRegistry;
import game.wreckriff.config.CameraRules;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.simulation.PhysicsWorld;
import game.wreckriff.simulation.RoadContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Native wheel contacts and sphere sweeps with a real Camera; no renderer, GPU or visible window. */
class NativeChaseCameraTest {
    private static final CameraRules CAMERA_RULES=CameraRules.load();
    private static final ArenaDefinition ARENA=ArenaRegistry.load().definition("construction_17");
    private static final int PLAYER=0;
    // Native suspension continues to settle by fractions of a millimetre while the camera is tracking it.
    private static final float CONTACT_HEIGHT_TOLERANCE=.001f;

    @ParameterizedTest @ValueSource(ints={30,60,120})
    void nativeLaunchContextExpandsAndReturnsSmoothlyAtEachRenderRate(int fps) {
        FlightTrace observed=flightTrace(fps);
        if(fps!=120) {
            FlightTrace reference=flightTrace(120);
            for(int i=0;i<observed.offsets.length;i++)
                assertTrue(observed.offsets[i].distance(reference.offsets[i])<.035f,
                        "The same elapsed flight/landing time must have the same framing at "+fps+" Hz: sample "+i);
        }
    }

    @ParameterizedTest @ValueSource(ints={30,60,120})
    void desiredAndSmoothedFlightPositionsStayOnTheNearSideOfANativeWall(int fps) {
        try(var rig=new Rig(new Quaternion())) {
            rig.addWall();
            Vector3f desired=rig.world.position(PLAYER).add(0,CAMERA_RULES.height(),-CAMERA_RULES.distance());
            assertNotNull(rig.world.staticSweep(rig.pivot(1),desired,CAMERA_RULES.sweepRadius()),
                    "The fixture must obstruct the ordinary desired camera position");
            rig.render(fps,false,1);
            rig.assertWallClear(1);
            rig.beginLaunch();
            for(int i=0;i<fps*2;i++) {
                rig.tickAndRender(fps,false);
                rig.assertWallClear(1);
            }
            rig.world.endLaunch(PLAYER);
            assertEquals(RoadContext.Motion.ROAD,rig.world.roadContext(PLAYER).motion());
            for(int i=0;i<fps;i++) {
                rig.tickAndRender(fps,false);
                rig.assertWallClear(1);
            }
        }
    }

    @Test void finalNativeSweepRejectsAnOldCameraPositionEvenWhenTheNewRearViewTargetIsClear() {
        try(var rig=new Rig(new Quaternion())) {
            rig.render(120,false,1);
            Vector3f oldCamera=rig.camera.getLocation().clone();
            rig.addWall();
            assertNotNull(rig.world.staticSweep(rig.pivot(1),oldCamera,CAMERA_RULES.sweepRadius()));
            Vector3f rearDesired=rig.world.position(PLAYER).add(0,CAMERA_RULES.height(),CAMERA_RULES.distance());
            assertNull(rig.world.staticSweep(rig.pivot(1),rearDesired,CAMERA_RULES.sweepRadius()),
                    "The first sweep is clear, so only the final sweep can prevent interpolation through the wall");
            rig.render(120,true,1);
            rig.assertWallClear(1);
            assertTrue(rig.camera.getLocation().distance(oldCamera)>1,
                    "A newly obstructed old camera location must be corrected in this frame");
            for(int i=0;i<120;i++) {
                rig.tickAndRender(120,true);
                rig.assertWallClear(1);
            }
        }
    }

    @ParameterizedTest @ValueSource(floats={.25f,.5f,.75f})
    void nativeSweepUsesTheInterpolatedVehiclePivot(float alpha) {
        try(var rig=new Rig(new Quaternion())) {
            rig.render(120,false,1);
            rig.addWall();
            Vector3f previous=rig.world.position(PLAYER);
            rig.world.vehicle(PLAYER).setPhysicsLocation(previous.add(0,0,2));
            assertEquals(previous.z+2*alpha,rig.world.interpolatedPose(PLAYER,alpha).position().z,.0001f);
            rig.render(120,false,alpha);
            rig.assertWallClear(alpha);
        }
    }

    @Test void rearViewDoesNotReplaceTheCachedForwardUsedAtVerticalPitch() {
        Quaternion yaw=new Quaternion().fromAngleAxis(70*FastMath.DEG_TO_RAD,Vector3f.UNIT_Y);
        Vector3f forward=yaw.mult(Vector3f.UNIT_Z);
        try(var rig=new Rig(yaw)) {
            rig.render(120,false,1);
            rig.placeAirborne(yaw);
            rig.renderFrames(120,2,false);
            assertBehind(rig,forward);
            rig.renderFrames(120,2,true);
            assertTrue(rig.offset().dot(forward)>CAMERA_RULES.distance(),"Rear view must actually move ahead of the car");
            rig.placeAirborne(yaw.mult(new Quaternion().fromAngleAxis(FastMath.HALF_PI,Vector3f.UNIT_X)));
            rig.renderFrames(120,1,true);
            assertTrue(rig.offset().dot(forward)>CAMERA_RULES.distance(),
                    "At vertical pitch rear view must retain the real forward heading, not its own reversed view");
            rig.renderFrames(120,2,false);
            assertBehind(rig,forward);
        }
    }

    @Test void tumblingThroughVerticalKeepsTheEstablishedHeadingInsteadOfJumpingToWorldZ() {
        Quaternion yaw=new Quaternion().fromAngleAxis(70*FastMath.DEG_TO_RAD,Vector3f.UNIT_Y);
        Vector3f forward=yaw.mult(Vector3f.UNIT_Z);
        try(var rig=new Rig(yaw)) {
            rig.render(120,false,1);
            rig.placeAirborne(yaw);
            rig.renderFrames(120,2,false);
            Vector3f previous=rig.camera.getLocation().clone();
            for(float pitch:new float[]{60,80,89,89.9f,90,90.1f,91,100,120,180,240,269.9f,270,270.1f,300,360}) {
                rig.placeAirborne(yaw.mult(new Quaternion().fromAngleAxis(pitch*FastMath.DEG_TO_RAD,Vector3f.UNIT_X)));
                for(int frame=0;frame<30;frame++) {
                    rig.render(120,false,1);
                    assertBehind(rig,forward);
                    assertTrue(rig.camera.getLocation().distance(previous)<.03f,
                            "Pitch "+pitch+" must not create a horizontal camera snap");
                    assertFiniteCamera(rig.camera);
                    previous=rig.camera.getLocation().clone();
                }
            }
        }
    }

    private static FlightTrace flightTrace(int fps) {
        try(var rig=new Rig(new Quaternion())) {
            rig.render(fps,false,1);
            Vector3f ground=rig.offset();
            assertEquals(-CAMERA_RULES.distance(),ground.z,.005f);
            assertEquals(CAMERA_RULES.height(),ground.y,.005f);
            rig.beginLaunch();
            Vector3f[] checkpoints=new Vector3f[8];
            Vector3f previous=ground;
            for(int i=0;i<fps*2;i++) {
                rig.tickAndRender(fps,false);
                Vector3f current=rig.offset();
                assertTrue(current.z<=previous.z+.0002f,"Flight distance must expand without reversing");
                assertTrue(current.y>=previous.y-CONTACT_HEIGHT_TOLERANCE,"Flight height must rise without reversing");
                assertTrue(current.distance(previous)<12f/fps,"Expansion must be gradual on every rendered frame");
                if(i==0)assertTrue(current.distance(ground)>.0001f&&current.distance(ground)<.2f);
                if((i+1)%(fps/2)==0)checkpoints[(i+1)/(fps/2)-1]=current;
                previous=current;
            }
            assertEquals(-CAMERA_RULES.distance()*1.35f,previous.z,.025f);
            assertEquals(CAMERA_RULES.height()+.75f,previous.y,.025f);
            rig.world.endLaunch(PLAYER);
            assertEquals(4,rig.world.supportedWheelContacts(PLAYER));
            assertEquals(RoadContext.Motion.ROAD,rig.world.roadContext(PLAYER).motion());
            for(int i=0;i<fps*2;i++) {
                rig.tickAndRender(fps,false);
                Vector3f current=rig.offset();
                assertTrue(current.z>=previous.z-.0002f,"Landing must return continuously toward ordinary distance");
                assertTrue(current.y<=previous.y+CONTACT_HEIGHT_TOLERANCE,"Landing must return continuously toward ordinary height: frame="+i
                        +", previous="+previous+", current="+current+", body="+rig.world.position(PLAYER));
                assertTrue(current.distance(previous)<12f/fps,"Return must be gradual on every rendered frame");
                if((i+1)%(fps/2)==0)checkpoints[4+(i+1)/(fps/2)-1]=current;
                previous=current;
            }
            assertTrue(previous.distance(ground)<.025f,"Grounded framing must recover after flight");
            return new FlightTrace(checkpoints);
        }
    }

    private static void assertBehind(Rig rig,Vector3f forward) {
        Vector3f horizontal=rig.offset().setY(0);
        assertTrue(horizontal.length()>CAMERA_RULES.distance());
        assertTrue(horizontal.normalizeLocal().dot(forward)<-.999f,
                "The camera must stay behind the established non-world-Z heading");
    }

    private static void assertFiniteCamera(Camera camera) {
        assertTrue(Vector3f.isValidVector(camera.getLocation()));
        assertTrue(Vector3f.isValidVector(camera.getDirection()));
        assertTrue(Vector3f.isValidVector(camera.getUp()));
        assertEquals(1,camera.getDirection().length(),.0001f);
        assertEquals(0,camera.getDirection().dot(camera.getUp()),.0001f);
    }

    private record FlightTrace(Vector3f[] offsets) {}

    private static final class Rig implements AutoCloseable {
        final PhysicsWorld world=new PhysicsWorld(VehicleRules.load());
        final Camera camera=new Camera(1920,1080);
        final ChaseCamera chase=new ChaseCamera(camera,CAMERA_RULES);

        Rig(Quaternion rotation) {
            world.configureArena(ARENA);
            var floor=ARENA.boxes().stream().filter(box->box.id().equals("floor-west")).findFirst().orElseThrow();
            assertTrue(floor.collision());
            world.addStatic(floor.id(),new BoxCollisionShape(floor.size().vector().mult(.5f)),floor.center().vector(),new Quaternion());
            world.addVehicle(PLAYER,new Vector3f(20,2,70),rotation);
            for(int i=0;i<360;i++)world.step();
            assertEquals(4,world.supportedWheelContacts(PLAYER),"Fixture requires all four native wheels on the shipped floor");
            assertEquals("floor-west",world.roadContext(PLAYER).surfaceId());
            assertEquals(RoadContext.Motion.ROAD,world.roadContext(PLAYER).motion());
        }

        void beginLaunch() {
            var pad=ARENA.launchPads().getFirst();
            // Exercise the camera's existing launch-state boundary; launch trajectories have separate native tests.
            world.beginLaunch(PLAYER,pad.id(),pad.sourceSurfaceId(),pad.landingSurfaceId());
            assertTrue(world.roadContext(PLAYER).flying());
            assertEquals(RoadContext.Motion.LAUNCH,world.roadContext(PLAYER).motion());
        }
        void placeAirborne(Quaternion rotation) {
            world.teleport(PLAYER,new Vector3f(20,20,70),rotation);
            beginLaunch();
        }
        void tickAndRender(int fps,boolean rear) {
            for(int i=0;i<120/fps;i++)world.step();
            render(fps,rear,1);
        }
        void render(int fps,boolean rear,float alpha) {
            chase.update(world,PLAYER,alpha,1f/fps,rear,false,0);
        }
        void renderFrames(int fps,int seconds,boolean rear) {
            for(int i=0;i<fps*seconds;i++)render(fps,rear,1);
        }
        Vector3f offset() { return camera.getLocation().subtract(world.position(PLAYER)); }
        Vector3f pivot(float alpha) { return world.interpolatedPose(PLAYER,alpha).position().add(0,CAMERA_RULES.lookHeight(),0); }
        void addWall() {
            world.addStatic("camera-wall",new BoxCollisionShape(new Vector3f(10,8,.3f)),new Vector3f(20,8,65),new Quaternion());
        }
        void assertWallClear(float alpha) {
            assertTrue(camera.getLocation().z>65.3f+CAMERA_RULES.sweepRadius(),"Camera sphere must remain in front of the wall");
            assertNull(world.staticSweep(pivot(alpha),camera.getLocation(),CAMERA_RULES.sweepRadius()),
                    "The final camera segment must be clear in native physics");
            assertFiniteCamera(camera);
        }
        @Override public void close() { world.close(); }
    }
}

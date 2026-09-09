package game.wreckriff.simulation;

import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.math.*;
import game.wreckriff.config.VehicleRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class NativeArenaMechanismsTest {
    private static final VehicleRules RULES=VehicleRules.load();

    @Test void movingBoxKeepsOneStableNativeIdentityThroughTranslationRotationAndRemoval() {
        try(var world=new PhysicsWorld(RULES)) {
            var body=world.addMovingBox("carousel-arm",new Vector3f(3,.5f,.4f),new Vector3f(10,2,0),new Quaternion());
            assertTrue(body.isKinematic());assertTrue(body.isContactResponse());assertEquals(1,world.bodyCount());
            var hit=world.ray(new Vector3f(12,2,-5),new Vector3f(12,2,5),-1);
            assertNotNull(hit);assertEquals("carousel-arm",hit.objectId());
            assertEquals("carousel-arm",world.staticSweep(new Vector3f(12,2,-5),new Vector3f(12,2,5),.1f).objectId());
            Quaternion turn=new Quaternion().fromAngleAxis(FastMath.HALF_PI,Vector3f.UNIT_Y);
            world.moveArenaBody("carousel-arm",new Vector3f(10,2,0),turn);world.step();
            assertNull(world.ray(new Vector3f(12,2,-5),new Vector3f(12,2,5),-1),"Ray must observe the rotated actual collider");
            world.moveArenaBody("carousel-arm",new Vector3f(20,2,0),turn);world.step();
            assertNull(world.ray(new Vector3f(10,2,-5),new Vector3f(10,2,5),-1));
            assertEquals("carousel-arm",world.ray(new Vector3f(20,2,-5),new Vector3f(20,2,5),-1).objectId());
            assertEquals(1,world.bodyCount());assertTrue(world.removeArenaBody("carousel-arm"));
            assertFalse(world.removeArenaBody("carousel-arm"));assertEquals(0,world.bodyCount());
            assertThrows(IllegalArgumentException.class,()->world.moveArenaBody("carousel-arm",Vector3f.ZERO,new Quaternion()));
        }
    }
    @ParameterizedTest @ValueSource(ints={0,20})
    void actualMovingContactCannotAddMoreThanEightHorizontalMetresPerSecond(int initialSpeed) {
        try(var world=floor()) {
            // Test forward momentum: a sideways velocity is intentionally
            // removed by the raycast wheels' lateral grip before the impact.
            var car=world.addVehicle(0,new Vector3f(0,1,0),facingX());settle(world);
            // Existing weapon/player momentum is present before the native step.
            world.impulse(0,new Vector3f(initialSpeed*world.mass(0),0,0),Vector3f.ZERO,0);
            world.addMovingBox("service-truck",new Vector3f(.6f,1.2f,4),new Vector3f(-4,1.2f,0),new Quaternion());
            boolean contact=false,existingSpeedPreserved=false;float x=-4;StringBuilder trace=new StringBuilder();
            for(int tick=0;tick<24;tick++) {
                Vector3f before=world.velocity(0);
                x+=48*MatchSession.DT;world.moveArenaBody("service-truck",new Vector3f(x,1.2f,0),new Quaternion());
                world.step();
                var contacts=world.arenaContacts().stream().filter(c->c.objectId().equals("service-truck")&&c.vehicleId()==0).toList();
                assertTrue(contacts.size()<=1,"One strongest contact per native object/vehicle step");
                if(!contacts.isEmpty()) {
                    contact=true;Vector3f velocity=world.velocity(0);
                    trace.append("tick=").append(tick).append(" car=").append(before.x).append("->").append(velocity.x)
                            .append(" closing=").append(contacts.getFirst().closingSpeed()).append(';');
                    assertTrue(velocity.x-before.x<=8.05f,"Moving-body delta: "+trace);
                    existingSpeedPreserved|=velocity.x>initialSpeed;
                    assertTrue(contacts.getFirst().normal().x>.8f);
                    assertTrue(contacts.getFirst().closingSpeed()>=0);
                }
            }
            assertTrue(contact,"Native moving collider must reach the chassis");
            assertTrue(existingSpeedPreserved,"The mechanism must physically push beyond existing momentum; cap limits only its added speed: "+trace);
            assertTrue(car.isContactResponse());assertEquals(0,world.teleportGeneration(0));
        }
    }
    @Test void aCarStillStopsNaturallyAgainstAnUnmovingKinematicObstacle() {
        try(var world=floor()) {
            var car=world.addVehicle(0,new Vector3f(-5,1,0),facingX());settle(world);
            world.addMovingBox("idle-truck",new Vector3f(.6f,1.2f,4),new Vector3f(0,1.2f,0),new Quaternion());
            car.setLinearVelocity(new Vector3f(24,0,0));
            boolean stopped=false;StringBuilder trace=new StringBuilder();
            for(int tick=0;tick<60;tick++) {
                float before=world.velocity(0).x;world.step();
                if(world.arenaContacts().stream().anyMatch(c->c.objectId().equals("idle-truck"))) {
                    trace.append("tick=").append(tick).append(" car=").append(before).append("->").append(world.velocity(0).x).append(';');
                    stopped|=before-world.velocity(0).x>8;
                }
            }
            assertTrue(stopped,"A speed-delta cap must not undo passive collision braking: "+trace);
            assertTrue(world.position(0).x<0,"The actual box still blocks the car");
        }
    }
    @Test void translationAndRotationShareNativeVelocityAtContact() {
        try(var world=new PhysicsWorld(RULES)) {
            var car=world.addVehicle(0,new Vector3f(0,5,0),facingX());
            car.setGravity(Vector3f.ZERO);car.setLinearVelocity(new Vector3f(10,0,0));
            // The raised chassis has no wheel/road friction. A real moving
            // collider must transfer momentum, including when both pose setters
            // run before the same native step.
            world.addMovingBox("turning-arm",new Vector3f(.6f,2,4),new Vector3f(-3.7f,5,0),new Quaternion());
            float x=-3.7f,maxSpeed=10;boolean contact=false;
            for(int tick=0;tick<24;tick++) {
                Vector3f before=world.velocity(0);
                x+=32*MatchSession.DT;
                Quaternion rotation=new Quaternion().fromAngleAxis((tick+1)*.005f,Vector3f.UNIT_Y);
                world.moveArenaBody("turning-arm",new Vector3f(x,5,0),rotation);
                world.step();
                if(world.arenaContacts().stream().anyMatch(c->c.objectId().equals("turning-arm"))) {
                    contact=true;Vector3f after=world.velocity(0);
                    assertTrue(after.subtract(before).setY(0).length()<=8.05f,"Combined-transform push: "+before+" -> "+after);
                    maxSpeed=Math.max(maxSpeed,after.x);
                }
            }
            assertTrue(contact);assertTrue(maxSpeed>14,"Both transform components must reach the native contact solver; max="+maxSpeed);
            assertEquals(2,world.bodyCount());assertEquals(0,world.teleportGeneration(0));
        }
    }
    @Test void staticPanelContactsReportClosingSpeedAndClearOnTeleportRemovalAndNextStep() {
        try(var world=floor()) {
            var car=world.addVehicle(0,new Vector3f(0,1,-8),new Quaternion());settle(world);
            world.addStatic("destructible-panel",new BoxCollisionShape(new Vector3f(4,2,.3f)),new Vector3f(0,2,0),new Quaternion());
            car.setLinearVelocity(new Vector3f(0,0,24));PhysicsWorld.ArenaContact found=null;
            for(int tick=0;tick<90&&found==null;tick++) {
                world.step();var contacts=world.arenaContacts().stream().filter(c->c.objectId().equals("destructible-panel")).toList();
                assertTrue(contacts.size()<=1);if(!contacts.isEmpty())found=contacts.getFirst();
            }
            assertNotNull(found);assertEquals(0,found.vehicleId());assertTrue(found.closingSpeed()>20);
            assertTrue(found.normal().z<-.8f);assertTrue(Math.abs(found.point().z)<.5f);
            Vector3f saved=found.point();found.point().set(99,99,99);assertEquals(saved,found.point());
            world.teleport(0,new Vector3f(0,1,-30),new Quaternion());assertTrue(world.arenaContacts().isEmpty());
            world.step();assertTrue(world.arenaContacts().stream().noneMatch(c->c.objectId().equals("destructible-panel")));
            assertTrue(world.removeArenaBody("destructible-panel"));
            world.close();assertTrue(world.arenaContacts().isEmpty());
        }
    }
    @Test void unrelatedMovingBodyDoesNotChangeOrdinaryVehicleCollision() {
        Vector3f reference=carCollision(false),withMechanism=carCollision(true);
        assertEquals(reference.x,withMechanism.x,.02f);assertEquals(reference.z,withMechanism.z,.02f);
    }
    @Test void movingHazardCannotBecomeAStaticRoadCheckpointSupport() {
        try(var world=floor()) {
            world.addMovingBox("crane-load",new Vector3f(2,.5f,2),new Vector3f(0,3,0),new Quaternion());
            assertNull(world.support(new Vector3f(0,3.6f,0),.3f),"Moving hazard is not a static road");
            assertEquals("crane-load",world.ray(new Vector3f(0,3.6f,0),new Vector3f(0,3.3f,0),-1).objectId());
        }
    }
    private static Vector3f carCollision(boolean mechanism) {
        try(var world=new PhysicsWorld(RULES)) {
            Quaternion yaw=new Quaternion().fromAngleAxis(FastMath.HALF_PI,Vector3f.UNIT_Y);
            var a=world.addVehicle(0,new Vector3f(-3,5,0),yaw);var b=world.addVehicle(1,new Vector3f(3,5,0),yaw);
            a.setGravity(Vector3f.ZERO);b.setGravity(Vector3f.ZERO);a.setLinearVelocity(new Vector3f(20,0,0));b.setLinearVelocity(new Vector3f(-20,0,0));
            if(mechanism)world.addMovingBox("far-truck",new Vector3f(1,1,1),new Vector3f(100,2,0),new Quaternion());
            boolean ram=false;
            for(int tick=0;tick<30;tick++) {
                if(mechanism)world.moveArenaBody("far-truck",new Vector3f(100+tick*.5f,2,0),new Quaternion());
                world.step();ram|=!world.rams().isEmpty();
            }
            assertTrue(ram);return world.velocity(0);
        }
    }
    private static PhysicsWorld floor() {
        var world=new PhysicsWorld(RULES);
        world.addStatic("floor",new BoxCollisionShape(new Vector3f(100,.5f,100)),new Vector3f(0,-.5f,0),new Quaternion());return world;
    }
    private static void settle(PhysicsWorld world) {for(int tick=0;tick<360;tick++)world.step();}
    private static Quaternion facingX() {return new Quaternion().fromAngleAxis(FastMath.HALF_PI,Vector3f.UNIT_Y);}
}

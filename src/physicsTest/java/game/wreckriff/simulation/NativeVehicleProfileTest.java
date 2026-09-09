package game.wreckriff.simulation;

import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.bullet.collision.shapes.CompoundCollisionShape;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import game.wreckriff.arena.ArenaDefinition.Bounds;
import game.wreckriff.combat.AbilityId;
import game.wreckriff.combat.CombatRules;
import game.wreckriff.config.VehicleProfile;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.config.Configs;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.vehicle.VehicleController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class NativeVehicleProfileTest {
    private static final VehicleCommand GAS=new VehicleCommand(1,0,0,false,false,false,false,null,0,false,false,AbilityId.NONE);

    @Test void defaultRivetKeepsExactChassisWheelAndMuzzleContract() {
        var rules=VehicleRules.load();
        try(var world=new PhysicsWorld(rules)) {
            var body=world.addVehicle(0,new Vector3f(0,1,0),new Quaternion());
            assertEquals(1100,body.getMass());assertEquals(4,body.getNumWheels());
            var children=((CompoundCollisionShape)body.getCollisionShape()).listChildren();
            assertEquals(2,children.length);
            assertEquals(new Vector3f(0,.2f,0),children[0].copyOffset(null));
            assertEquals(new Vector3f(1.05f,.3f,2.3f),((BoxCollisionShape)children[0].getShape()).getHalfExtents(null));
            assertEquals(new Vector3f(0,.65f,-.25f),children[1].copyOffset(null));
            assertEquals(new Vector3f(.83f,.32f,.85f),((BoxCollisionShape)children[1].getShape()).getHalfExtents(null));
            for(int i=0;i<4;i++) {
                var wheel=body.getWheel(i);
                assertEquals(new Vector3f((i%2==0?-1:1)*(rules.width()/2-.05f),.15f,(i<2?1:-1)*rules.wheelBase()/2),wheel.getLocation());
                assertEquals(.38f,wheel.getRadius());assertEquals(.28f,wheel.getRestLength());
                assertEquals(14000,wheel.getMaxSuspensionForce());assertEquals(55,wheel.getSuspensionStiffness());
            }
            assertEquals(new Vector3f(0,1.55f,2.5f),world.muzzle(0));
            assertEquals(new Vector3f(-.53f,1.47f,2.28f),world.machineGunMuzzle(0,0));
        }
    }

    @ParameterizedTest @ValueSource(strings={"boss_foreman","boss_prefect","boss_emcee","boss_ash_shepherd","boss_director"})
    void eachBossSettlesAndAcceleratesOnFourRealWheels(String profileId) {
        var rules=VehicleRules.load();var profile=VehicleProfile.boss(profileId,rules);
        try(var world=new PhysicsWorld(rules)) {
            world.addStatic(new BoxCollisionShape(new Vector3f(150,.5f,150)),new Vector3f(0,-.5f,0),new Quaternion());
            var body=world.addVehicle(7,new Vector3f(0,profile.roadOffset()+.5f,0),new Quaternion(),profile);
            for(int tick=0;tick<360;tick++)world.step();
            assertEquals(4,world.wheelContacts(7),profileId+" must stand on all wheels");
            assertEquals(profile.mass(),body.getMass(),.001f);
            assertTrue(world.rotation(7).mult(Vector3f.UNIT_Y).y>.99f);
            var state=new VehicleState(7,profileId,false,Configs.load("combat",CombatRules.class));
            var driver=new VehicleController(world,state,rules);
            Vector3f start=world.position(7);
            for(int tick=0;tick<360;tick++){driver.drive(GAS);world.step();}
            assertTrue(world.position(7).z-start.z>10,profileId+" must accelerate under actual native mass");
            assertTrue(world.velocity(7).z>5&&world.velocity(7).z<rules.maxSpeed()*profile.speedMultiplier()+1);
            assertTrue(world.rotation(7).mult(Vector3f.UNIT_Y).y>.95f,"Straight driving must remain upright");
            assertEquals(4,world.wheelContacts(7));
        }
    }

    @Test void bossHighHullIsHitAndQueriedWithItsOwnGeometry() {
        var rules=VehicleRules.load();var profile=VehicleProfile.boss("boss_director",rules);
        try(var world=new PhysicsWorld(rules)) {
            var body=world.addVehicle(8,new Vector3f(0,2,0),new Quaternion(),profile);
            Vector3f from=new Vector3f(-12,7,0),to=new Vector3f(12,7,0);
            var hit=world.sweep(from,to,.25f,-1);
            assertNotNull(hit,"Rivet's old 2.3m preliminary radius must not discard the tall boss");
            assertEquals(8,hit.vehicleId());
            // Close to the cabin, its tall side is nearest. From x=-12 the wider
            // lower chassis corner is correctly nearer despite being below the ray.
            Vector3f nearCabin=new Vector3f(-4,7,0);
            Vector3f point=world.closestHullPoint(8,nearCabin);
            assertEquals(0,world.distanceToHull(8,point),.00001f);
            assertEquals(nearCabin.distance(point),world.distanceToHull(8,nearCabin),.00001f);
            assertTrue(point.y>6.9f);
            assertTrue(world.muzzle(8).z>profile.length()/2);
            assertNull(world.ray(world.muzzle(8),world.muzzle(8).add(0,0,30),-1),"Muzzle must clear own hull");
            world.impulse(8,new Vector3f(13200,0,0),Vector3f.ZERO,2);
            assertEquals(13200/profile.mass(),world.velocity(8).x,.00001f);
            body.setMass(profile.mass()*2);
            assertEquals(profile.mass()*2,world.mass(8),.001f,"Actual native mass remains authoritative");
        }
    }

    @Test void recoveryVolumeAndSupportIdsSurviveDestructibleRemoval() {
        var rules=VehicleRules.load();var profile=VehicleProfile.boss("boss_director",rules);
        try(var world=new PhysicsWorld(rules)) {
            world.addStatic("first",new BoxCollisionShape(new Vector3f(2,.5f,2)),new Vector3f(-20,-.5f,0),new Quaternion());
            world.addStatic("road",new BoxCollisionShape(new Vector3f(2,.5f,2)),new Vector3f(20,-.5f,0),new Quaternion());
            world.addStatic("gate",new BoxCollisionShape(new Vector3f(.1f,10,10)),new Vector3f(8,10,0),new Quaternion());
            world.addVehicle(0,new Vector3f(-30,3,0),new Quaternion());
            world.addVehicle(6,new Vector3f(30,3,0),new Quaternion(),profile);
            Vector3f candidate=new Vector3f(5.7f,3,0);
            assertTrue(world.freePose(0,candidate,new Quaternion()));
            assertFalse(world.freePose(6,candidate,new Quaternion()),"Large chassis must not recover inside a gate");
            var before=world.support(new Vector3f(20,1,0),2);assertNotNull(before);
            assertEquals("road",world.staticObjectId(before.surfaceId()));
            assertTrue(world.removeStatic("first"));assertTrue(world.removeStatic("gate"));
            assertFalse(world.removeStatic("gate"));
            assertTrue(world.freePose(6,candidate,new Quaternion()));
            var after=world.support(new Vector3f(20,1,0),2);assertNotNull(after);
            assertEquals(before.surfaceId(),after.surfaceId());
            world.addStatic("replacement",new BoxCollisionShape(new Vector3f(2,.5f,2)),new Vector3f(-20,-.5f,0),new Quaternion());
            var replacement=world.support(new Vector3f(-20,1,0),2);assertNotNull(replacement);
            assertTrue(replacement.surfaceId()>before.surfaceId(),"Removed surface IDs are never recycled");
        }
    }

    @Test void activeArenaBoundsAndRecoveryCostReplaceYardCoordinates() {
        var rules=VehicleRules.load();
        try(var world=new PhysicsWorld(rules)) {
            world.addStatic(new BoxCollisionShape(new Vector3f(180,.5f,180)),new Vector3f(180,-.5f,180),new Quaternion());
            world.addVehicle(0,new Vector3f(200,1,200),new Quaternion());
            for(int tick=0;tick<240;tick++)world.step();
            var state=new VehicleState(0,"test",true,Configs.load("combat",CombatRules.class));
            var driver=new VehicleController(world,state,rules,new Bounds(0,320,0,300,-12),0);
            assertEquals(VehicleController.Recovery.NONE,driver.prepare(VehicleCommand.NONE,240));
            Vector3f safe=world.position(0);
            world.teleport(0,new Vector3f(323,safe.y,200),new Quaternion());
            var result=driver.prepare(VehicleCommand.NONE,400);
            assertTrue(result.recovered());assertEquals(0,result.cost());assertEquals(1,state.recoveries);
            assertTrue(safe.distance(world.position(0))<.00001f);
        }
    }
}

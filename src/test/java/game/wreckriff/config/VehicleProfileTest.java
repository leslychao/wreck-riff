package game.wreckriff.config;

import com.jme3.math.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VehicleProfileTest {
    @Test void playerRosterUsesIndependentMassGeometryAndTurboTuning() {
        var rules=VehicleRules.load();
        float[][] expected={{800,1100,1,1,1,1},{1040,1900,24f/28f,34f/40f,.8f,.8f},{640,650,31f/28f,44f/40f,1.2f,1.15f}};
        for(int index=0;index<VehicleDefinition.values().length;index++) {
            var definition=VehicleDefinition.values()[index];var profile=definition.profile(rules);var row=expected[index];
            assertSame(definition,VehicleDefinition.forId(definition.id()));
            assertEquals(row[0],definition.maximumHp());assertEquals(row[1],profile.mass());
            assertEquals(row[2],profile.speedMultiplier(),.00001f);
            assertEquals(row[3],profile.turboMultiplier(),.00001f);
            assertEquals(row[4],profile.accelerationMultiplier());assertEquals(row[5],profile.turnMultiplier());
            assertFalse(definition.specialName().isBlank());
        }
        var truck=VehicleDefinition.GRINDER.profile(rules);var spark=VehicleDefinition.SPARK.profile(rules);
        assertEquals(3,truck.hullBoxes().size());assertEquals(3.1f,spark.length());
        assertTrue(truck.muzzle().y>truck.hullBounds().maxY());
        assertTrue(truck.muzzle().y>truck.grinderIntake().y+1);
        assertThrows(IllegalArgumentException.class,()->VehicleDefinition.forId("unknown"));
        assertThrows(IllegalArgumentException.class,()->VehicleProfile.player("boss_foreman",rules));
    }
    @Test void globalSpeedTuningScalesEveryPlayerProfileAndPreservesTheirOrder() {
        var configuration=Configs.gson().toJsonTree(VehicleRules.load()).getAsJsonObject();
        configuration.addProperty("maxSpeed",34);
        configuration.addProperty("turboSpeed",46);
        var rules=Configs.gson().fromJson(configuration,VehicleRules.class);
        float[][] expected={{34,46},{29.142857f,39.1f},{37.642857f,50.6f}};
        for(int index=0;index<VehicleDefinition.values().length;index++) {
            var profile=VehicleDefinition.values()[index].profile(rules);
            assertEquals(expected[index][0],rules.maxSpeed()*profile.speedMultiplier(),.00001f);
            assertEquals(expected[index][1],rules.turboSpeed()*profile.turboMultiplier(),.00001f);
        }
        assertTrue(VehicleDefinition.GRINDER.profile(rules).speedMultiplier()<1);
        assertTrue(VehicleDefinition.SPARK.profile(rules).speedMultiplier()>1);
    }
    @Test void bossProfilesMatchApprovedDimensionsAndPhysicalRatios() {
        var rules=VehicleRules.load();
        float[][] expected={{12,5,5.5f,.72f,.65f,.65f},{9,3.3f,3,1.2f,1.2f,1.1f},
                {8,4.2f,4.6f,1.05f,1.05f,1},{10,4.4f,4.8f,.8f,.8f,.75f},{14,5.2f,6,.85f,.75f,.7f}};
        var profiles=VehicleProfile.bosses(rules);
        assertEquals(5,profiles.stream().map(VehicleProfile::id).distinct().count());
        for(int i=0;i<profiles.size();i++) {
            var profile=profiles.get(i);var row=expected[i];
            assertEquals(row[0],profile.length());assertEquals(row[1],profile.width());assertEquals(row[2],profile.height());
            assertEquals(row[3],profile.speedMultiplier());assertEquals(row[4],profile.accelerationMultiplier());assertEquals(row[5],profile.turnMultiplier());
            assertEquals(1100*row[0]*row[1]/(4.6f*2.1f),profile.mass(),.002f);
            assertEquals(row[2],profile.hullBounds().maxY()-profile.hullBounds().minY(),.00001f);
            assertTrue(profile.fullBounds().minY()<=profile.hullBounds().minY());
            assertTrue(profile.fullBounds().maxZ()>=profile.muzzle().z);
            for(int wheel=0;wheel<4;wheel++) {
                Vector3f center=profile.wheelConnection(wheel);
                assertTrue(center.x>=profile.fullBounds().minX()&&center.x<=profile.fullBounds().maxX());
                assertTrue(profile.fullBounds().minY()<=center.y-profile.suspensionRestLength()*2-profile.wheelRadius()+.00001f);
            }
        }
    }
    @Test void profileDoesNotExposeMutableGeometry() {
        var profile=VehicleProfile.rivet(VehicleRules.load());
        profile.hullBoxes().getFirst().center().set(10,10,10);
        profile.hullBoxes().getFirst().extent().set(10,10,10);
        profile.muzzle().set(10,10,10);
        profile.wheelConnection(0).set(10,10,10);
        assertEquals(new Vector3f(0,.2f,0),profile.hullBoxes().getFirst().center());
        assertEquals(new Vector3f(0,.55f,2.5f),profile.muzzle());
        assertTrue(new Vector3f(-1,.15f,1.4f).distance(profile.wheelConnection(0))<.000001f);
        assertThrows(UnsupportedOperationException.class,()->profile.hullBoxes().clear());
        assertThrows(IllegalArgumentException.class,()->VehicleProfile.boss("missing",VehicleRules.load()));
    }
}

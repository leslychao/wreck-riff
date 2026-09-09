package game.wreckriff.config;

import com.jme3.math.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VehicleProfileTest {
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

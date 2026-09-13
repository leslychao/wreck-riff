package game.wreckriff.presentation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChaseCameraVolumeTest {
    @Test void sweepEnclosesNearCornersAtOrdinaryTurboAndUltrawideViews() {
        for(float aspect:new float[]{4f/3,16f/9,32f/9})for(float fov:new float[]{65,72}) {
            double halfHeight=.3*Math.tan(Math.toRadians(fov*.5));
            double corner=Math.sqrt(.3*.3+halfHeight*halfHeight*(1+aspect*aspect));
            assertEquals(corner,ChaseCamera.nearPlaneRadius(fov,aspect),.00001);
            assertTrue(ChaseCamera.nearPlaneRadius(fov,aspect)>.3);
        }
    }
}

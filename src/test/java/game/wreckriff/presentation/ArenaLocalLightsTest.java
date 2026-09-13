package game.wreckriff.presentation;

import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import game.wreckriff.arena.ArenaDefinition.Vec3;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArenaLocalLightsTest {
    @Test void renderCallbackOnlySamplesCameraAndNeverInvalidatesTheSceneGraph() {
        Node root=new Node("arena");
        var geometry=new com.jme3.scene.Geometry("service-gate-panel",new com.jme3.scene.shape.Box(1,1,1));
        root.attachChild(geometry);
        var lights=new ArenaLocalLights(root,java.util.List.of(new ArenaArt.LocalLight("lamp",new Vec3(0,8,0),new Vec3(1,.6f,.3f),30)));
        root.addControl(lights);
        var camera=new com.jme3.renderer.Camera(1920,1080);
        var view=new com.jme3.renderer.ViewPort("Main",camera);
        root.updateLogicalState(1f/60);root.updateGeometricState();
        lights.controlRender(null,view);
        assertEquals(0,root.getLocalLightList().size(),"Render must defer structural mutations until update");
        assertDoesNotThrow(()->geometry.checkCulling(camera));
        root.updateLogicalState(1f/60);root.updateGeometricState();
        assertEquals(1,geometry.getWorldLightList().size());
        camera.setLocation(new Vector3f(5000,0,5000));
        lights.controlRender(null,view);
        assertEquals(1,root.getLocalLightList().size());
        assertDoesNotThrow(()->geometry.checkCulling(camera),"Camera motion during render cannot dirty descendants");
        root.updateLogicalState(1f/60);root.updateGeometricState();
        assertEquals(0,geometry.getWorldLightList().size());
    }
    @Test void cameraMovementKeepsAConstantBudgetAndReleasesDistantFixtures() {
        var definitions=new ArrayList<ArenaArt.LocalLight>();
        for(int i=0;i<24;i++)definitions.add(new ArenaArt.LocalLight("lamp-"+i,new Vec3(i*25,8,0),new Vec3(1,.6f,.3f),30));
        Node root=new Node("arena");var lights=new ArenaLocalLights(root,definitions);
        lights.select(Vector3f.ZERO);assertEquals(8,root.getLocalLightList().size());
        assertEquals("lamp-0",root.getLocalLightList().get(0).getName());
        for(int frame=0;frame<600;frame++) {
            lights.select(new Vector3f(frame,0,0));assertTrue(root.getLocalLightList().size()<=ArenaLocalLights.MAX_ACTIVE);
        }
        lights.select(new Vector3f(5000,0,5000));assertEquals(0,root.getLocalLightList().size());
        lights.select(Vector3f.ZERO);assertEquals(8,root.getLocalLightList().size());
    }
}

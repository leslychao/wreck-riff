package game.wreckriff.presentation;

import com.jme3.bounding.BoundingBox;
import com.jme3.math.*;
import com.jme3.renderer.*;
import com.jme3.renderer.queue.*;
import com.jme3.scene.*;
import com.jme3.shadow.ShadowUtil;
import game.wreckriff.arena.ArenaDefinition;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShadowBoundsTest {
    @Test void damageStagesAndArenaReceiverBoundsStayFiniteAcrossThreeShadowCascades() {
        var assets=PresentationTestAssets.shared();Node scene=new Node("shadow-test");
        scene.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
        // Native colliders are irrelevant here: use the exact authored box surfaces and placements.
        for(var box:ArenaDefinition.load().boxes()) {
            Vector3f half=box.size().vector().mult(.5f);
            Geometry geometry=new Geometry(box.id(),SurfaceMesh.box(half.x,half.y,half.z,4));
            geometry.setMaterial(SurfaceMaterials.lit(assets,ColorRGBA.Gray,1,.1f));geometry.setLocalTranslation(box.center().vector());scene.attachChild(geometry);
        }
        Node car=VehicleVisual.create(assets,0);car.setLocalTranslation(0,1,0);scene.attachChild(car);
        Camera view=new Camera(1280,720);ViewPort viewport=new ViewPort("shadow-test",view);viewport.attachScene(scene);
        Camera sun=new Camera(2048,2048);sun.setParallelProjection(true);sun.setFrustumFar(180);
        sun.lookAtDirection(new Vector3f(-.72f,-.7f,.38f).normalizeLocal(),Vector3f.UNIT_Y);
        Vector3f[] corners=new Vector3f[8];for(int i=0;i<8;i++)corners[i]=new Vector3f();
        for(float hp:new float[]{1,.75f,.5f,.25f,0}) {
            VehicleVisual.updateDamage(car,hp);VehicleVisual.updateEffects(car,hp==.5f,hp==.25f);scene.updateGeometricState();
            scene.depthFirstTraversal(spatial->{if(spatial.getWorldBound()!=null) {
                var bound=spatial.getWorldBound();assertTrue(Vector3f.isValidVector(bound.getCenter()),spatial.getName());
                if(bound instanceof BoundingBox box)assertTrue(Float.isFinite(box.getXExtent())&&Float.isFinite(box.getYExtent())&&Float.isFinite(box.getZExtent()),spatial.getName());
            }});
            for(Vector3f eye:new Vector3f[]{new Vector3f(0,5,-9),new Vector3f(-75,9,-37),new Vector3f(26,14,3),new Vector3f(-6,4,7)}) {
                view.setFrustumPerspective(64,1280f/720,.1f,500);view.setLocation(eye);view.lookAt(new Vector3f(0,1,0),Vector3f.UNIT_Y);view.update();
                GeometryList receivers=new GeometryList(new OpaqueComparator());
                ShadowUtil.getGeometriesInCamFrustum(scene,view,RenderQueue.ShadowMode.Receive,receivers);
                assertTrue(receivers.size()>0);
                float[] splits={.1f,23,58,180};
                for(int split=0;split<3;split++) {
                    ShadowUtil.updateFrustumPoints(view,splits[split],splits[split+1],1,corners);
                    GeometryList casters=new GeometryList(new OpaqueComparator());
                    assertDoesNotThrow(()->ShadowUtil.updateShadowCamera(viewport,receivers,sun,corners,casters,2048));
                    for(int row=0;row<4;row++)for(int column=0;column<4;column++)assertTrue(Float.isFinite(sun.getViewProjectionMatrix().get(row,column)));
                }
            }
        }
    }
}

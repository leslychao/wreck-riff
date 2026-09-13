package game.wreckriff.presentation;

import com.jme3.bounding.BoundingBox;
import com.jme3.collision.CollisionResults;
import com.jme3.math.Ray;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.ui.MenuLayout;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GaragePresentationTest {
    @Test void carouselKeepsOneRealLitVehicleAndReusesEveryProfile() {
        try(var garage=new GaragePresentation(PresentationTestAssets.shared(),VehicleRules.load())) {
            var camera=new Camera(1280,720);
            garage.show("rivet");garage.update(.31f,camera,.44f,.14f);
            Node rivet=(Node)garage.node().getChild("garage-vehicle");
            assertEquals("rivet",rivet.getUserData("profileId"));
            assertNotNull(rivet.getChild("wheel-0"));
            assertEquals("Phong Lighting",((Geometry)rivet.getChild("paint")).getMaterial().getMaterialDef().getName());
            for(String profile:List.of("grinder","spark","rivet","spark","grinder","rivet")) {
                garage.show(profile);garage.update(.31f,camera,.44f,.14f);
                assertEquals(1,garage.node().getChildren().stream().filter(n->n.getName().equals("garage-vehicle")).count());
                assertEquals(profile,garage.node().getChild("garage-vehicle").getUserData("profileId"));
            }
            assertSame(rivet,garage.node().getChild("garage-vehicle"),"Browsing must reuse authored meshes and materials");
            assertEquals(3,garage.cachedVehicleCount());
        }
    }

    @Test void allModelsFitTheUnobstructedViewportAcrossWindowSizes() {
        try(var garage=new GaragePresentation(PresentationTestAssets.shared(),VehicleRules.load())) {
            for(int[] size:List.of(new int[]{640,480},new int[]{1280,720},new int[]{1920,1080},
                    new int[]{2560,1440},new int[]{3440,1440},new int[]{3840,2160})) {
                var camera=new Camera(size[0],size[1]);
                for(String profile:List.of("rivet","grinder","spark")) {
                    garage.show(profile);
                    for(int frame=0;frame<=72;frame++) {
                        float step=frame==0?0:.5f;
                        garage.update(step,camera,.44f,.15f);garage.node().updateGeometricState();
                        var bounds=(BoundingBox)garage.node().getChild("garage-vehicle").getWorldBound();
                        for(int x:new int[]{-1,1})for(int y:new int[]{-1,1})for(int z:new int[]{-1,1}) {
                            Vector3f corner=bounds.getCenter().add(x*bounds.getXExtent(),y*bounds.getYExtent(),z*bounds.getZExtent());
                            Vector3f screen=camera.getScreenCoordinates(corner);
                            assertTrue(screen.x>=size[0]*.44f&&screen.x<=size[0],profile+" overlaps navigation: "+screen);
                            assertTrue(screen.y>=size[1]*.15f&&screen.y<=size[1],profile+" overlaps footer: "+screen);
                            assertTrue(screen.z>0&&screen.z<1);
                        }
                    }
                }
            }
        }
    }

    @Test void presentationAnimatesWithoutReplacingGeometryOrRetainingTheSceneOnClose() {
        var garage=new GaragePresentation(PresentationTestAssets.shared(),VehicleRules.load());
        var scene=new Node("root");scene.attachChild(garage.node());
        var camera=new Camera(1920,1080);garage.show("grinder");garage.update(.31f,camera,.4f,0);
        Node model=(Node)garage.node().getChild("garage-vehicle");
        var paint=((Geometry)model.getChild("paint")).getMesh();
        Vector3f previous=camera.getLocation().clone(),previousDirection=model.getLocalRotation().mult(Vector3f.UNIT_Z);
        for(int frame=0;frame<60;frame++)garage.update(1f/60,camera,.4f,0);
        assertEquals(previous,camera.getLocation(),"A turn must not pump the camera distance or tracking centre");
        assertNotEquals(previousDirection,model.getLocalRotation().mult(Vector3f.UNIT_Z),"The car itself rotates");
        assertSame(paint,((Geometry)model.getChild("paint")).getMesh());
        assertThrows(IllegalArgumentException.class,()->garage.show("unknown"));
        assertSame(model,garage.node().getChild("garage-vehicle"));
        garage.close();garage.close();
        assertNull(garage.node().getParent());assertEquals(0,garage.cachedVehicleCount());
        assertEquals(0,garage.node().getQuantity());
    }

    @Test void allCarsCompleteOneContinuousTurnIn36SecondsIncludingLongFrames() {
        try(var garage=new GaragePresentation(PresentationTestAssets.shared(),VehicleRules.load())) {
            var camera=new Camera(1920,1080);
            for(String profile:List.of("rivet","grinder","spark")) {
                garage.show(profile);garage.update(0,camera,.44f,.15f);
                Node model=(Node)garage.node().getChild("garage-vehicle");
                Vector3f cameraLocation=camera.getLocation().clone();
                var cameraRotation=camera.getRotation().clone();
                assertVectorEquals(Vector3f.UNIT_Z,model.getLocalRotation().mult(Vector3f.UNIT_Z));
                for(Vector3f direction:List.of(Vector3f.UNIT_X,Vector3f.UNIT_Z.negate(),Vector3f.UNIT_X.negate(),Vector3f.UNIT_Z)) {
                    garage.update(9,camera,.44f,.15f);
                    assertVectorEquals(direction,model.getLocalRotation().mult(Vector3f.UNIT_Z));
                    assertEquals(cameraLocation,camera.getLocation());assertEquals(cameraRotation,camera.getRotation());
                    assertVectorEquals(direction,garage.node().getChild("garage-turntable").getLocalRotation().mult(Vector3f.UNIT_Z));
                }
            }
        }
    }

    @Test void switchingImmediatelyUsesTheLastRequestedCachedCarAndSettlesIn300Milliseconds() {
        try(var garage=new GaragePresentation(PresentationTestAssets.shared(),VehicleRules.load())) {
            var camera=new Camera(1280,720);
            garage.show("rivet");garage.update(8,camera,.44f,.15f);
            Node rivet=(Node)garage.node().getChild("garage-vehicle");
            garage.show("grinder");garage.update(.1f,camera,.44f,.15f);
            garage.show("spark");garage.show("rivet");
            assertSame(rivet,garage.node().getChild("garage-vehicle"));
            assertVectorEquals(Vector3f.UNIT_Z,rivet.getLocalRotation().mult(Vector3f.UNIT_Z));
            assertEquals(.55f,rivet.getLocalTranslation().x,1e-6f);
            garage.update(.15f,camera,.44f,.15f);
            assertEquals(.275f,rivet.getLocalTranslation().x,1e-6f);
            // A redundant selection during the entrance must not restart or enqueue it.
            garage.show("rivet");garage.update(.15f,camera,.44f,.15f);
            assertEquals(0,rivet.getLocalTranslation().x,1e-6f);
            assertEquals(3,garage.cachedVehicleCount());
            assertEquals(1,garage.node().getChildren().stream().filter(n->n.getName().equals("garage-vehicle")).count());
        }
    }

    @Test void animationIsIndependentOfFramePartitionAndRejectsInvalidFrameTimes() {
        try(var coarse=new GaragePresentation(PresentationTestAssets.shared(),VehicleRules.load());
            var fine=new GaragePresentation(PresentationTestAssets.shared(),VehicleRules.load())) {
            var cameraCoarse=new Camera(1280,720);var cameraFine=new Camera(1280,720);
            coarse.show("grinder");fine.show("grinder");
            coarse.update(12,cameraCoarse,.44f,.15f);
            for(int frame=0;frame<720;frame++)fine.update(1f/60,cameraFine,.44f,.15f);
            assertSamePose(coarse.node().getChild("garage-vehicle"),fine.node().getChild("garage-vehicle"));
            assertSamePose(coarse.node().getChild("workshop-fan-rotor"),fine.node().getChild("workshop-fan-rotor"));
            assertEquals(cameraCoarse.getLocation(),cameraFine.getLocation());
            for(float invalid:new float[]{-1,Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY})
                assertThrows(IllegalArgumentException.class,()->coarse.update(invalid,cameraCoarse,.44f,.15f));
        }
    }

    @Test void workshopMotionReusesBuffersAndWeldingSparksOnlyAppearInShortBursts() {
        try(var garage=new GaragePresentation(PresentationTestAssets.shared(),VehicleRules.load())) {
            var camera=new Camera(1280,720);garage.show("rivet");garage.update(0,camera,.44f,.15f);
            var fan=garage.node().getChild("workshop-fan-rotor");var fanRotation=fan.getLocalRotation().clone();
            var particles=(Geometry)garage.node().getChild("garage-haze");
            var positions=particles.getMesh().getFloatBuffer(VertexBuffer.Type.Position);
            var colors=particles.getMesh().getFloatBuffer(VertexBuffer.Type.Color);
            float hazeY=positions.get(1);
            assertEquals(0,colors.get(12*6*4+3));
            garage.update(5.95f,camera,.44f,.15f);
            assertTrue(colors.get(12*6*4+3)>0,"Welding must emit visible sparks");
            assertNotEquals(hazeY,positions.get(1));assertNotEquals(fanRotation,fan.getLocalRotation());
            garage.update(1,camera,.44f,.15f);
            assertEquals(0,colors.get(12*6*4+3),"Sparks are intermittent rather than constant flashing");
            assertSame(positions,particles.getMesh().getFloatBuffer(VertexBuffer.Type.Position));
            assertSame(colors,particles.getMesh().getFloatBuffer(VertexBuffer.Type.Color));
        }
    }

    private static void assertVectorEquals(Vector3f expected,Vector3f actual) {
        assertEquals(expected.x,actual.x,1e-5f);assertEquals(expected.y,actual.y,1e-5f);assertEquals(expected.z,actual.z,1e-5f);
    }

    private static void assertSamePose(Spatial expected,Spatial actual) {
        assertVectorEquals(expected.getLocalTranslation(),actual.getLocalTranslation());
        assertVectorEquals(expected.getLocalRotation().mult(Vector3f.UNIT_Z),actual.getLocalRotation().mult(Vector3f.UNIT_Z));
    }

    @Test void workshopCoversCameraRaysAtUltrawideAndExtremeUiScales() {
        try(var garage=new GaragePresentation(PresentationTestAssets.shared(),VehicleRules.load())) {
            Node workshop=(Node)garage.node().getChild("garage-workshop");
            for(int[] size:List.of(new int[]{3840,1080},new int[]{640,480},new int[]{1280,720},
                    new int[]{3440,1440},new int[]{3840,2160})) {
                var camera=new Camera(size[0],size[1]);
                for(float scale:new float[]{.8f,1.5f})for(var page:MenuLayout.Kind.values()) {
                    var layout=MenuLayout.compute(size[0],size[1],scale,page==MenuLayout.Kind.STANDARD,page);
                    float left=page==MenuLayout.Kind.STANDARD?0:Math.min(.8f,(layout.panel().right()+16*layout.scale())/size[0]);
                    float bottom=page==MenuLayout.Kind.VEHICLE?.17f:0;
                    for(String profile:List.of("rivet","grinder","spark")) {
                        garage.show(profile);
                        for(float step:new float[]{.01f,.15f,.16f,2f}) {
                            garage.update(step,camera,left,bottom);garage.node().updateGeometricState();
                            // Sample the full framebuffer, including the exposed border around standard menus.
                            for(int x=0;x<=12;x++)for(int y=0;y<=8;y++) {
                                var pixel=new Vector2f(.5f+(size[0]-1)*x/12f,.5f+(size[1]-1)*y/8f);
                                Vector3f near=camera.getWorldCoordinates(pixel,0),far=camera.getWorldCoordinates(pixel,1);
                                var ray=new Ray(near,far.subtract(near).normalizeLocal());
                                ray.setLimit(near.distance(far));
                                var collisions=new CollisionResults();workshop.collideWith(ray,collisions);
                                assertTrue(collisions.size()>0,"Viewport exposes the workshop boundary: "+size[0]+"x"+size[1]
                                        +" scale="+scale+" page="+page+" profile="+profile+" pixel="+pixel+" ray="+ray);
                            }
                        }
                    }
                }
            }
        }
    }
}

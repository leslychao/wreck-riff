package game.wreckriff.presentation;

import com.jme3.math.ColorRGBA;
import com.jme3.scene.*;
import game.wreckriff.config.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BossTelegraphVisualTest {
    @Test void allFiveBossesHaveContinuousAmberToWarmPreparationOnTheirExistingLightsAtMinimumEffects() {
        for(var profile:VehicleProfile.bosses(VehicleRules.load())) {
            Node model=VehicleVisual.create(PresentationTestAssets.shared(),profile,0);
            for(float hp:new float[]{1,.75f,.5f,.25f}) {
                VehicleVisual.updateDamage(model,hp);VehicleVisual.updateEffects(model,true,true);
                for(boolean vulnerable:new boolean[]{false,true}) {
                    VehicleVisual.updateBossPhase(model,2,vulnerable);Map<Geometry,Mesh> meshes=meshes(model);
                    var lamps=(Geometry)model.getChild("headlights");float[] damage=colors(lamps.getMesh());
                    var core=(Geometry)model.getChild("service-core");var coreMaterial=core.getMaterial();
                    var coverCull=model.getChild("service-cover").getLocalCullHint();var coreCull=core.getLocalCullHint();
                    ColorRGBA previous=null;
                    for(long tick:new long[]{120,150,180,210,239}) {
                        VehicleVisual.updateBossTelegraph(model,tick,120,240,false);
                        var color=(ColorRGBA)lamps.getMaterial().getParam("Color").getValue();
                        assertTrue(color.r>color.g&&color.g>color.b,"Warning stays amber/warm without flashing or bloom");
                        if(previous!=null)assertTrue(luminance(color)>luminance(previous),"Charge brightness grows continuously from authoritative ticks");
                        previous=color.clone();assertEquals(ColorRGBA.Black,lamps.getMaterial().getParam("GlowColor").getValue());
                        assertEquals(false,lamps.getMaterial().getParam("VertexColor").getValue(),
                                "A gameplay warning must not be multiplied by the 1.5% damaged-lamp blackout");
                        assertArrayEquals(damage,colors(lamps.getMesh()),"Damaged lamps retain their actual per-vertex blackout");
                        assertEquals(coreCull,core.getLocalCullHint());assertEquals(coverCull,model.getChild("service-cover").getLocalCullHint());
                        assertSame(coreMaterial,core.getMaterial());assertEquals(meshes,meshes(model));
                        assertBudget(model);
                    }
                }
            }
        }
    }

    @Test void expiryAndDeathRestoreTheAuthoritativeModeWithoutOpeningAWeakPoint() {
        for(var profile:VehicleProfile.bosses(VehicleRules.load())) {
            Node model=VehicleVisual.create(PresentationTestAssets.shared(),profile,0);var lamps=(Geometry)model.getChild("headlights");
            for(int phase=0;phase<3;phase++) {
                VehicleVisual.updateBossPhase(model,phase,false);var base=((ColorRGBA)lamps.getMaterial().getParam("Color").getValue()).clone();
                VehicleVisual.updateBossTelegraph(model,180,120,240,true);assertNotEquals(base,lamps.getMaterial().getParam("Color").getValue());
                VehicleVisual.updateBossTelegraph(model,240,120,240,true);assertEquals(base,lamps.getMaterial().getParam("Color").getValue());
                assertEquals(true,lamps.getMaterial().getParam("VertexColor").getValue(),"Ordinary damaged lamp blackout returns after warning");
                assertEquals(Spatial.CullHint.Always,model.getChild("service-core").getLocalCullHint());
            }
            VehicleVisual.updateDamage(model,0);VehicleVisual.updateBossPhase(model,2,false);
            var dead=((ColorRGBA)lamps.getMaterial().getParam("Color").getValue()).clone();
            VehicleVisual.updateBossTelegraph(model,180,120,240,true);assertEquals(dead,lamps.getMaterial().getParam("Color").getValue());
            assertEquals(ColorRGBA.Black,lamps.getMaterial().getParam("GlowColor").getValue());
        }
    }

    private static float luminance(ColorRGBA color) {return .2126f*color.r+.7152f*color.g+.0722f*color.b;}
    private static Map<Geometry,Mesh> meshes(Node model) {Map<Geometry,Mesh> result=new IdentityHashMap<>();model.depthFirstTraversal(spatial->{if(spatial instanceof Geometry geometry)result.put(geometry,geometry.getMesh());});return result;}
    private static float[] colors(Mesh mesh) {var buffer=mesh.getFloatBuffer(VertexBuffer.Type.Color).duplicate().rewind();float[] result=new float[buffer.remaining()];buffer.get(result);return result;}
    private static void assertBudget(Node model) {
        int[] draws={0},triangles={0};model.depthFirstTraversal(spatial->{if(spatial instanceof Geometry geometry) {
            for(Spatial parent=geometry;parent!=null;parent=parent.getParent())if(parent.getLocalCullHint()==Spatial.CullHint.Always)return;
            draws[0]++;triangles[0]+=geometry.getMesh().getTriangleCount();
        }});assertTrue(draws[0]<=22);assertTrue(triangles[0]<=12000);
    }
}

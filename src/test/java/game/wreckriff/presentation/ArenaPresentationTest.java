package game.wreckriff.presentation;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.math.*;
import com.jme3.scene.*;
import game.wreckriff.arena.*;
import game.wreckriff.simulation.*;
import org.junit.jupiter.api.Test;
import java.nio.FloatBuffer;
import static org.junit.jupiter.api.Assertions.*;

class ArenaPresentationTest {
    @Test void hazardFeedbackUsesExactAuthoritativePhasesAndFreezesWithTick() {
        Fixture fixture=new Fixture(new SurfaceWorld(0));
        ArenaDefinition.Hazard hazard=fixture.definition.hazard();
        fixture.session.tick=hazard.offTicks()-1;fixture.visual.update(.02f);
        assertEquals("OFF",fixture.feedback().getUserData("hazardPhase"));
        assertEquals(Spatial.CullHint.Always,fixture.arcs().getCullHint());
        fixture.session.tick=hazard.offTicks();fixture.visual.update(.02f);
        assertEquals("WARNING",fixture.feedback().getUserData("hazardPhase"));
        ColorRGBA original=fixture.indicatorColor().clone();
        for(int i=0;i<100;i++)fixture.visual.update(.1f);
        assertEquals(original,fixture.indicatorColor(),"Render time must not advance warning brightness");
        assertEquals(Spatial.CullHint.Always,fixture.arcs().getCullHint());
        fixture.session.tick=hazard.offTicks()+hazard.warningTicks();fixture.visual.update(.02f);
        assertEquals("ACTIVE",fixture.feedback().getUserData("hazardPhase"));
        assertEquals(Spatial.CullHint.Inherit,fixture.arcs().getLocalCullHint());
        assertEquals(Spatial.CullHint.Dynamic,fixture.arcs().getCullHint(),
                "Active arcs participate in ordinary camera culling instead of staying hidden");
        assertTrue(fixture.indicatorColor().b>fixture.indicatorColor().r);
        fixture.session.tick=hazard.periodTicks();fixture.visual.update(.02f);
        assertEquals("OFF",fixture.feedback().getUserData("hazardPhase"));
        assertEquals(Spatial.CullHint.Always,fixture.arcs().getCullHint());
    }
    @Test void hazardPresentationDoesNotCastFakeShadowsOrQueryPhysicsEveryRender() {
        SurfaceWorld world=new SurfaceWorld(6);Fixture fixture=new Fixture(world);
        for(int i=0;i<20;i++)fixture.visual.update(.016f);
        assertNull(fixture.feedback().getChild("vehicle-contact-shadows"));
        assertEquals(0,world.rayCount,"Real directional shadow maps replace per-car surface projections");
    }
    private static final class Fixture {
        final ArenaDefinition definition=ArenaDefinition.load();
        final MatchSession session=new MatchSession(42,360);
        final Node root=new Node("arena");
        final ArenaPresentation visual;
        Fixture(SurfaceWorld world) {
            for(int i=1;i<5;i++)session.vehicle(i).hp=0;
            visual=ArenaPresentation.attach(new DesktopAssetManager(true),root,world,session,definition,new ArenaSystems(session,definition));
        }
        Node feedback(){return(Node)root.getChild("arena-feedback");}
        Geometry arcs(){return(Geometry)feedback().getChild("hazard-active-arcs");}
        ColorRGBA indicatorColor(){return(ColorRGBA)((Geometry)feedback().getChild("hazard-indicator")).getMaterial().getParam("Color").getValue();}
    }
    private static final class SurfaceWorld implements WorldQuery {
        final float floor;
        float slope;
        boolean platformEdge,otherVehicle,noSurface;
        int rayCount,lastIgnored;
        SurfaceWorld(float floor){this.floor=floor;}
        public Vector3f position(int id){return new Vector3f(0,floor+.85f,0);}
        public Vector3f velocity(int id){return Vector3f.ZERO;}
        public Quaternion rotation(int id){return Quaternion.IDENTITY;}
        public boolean grounded(int id){return true;}
        public float mass(int id){return 1100;}
        public Hit ray(Vector3f from,Vector3f to,int ignored) {
            rayCount++;lastIgnored=ignored;if(noSurface)return null;
            float height=platformEdge&&from.x>.5f?0:floor+slope*from.z;
            if(height<to.y||height>from.y)return null;
            return new Hit(otherVehicle?1:-1,new Vector3f(from.x,height,from.z),new Vector3f(0,1,-slope).normalizeLocal(),
                    (from.y-height)/(from.y-to.y));
        }
        public Hit sweep(Vector3f a,Vector3f b,float radius,int ignored){return null;}
        public boolean visible(Vector3f a,Vector3f b,int target){return true;}
        public float distanceToHull(int id,Vector3f point){return 0;}
        public void impulse(int id,Vector3f impulse){}
    }
}

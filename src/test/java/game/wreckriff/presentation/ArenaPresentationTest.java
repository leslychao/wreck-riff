package game.wreckriff.presentation;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.math.*;
import com.jme3.scene.*;
import game.wreckriff.arena.*;
import game.wreckriff.simulation.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArenaPresentationTest {
    @Test void hazardFeedbackUsesExactAuthoritativePhasesAndFreezesWithTick() {
        Fixture fixture=new Fixture();
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
    @Test void hazardPresentationContainsOnlyIndicatorsAfterShadowRendererReplacement() {
        Fixture fixture=new Fixture();
        for(int i=0;i<20;i++)fixture.visual.update(.016f);
        assertNull(fixture.feedback().getChild("vehicle-contact-shadows"));
        assertEquals(2,fixture.feedback().getQuantity(),"Hazard frame and live arcs are the only arena feedback geometry");
    }
    private static final class Fixture {
        final ArenaDefinition definition=ArenaDefinition.load();
        final MatchSession session=new MatchSession(42,360);
        final Node root=new Node("arena");
        final ArenaPresentation visual;
        Fixture() {
            for(int i=1;i<5;i++)session.vehicle(i).hp=0;
            visual=ArenaPresentation.attach(new DesktopAssetManager(true),root,session,definition,new ArenaSystems(session,definition));
        }
        Node feedback(){return(Node)root.getChild("arena-feedback");}
        Geometry arcs(){return(Geometry)feedback().getChild("hazard-active-arcs");}
        ColorRGBA indicatorColor(){return(ColorRGBA)((Geometry)feedback().getChild("hazard-indicator")).getMaterial().getParam("Color").getValue();}
    }
}

package game.wreckriff.ui;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.font.BitmapText;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import game.wreckriff.app.ScreenFlow;
import game.wreckriff.combat.AbilityId;
import game.wreckriff.combat.WeaponType;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RosterHudTest {
    @Test void vehicleSelectionReturnsToItsActualOriginAndLoadingDropsOldParents() {
        var flow=new ScreenFlow();flow.maps();flow.open(ScreenFlow.Screen.VEHICLES);
        flow.back();assertEquals(ScreenFlow.Screen.MAPS,flow.screen());
        flow.menu();flow.open(ScreenFlow.Screen.VEHICLES);flow.loading();flow.running();
        flow.pause();flow.resume();assertEquals(ScreenFlow.Screen.RUNNING,flow.screen());
    }

    @Test void specialIconBindingAndCooldownSurviveViewportResize() {
        try(var view=new HudView(new DesktopAssetManager(true),new Node())) {
            view.resize(1920,1080,1);view.setSpecial("grinder","Мясорубка","C");
            var state=new HudView.Snapshot(new HudView.Vitals(1040,1040,1,HudView.Effect.NONE),WeaponType.HOMING,Map.of(),
                    Map.of(AbilityId.SPECIAL,new HudView.AbilityStatus(1,19.5f,20)),"",null,false,"","",
                    new RadarProjection.Observer(0,0,new RadarProjection.Heading(0,1),0),List.of());
            view.update(state);
            assertInstanceOf(Geometry.class,view.root().getChild("ability-special-icon"));
            assertEquals("20",((BitmapText)view.root().getChild("ability-special-cooldown-text")).getText());
            view.resize(640,480,1.5f);
            assertEquals("Мясорубка  [C]",((BitmapText)view.root().getChild("special-binding")).getText());
            assertEquals("1040 / 1040",((BitmapText)view.root().getChild("health-value")).getText());
            assertEquals(3,view.layout().abilitySlots().size());
            assertFalse(view.layout().health().overlaps(view.layout().abilities()));
            assertFalse(view.layout().weapons().overlaps(view.layout().abilities()));
        }
    }
}

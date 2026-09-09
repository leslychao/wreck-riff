package game.wreckriff.ui;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EnemyHealthBarsTest {
    @Test void compactPixelSizeScalesWithResolutionAndUiWhileHeightStaysReadable() {
        Node gui=new Node("gui"),menu=new Node("scaled-menu");gui.attachChild(menu);menu.setLocalScale(2);
        try(var bars=new EnemyHealthBars(new DesktopAssetManager(true),gui)) {
            bars.resize(1920,1080,1);bars.update(List.of(new EnemyHealthBars.Marker(2,960,600,.5f)));
            Node bar=(Node)bars.root().getChild(0);Geometry back=(Geometry)bar.getChild("background");
            assertSame(gui,bars.root().getParent());assertEquals(1,bars.root().getLocalScale().x);
            assertEquals(48,back.getLocalScale().x);assertEquals(5,back.getLocalScale().y);
            assertEquals(936,bar.getLocalTranslation().x);assertEquals(606,bar.getLocalTranslation().y);
            bars.resize(1280,720,1);
            assertEquals(32,back.getLocalScale().x);assertEquals(4,back.getLocalScale().y);
            bars.resize(3840,2160,1.5f);
            assertEquals(144,back.getLocalScale().x);assertEquals(15,back.getLocalScale().y);
            assertSame(bar,bars.root().getChild(0));
        }
    }

    @Test void damageRepairVisibilityReuseAndMatchCleanupKeepOnePairOfQuadsPerId() {
        Node gui=new Node("gui");
        var bars=new EnemyHealthBars(new DesktopAssetManager(true),gui);bars.resize(1920,1080,1);
        bars.update(List.of(new EnemyHealthBars.Marker(7,400,300,1)));
        Node bar=(Node)bars.root().getChild(0);Geometry back=(Geometry)bar.getChild("background"),health=(Geometry)bar.getChild("health");
        assertEquals(48,health.getLocalScale().x);
        bars.update(List.of(new EnemyHealthBars.Marker(7,500,320,.25f)));
        assertEquals(12,health.getLocalScale().x);
        bars.update(List.of(new EnemyHealthBars.Marker(7,500,320,.75f),new EnemyHealthBars.Marker(9,800,320,1)));
        assertEquals(36,health.getLocalScale().x);assertSame(bar,bars.root().getChild(0));
        Node second=(Node)bars.root().getChild(1);
        assertSame(back.getMesh(),health.getMesh());assertSame(health.getMesh(),((Geometry)second.getChild("health")).getMesh());
        assertSame(health.getMaterial(),((Geometry)second.getChild("health")).getMaterial());
        bars.update(List.of());assertEquals(Spatial.CullHint.Always,bar.getLocalCullHint());
        bars.update(List.of(new EnemyHealthBars.Marker(7,500,320,0)));assertEquals(Spatial.CullHint.Always,bar.getLocalCullHint());
        bars.update(List.of(new EnemyHealthBars.Marker(7,500,320,1)));assertSame(bar,bars.root().getChild(0));
        assertEquals(Spatial.CullHint.Inherit,bar.getLocalCullHint());assertEquals(2,bars.retainedCount());
        bars.setVisible(false);assertEquals(Spatial.CullHint.Always,bars.root().getLocalCullHint());
        bars.setVisible(true);assertEquals(Spatial.CullHint.Inherit,bars.root().getLocalCullHint());
        bars.clear();assertEquals(0,bars.retainedCount());assertEquals(0,bars.root().getQuantity());
        bars.update(List.of(new EnemyHealthBars.Marker(7,500,320,1)));assertNotSame(bar,bars.root().getChild(0));
        bars.close();assertNull(bars.root().getParent());assertEquals(0,bars.retainedCount());assertEquals(0,gui.getQuantity());
    }

    @Test void fillIsClampedAndEdgeBarsRemainInsideFramebuffer() {
        try(var bars=new EnemyHealthBars(new DesktopAssetManager(true),new Node("gui"))) {
            bars.resize(1920,1080,1);bars.update(List.of(new EnemyHealthBars.Marker(2,1919,1079,2)));
            Node bar=(Node)bars.root().getChild(0);
            assertEquals(1872,bar.getLocalTranslation().x);assertEquals(1075,bar.getLocalTranslation().y);
            assertEquals(48,bar.getChild("health").getLocalScale().x);
            assertEquals(0,new EnemyHealthBars.Marker(1,0,0,-1).healthFraction());
            assertThrows(IllegalArgumentException.class,()->new EnemyHealthBars.Marker(1,0,0,Float.NaN));
        }
    }
}

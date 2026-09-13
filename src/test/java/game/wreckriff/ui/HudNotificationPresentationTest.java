package game.wreckriff.ui;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.font.BitmapText;
import com.jme3.scene.Node;
import game.wreckriff.diagnostics.UiReviewFixtures;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HudNotificationPresentationTest {
    @Test void noticesHaveReadableShadowButNoOpaqueRectangleAndClearTogether() {
        try (var hud = new HudView(new DesktopAssetManager(true), new Node())) {
            hud.resize(640, 480, 1.5f);
            hud.setPickupReceipt("Homing +2");
            hud.setSubtitles("Противник уже на арене");
            hud.update(UiReviewFixtures.hud("rivet", 0));
            assertNull(hud.root().getChild("notice-panel"));
            for (String name : new String[]{"notification", "encounter-subtitle", "pickup-receipt"}) {
                var text = (BitmapText) hud.root().getChild(name);
                var shadow = (BitmapText) hud.root().getChild(name + "-shadow");
                assertNotNull(shadow, name);
                assertEquals(text.getText(), shadow.getText());
                assertTrue(shadow.getLocalTranslation().z < text.getLocalTranslation().z);
                assertTrue(shadow.getColor().r < .01f);
                assertEquals(text.getLineCount(), shadow.getLineCount());
            }
            hud.setPickupReceipt("");
            hud.setSubtitles("");
            hud.update(UiReviewFixtures.hud("rivet", 1));
            for (String name : new String[]{"notification", "encounter-subtitle", "pickup-receipt"}) {
                assertEquals("", ((BitmapText) hud.root().getChild(name)).getText());
                assertEquals("", ((BitmapText) hud.root().getChild(name + "-shadow")).getText());
            }
        }
    }
}

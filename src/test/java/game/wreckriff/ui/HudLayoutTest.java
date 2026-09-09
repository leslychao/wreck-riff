package game.wreckriff.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HudLayoutTest {
    @Test void everyPanelFitsWithoutOverlapFromSmallWindowToFourK() {
        int[][] sizes={{640,480},{1280,720},{1920,1080},{2560,1440},{3440,1440},{3840,2160}};
        for(int[] size:sizes) for(float scale:new float[]{.8f,1f,1.5f}) {
            HudLayout layout=HudLayout.compute(size[0],size[1],scale);
            for(UiBounds box:layout.panels()) {
                assertTrue(box.x()>=0 && box.y()>=0 && box.right()<=size[0] && box.top()<=size[1],box.toString());
            }
            assertFalse(layout.health().overlaps(layout.weapons()));
            assertFalse(layout.health().overlaps(layout.abilities()));
            assertFalse(layout.weapons().overlaps(layout.abilities()));
            assertFalse(layout.objective().overlaps(layout.radar()));
            assertTrue(layout.fontSize()>=14);
        }
    }

    @Test void smallWindowReflowsWeaponsAndDefaultFullHdStaysCompact() {
        assertEquals(3,HudLayout.compute(640,480,1).weaponColumns());
        HudLayout full=HudLayout.compute(1920,1080,1);
        assertEquals(6,full.weaponColumns());
        assertTrue(full.panels().stream().mapToDouble(UiBounds::area).sum()<1920*1080*.1);
        assertTrue(full.weapons().height()<=120);
        assertTrue(full.health().height()<=120);
        assertEquals(6,full.weaponSlots().size());
    }
}

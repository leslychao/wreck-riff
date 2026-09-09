package game.wreckriff.ui;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class UiFocusModelTest {
    private UiFocusModel.Item item(String id,float x,float y,boolean enabled) {
        return new UiFocusModel.Item(id,new UiBounds(x,y,100,40),enabled);
    }
    @Test void rebuildPreservesStableKeyAndSpatialNavigationSkipsDisabledItems() {
        var focus=new UiFocusModel();
        var items=List.of(item("a",0,80,true),item("b",120,80,true),item("c",0,0,true),item("d",120,0,false));
        focus.replace(items); focus.move(UiFocusModel.Direction.RIGHT); assertEquals("b",focus.selectedId());
        focus.replace(List.of(items.get(2),items.get(1),items.get(0),items.get(3)));
        assertEquals("b",focus.selectedId());
        focus.move(UiFocusModel.Direction.DOWN); assertEquals("c",focus.selectedId());
        assertFalse(focus.select("d"));
    }
    @Test void emptyAndFullyDisabledViewsCannotActivateASelection() {
        var focus=new UiFocusModel();
        focus.replace(List.of(item("disabled",0,0,false)));
        assertNull(focus.selectedId()); focus.move(UiFocusModel.Direction.RIGHT);
        assertNull(focus.selectedId());
    }
    @Test void scrollKeepsFocusedRowVisibleAndClampsAfterResize() {
        var scroll=new UiScrollModel(); scroll.resize(800,200); scroll.ensureVisible(480,40);
        assertEquals(320,scroll.offset()); scroll.scroll(999); assertEquals(600,scroll.offset());
        scroll.resize(300,200); assertEquals(100,scroll.offset());
        scroll.ensureVisible(0,40); assertEquals(0,scroll.offset());
    }
}

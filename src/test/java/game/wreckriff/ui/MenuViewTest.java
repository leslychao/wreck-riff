package game.wreckriff.ui;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.font.BitmapText;
import com.jme3.scene.Node;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MenuViewTest {
    private static final DesktopAssetManager ASSETS=new DesktopAssetManager(true);
    @Test void allResolutionsKeepPinnedRegionsSeparateAndPhysicalTextReadable() {
        for(int[] size:new int[][]{{640,480},{1280,720},{1920,1080},{2560,1440},{3440,1440},{3840,2160}}) {
            for(float scale:new float[]{.8f,1,1.5f}) {
                var layout=MenuLayout.compute(size[0],size[1],scale,true);
                assertTrue(layout.content().height()>=Math.max(108,132*layout.scale()),"A complete arena row must fit at "+size[0]+"x"+size[1]);
                assertFalse(layout.content().overlaps(layout.footer()));assertFalse(layout.content().overlaps(layout.tabs()));
                for(UiBounds bounds:List.of(layout.panel(),layout.header(),layout.tabs(),layout.content(),layout.footer(),layout.status())) {
                    assertTrue(bounds.x()>=0&&bounds.y()>=0&&bounds.right()<=size[0]&&bounds.top()<=size[1],bounds.toString());
                }
                assertTrue(layout.fontSize()>=14);
            }
        }
    }
    @Test void keyboardReachesOffscreenActionsAndReturnRestoresFocusAndScroll() {
        var ui=new GameUi(ASSETS,new Node());var menu=new MenuView(ui);menu.resize(640,480,1);
        var rows=new ArrayList<MenuView.Row>();var activated=new AtomicInteger();
        for(int i=0;i<14;i++){int id=i;rows.add(new MenuView.Action("row:"+i,"Действие "+i,()->activated.set(id)));}
        var back=List.of(new MenuView.Action("back","НАЗАД",()->{}));
        menu.show("list","СПИСОК","",List.of(),rows,back,"row:0");
        for(int i=0;i<9;i++)menu.move(UiFocusModel.Direction.DOWN);
        assertEquals("row:9",menu.selectedId());assertTrue(menu.scrollOffset()>0);assertNotNull(ui.root().getChild("row:9"));
        menu.activate();assertEquals(9,activated.get());float offset=menu.scrollOffset();
        menu.show("other","ДРУГОЙ ЭКРАН","",List.of(),List.of(),back,"back");
        menu.show("list","СПИСОК","",List.of(),rows,back,null);
        assertEquals("row:9",menu.selectedId());assertEquals(offset,menu.scrollOffset());
        ui.root().depthFirstTraversal(spatial->{if(spatial instanceof BitmapText text)assertTrue(text.getSize()>=14);});
    }
    @Test void confirmationAlwaysStartsOnCancelAndReadOnlyRowsDoNotActivateFooter() {
        var ui=new GameUi(ASSETS,new Node());var menu=new MenuView(ui);var calls=new AtomicInteger();
        var actions=List.of(new MenuView.Action("cancel","ОТМЕНА",()->{}),new MenuView.Action("accept","ПОДТВЕРДИТЬ",calls::incrementAndGet));
        menu.show("confirm:test","ПОДТВЕРЖДЕНИЕ","",List.of(),List.of(new MenuView.Text("body","Проверяем последствие.")),actions,"cancel");
        menu.move(UiFocusModel.Direction.RIGHT);assertEquals("accept",menu.selectedId());
        menu.show("other","ДРУГОЙ","",List.of(),List.of(),actions,"cancel");
        menu.show("confirm:test","ПОДТВЕРЖДЕНИЕ","",List.of(),List.of(new MenuView.Text("body","Проверяем последствие.")),actions,"cancel");
        assertEquals("cancel",menu.selectedId());menu.move(UiFocusModel.Direction.UP);assertEquals("body",menu.selectedId());
        menu.activate();assertEquals(0,calls.get());
    }
    @Test void slidersClampAtBothEndsInsteadOfWrapping() {
        var slider=new MenuView.Slider("volume","Громкость",1,0,1,.1f,"100%",ignored->{});
        assertEquals(1,slider.quantized(1.1));assertEquals(0,slider.quantized(-.1));assertEquals(.4f,slider.quantized(.41),.0001);
    }
    @Test void resizingKeepsTheSelectedOffscreenActionVisibleAndActivatable() {
        var ui=new GameUi(ASSETS,new Node());var menu=new MenuView(ui);menu.resize(1920,1080,1);
        var rows=new ArrayList<MenuView.Row>();var activated=new AtomicInteger(-1);
        for(int i=0;i<14;i++){int value=i;rows.add(new MenuView.Action("row:"+i,"Действие "+i,()->activated.set(value)));}
        menu.show("resize","СПИСОК","",List.of(),rows,List.of(),"row:0");
        for(int i=0;i<9;i++)menu.move(UiFocusModel.Direction.DOWN);
        menu.resize(640,480,1.5f);
        assertEquals("row:9",menu.selectedId());assertNotNull(ui.root().getChild("row:9"));
        menu.activate();assertEquals(9,activated.get());
    }
}

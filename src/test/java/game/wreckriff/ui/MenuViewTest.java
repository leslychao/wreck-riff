package game.wreckriff.ui;

import game.wreckriff.audio.UiCue;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.font.BitmapText;
import com.jme3.scene.Node;
import game.wreckriff.arena.ArenaRegistry;
import game.wreckriff.config.VehicleDefinition;
import game.wreckriff.config.VehicleRules;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MenuViewTest {
    @Test void feedbackDescribesAcceptedActionsAndSilentNoOps() {
        var ui=new GameUi(ASSETS,new Node());var menu=new MenuView(ui);var cues=new ArrayList<UiCue>();
        var accepted=new AtomicInteger();menu.onFeedback(cues::add);
        menu.show("feedback","Feedback","",List.of(),List.of(
                new MenuView.Action("first","First",accepted::incrementAndGet),
                new MenuView.Action("disabled","Disabled","",false,accepted::incrementAndGet),
                new MenuView.Choice("choice","Choice",List.of("Low","High"),1,ignored->accepted.incrementAndGet())),
                List.of(new MenuView.Action("back","Back",UiCue.BACK,accepted::incrementAndGet)),"first");
        assertTrue(cues.isEmpty(),"Initial/programmatic focus is silent");
        menu.move(UiFocusModel.Direction.UP);menu.click(-1,-1);
        var disabled=ui.root().getChild("disabled").getLocalTranslation();menu.click(disabled.x+1,disabled.y+1);
        assertTrue(cues.isEmpty());assertEquals(0,accepted.get());
        menu.activate();assertEquals(List.of(UiCue.CONFIRM),cues);assertEquals(1,accepted.get());
        menu.focus("choice");menu.move(UiFocusModel.Direction.RIGHT);menu.activate();
        assertEquals(1,cues.size(),"A clamped choice does not claim confirmation");
        menu.move(UiFocusModel.Direction.LEFT);assertEquals(UiCue.CHANGE,cues.getLast());
        menu.focus("back");menu.activate();assertEquals(UiCue.BACK,cues.getLast());
        cues.clear();menu.focus("first");menu.cycle(false);
        assertEquals(List.of(UiCue.NAVIGATE),cues);
        var back=ui.root().getChild("back").getLocalTranslation();menu.hover(back.x+1,back.y+1);menu.hover(back.x+2,back.y+2);
        assertEquals(List.of(UiCue.NAVIGATE,UiCue.NAVIGATE),cues);
    }

    @Test void vehicleFeedbackIsOneMechanicalCuePerActualBrowse() {
        var ui=new GameUi(ASSETS,new Node());var menu=new MenuView(ui);var model=new VehicleSelectionModel("rivet");
        var cues=new ArrayList<UiCue>();menu.onFeedback(cues::add);
        menu.showVehicleSelection(model,34,()->model.browse(-1),()->model.browse(1),model::commit,model::cancel);
        menu.move(UiFocusModel.Direction.RIGHT);
        var next=ui.root().getChild("vehicle:next").getLocalTranslation();menu.click(next.x+1,next.y+1);
        assertEquals(List.of(UiCue.VEHICLE,UiCue.VEHICLE),cues);assertEquals("spark",model.preview().id());
        model.cancel();menu.move(UiFocusModel.Direction.RIGHT);assertEquals(2,cues.size());
    }

    @Test void unchangedSliderAndSelectedTabDoNotEmitFeedback() {
        var ui=new GameUi(ASSETS,new Node());var menu=new MenuView(ui);var cues=new ArrayList<UiCue>();
        var changes=new AtomicInteger();menu.onFeedback(cues::add);
        menu.show("settings:feedback","Settings","",List.of(new MenuView.Tab("selected","Audio",true,changes::incrementAndGet)),
                List.of(new MenuView.Slider("music","Music",1,0,1,.05f,"100%",ignored->changes.incrementAndGet())),List.of(),"music");
        menu.move(UiFocusModel.Direction.RIGHT);menu.activate();menu.focus("selected");menu.activate();
        assertTrue(cues.isEmpty());assertEquals(0,changes.get());
        menu.focus("music");menu.move(UiFocusModel.Direction.LEFT);
        assertEquals(List.of(UiCue.CHANGE),cues);assertEquals(1,changes.get());
    }
    private static final DesktopAssetManager ASSETS=new DesktopAssetManager(true);
    @Test void vehicleCarouselBrowsesFromAnyFocusAndConfirmChoosesDirectly() {
        var ui=new GameUi(ASSETS,new Node());var menu=new MenuView(ui);
        var model=new VehicleSelectionModel("rivet");var chooses=new AtomicInteger();var backs=new AtomicInteger();
        menu.showVehicleSelection(model,28,()->model.browse(-1),()->model.browse(1),chooses::incrementAndGet,backs::incrementAndGet);
        assertEquals("vehicle:choose",menu.selectedId());
        menu.focus("back");menu.move(UiFocusModel.Direction.RIGHT);
        assertEquals("grinder",model.preview().id());assertEquals(0,chooses.get());
        menu.focus("vehicle:previous");menu.activate();
        assertEquals(1,chooses.get());assertEquals("grinder",model.preview().id());
        var arrow=ui.root().getChild("vehicle:next").getLocalTranslation();menu.click(arrow.x+1,arrow.y+1);
        assertEquals("spark",model.preview().id());assertEquals(1,chooses.get());
        menu.focus("back");menu.activate();assertEquals(1,backs.get());
        assertNull(ui.root().getChild("vehicle-preview-model"));assertNull(ui.root().getChild("start"));
    }
    @Test void typedSettingsUseArrowsForValuesAndTabsRenderSelectionWithoutBrackets() {
        var ui=new GameUi(ASSETS,new Node());var menu=new MenuView(ui);var changed=new AtomicInteger(-1);
        menu.show("settings:0","НАСТРОЙКИ","",List.of(new MenuView.Tab("video","ВИДЕО",true,()->{})),List.of(
                new MenuView.Toggle("vsync","VSync",false,value->changed.set(value?1:0)),
                new MenuView.Choice("samples","Сглаживание",List.of("Выкл.","2x","4x"),1,changed::set)),List.of(),"vsync");
        menu.move(UiFocusModel.Direction.RIGHT);assertEquals(1,changed.get());
        menu.focus("samples");menu.move(UiFocusModel.Direction.LEFT);assertEquals(0,changed.get());
        var arrow=ui.root().getChild("samples:next").getLocalTranslation();menu.click(arrow.x+1,arrow.y+1);assertEquals(2,changed.get());
        assertEquals(0,new MenuView.Choice("first","",List.of("0","1"),0,ignored->{}).shifted(-1));
        assertEquals(1,new MenuView.Choice("last","",List.of("0","1"),1,ignored->{}).shifted(1));
        var labels=new ArrayList<String>();ui.root().depthFirstTraversal(spatial->{if(spatial instanceof BitmapText text)labels.add(text.getText());});
        assertTrue(labels.contains("ВИДЕО"));assertFalse(labels.stream().anyMatch(label->label.contains("[ ВИДЕО ]")));
    }
    @Test void mainMenuLeavesTheRightHalfClearForTheGarageAtEverySize() {
        for(int[] size:new int[][]{{640,480},{1280,720},{1920,1080},{3840,2160}}) {
            var menu=new MenuView(new GameUi(ASSETS,new Node()));menu.resize(size[0],size[1],1);
            menu.show("main","WRECK RIFF","",List.of(),List.of(new MenuView.Action("new","НОВАЯ КАМПАНИЯ",()->{})),List.of(),"new");
            assertTrue(menu.layout().panel().right()<=size[0]*.55f);
        }
    }
    @Test void clampedSettingsDoNotRestartTheRendererOrReopenConfirmation() {
        var menu=new MenuView(new GameUi(ASSETS,new Node()));var changes=new AtomicInteger();
        menu.show("settings:0","НАСТРОЙКИ","",List.of(),List.of(
                new MenuView.Toggle("vsync","VSync",true,ignored->changes.incrementAndGet()),
                new MenuView.Choice("samples","Сглаживание",List.of("Выкл.","2x"),1,ignored->changes.incrementAndGet())),List.of(),"vsync");
        menu.move(UiFocusModel.Direction.RIGHT);assertEquals(0,changes.get());
        menu.focus("samples");menu.move(UiFocusModel.Direction.RIGHT);menu.activate();assertEquals(0,changes.get());
        menu.move(UiFocusModel.Direction.LEFT);assertEquals(1,changes.get());
    }
    @Test void minimumWindowVehicleNumbersStayOnOneLineAndTitleClearsSubtitle() {
        var rules=VehicleRules.load();
        for(var definition:VehicleDefinition.values()) {
            var ui=new GameUi(ASSETS,new Node());var menu=new MenuView(ui);menu.resize(640,480,1.5f);
            float speed=rules.maxSpeed()*definition.profile(rules).speedMultiplier();
            menu.showVehicleSelection(new VehicleSelectionModel(definition.id()),speed,()->{},()->{},()->{},()->{});
            var texts=new ArrayList<BitmapText>();ui.root().depthFirstTraversal(spatial->{if(spatial instanceof BitmapText text)texts.add(text);});
            for(String value:List.of(Math.round(definition.maximumHp())+" HP",Math.round(speed*3.6f)+" км/ч")) {
                var text=texts.stream().filter(candidate->candidate.getText().equals(value)).findFirst().orElseThrow();
                assertEquals(1,text.getLineCount(),definition.id()+": "+value+" must remain on one readable line");
                assertTrue(text.getSize()>=14);
            }
            var title=texts.stream().filter(text->text.getText().equals(definition.displayName())).findFirst().orElseThrow();
            var subtitle=texts.stream().filter(text->text.getText().startsWith("ГАРАЖ")).findFirst().orElseThrow();
            assertEquals(1,title.getLineCount());
            assertTrue(title.getLocalTranslation().y-title.getHeight()>=subtitle.getLocalTranslation().y+4,
                    definition.id()+": header title must leave a visible gap above subtitle");
        }
    }
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
    @Test void namedFocusUsesExistingScrollAndRejectsMissingOrDisabledActions() {
        var menu=new MenuView(new GameUi(ASSETS,new Node()));menu.resize(640,480,1);
        var rows=new ArrayList<MenuView.Row>();var calls=new AtomicInteger();
        for(int i=0;i<16;i++)rows.add(new MenuView.Action("item:"+i,"Действие "+i,calls::incrementAndGet));
        rows.add(new MenuView.Action("disabled","Недоступно","",false,calls::incrementAndGet));
        menu.show("focus-review","СПИСОК","",List.of(),rows,List.of(),"item:0");
        assertTrue(menu.focus("item:15"));assertTrue(menu.scrollOffset()>0);menu.activate();assertEquals(1,calls.get());
        assertFalse(menu.focus("missing"));assertFalse(menu.focus("disabled"));assertEquals("item:15",menu.selectedId());
    }
    @Test void everySettingsTabMovesDownIntoTheFirstRowAtEverySupportedSizeAndScale() {
        for(int[] size:new int[][]{{640,480},{1280,720},{1920,1080},{2560,1440},{3440,1440},{3840,2160}}) {
            for(float scale:new float[]{.8f,1,1.5f}) {
                var menu=new MenuView(new GameUi(ASSETS,new Node()));menu.resize(size[0],size[1],scale);
                var tabs=new ArrayList<MenuView.Tab>();var rows=new ArrayList<MenuView.Row>();
                for(int i=0;i<4;i++){tabs.add(new MenuView.Tab("tab:"+i,"ВКЛАДКА "+i,i==0,()->{}));rows.add(new MenuView.Action("row:"+i,"Настройка "+i,()->{}));}
                var footer=List.of(new MenuView.Action("back","НАЗАД",()->{}),new MenuView.Action("reset","СБРОСИТЬ",()->{}));
                for(int tab=0;tab<4;tab++) {
                    menu.show("settings:"+tab,"НАСТРОЙКИ","",tabs,rows,footer,"tab:0");
                    for(int i=0;i<tab;i++)menu.move(UiFocusModel.Direction.RIGHT);
                    assertEquals("tab:"+tab,menu.selectedId());menu.move(UiFocusModel.Direction.DOWN);
                    assertEquals("row:0",menu.selectedId(),size[0]+"x"+size[1]+" scale="+scale+" tab="+tab);
                }
            }
        }
    }
    @Test void reopeningVideoConfirmationRestoresRevertWithoutResettingFocusDuringTheCountdown() {
        var menu=new MenuView(new GameUi(ASSETS,new Node()));
        var footer=List.of(new MenuView.Action("revert","ВЕРНУТЬ",()->{}),new MenuView.Action("keep","ОСТАВИТЬ",()->{}));
        menu.show("video-confirm","СОХРАНИТЬ РЕЖИМ?","10 секунд",List.of(),List.of(),footer,"revert");
        menu.move(UiFocusModel.Direction.RIGHT);assertEquals("keep",menu.selectedId());
        menu.show("video-confirm","СОХРАНИТЬ РЕЖИМ?","9 секунд",List.of(),List.of(),footer,"revert");
        assertEquals("keep",menu.selectedId());
        menu.show("settings","НАСТРОЙКИ","",List.of(),List.of(),List.of(),null);
        menu.show("video-confirm","СОХРАНИТЬ РЕЖИМ?","10 секунд",List.of(),List.of(),footer,"revert");
        assertEquals("revert",menu.selectedId());
    }
    @Test void lockedArenaCardsRemainReachableForInspectionWhilePlayAndDuelCannotActivate() {
        var ui=new GameUi(ASSETS,new Node());var menu=new MenuView(ui);menu.resize(640,480,1.5f);
        var registry=ArenaRegistry.load();var rows=new ArrayList<MenuView.Row>();var calls=new AtomicInteger();
        String[] ids=registry.entries().stream().map(ArenaRegistry.Entry::id).toArray(String[]::new);
        for(int i=0;i<ids.length;i++)rows.add(new MenuView.ArenaCard("arena:"+ids[i],registry.definition(ids[i]),
                i<2?"Арена открыта":"Завершите предыдущую арену",i<2,calls::incrementAndGet,i==2?calls::incrementAndGet:null));
        menu.show("maps","АРЕНЫ","",List.of(),rows,List.of(new MenuView.Action("back","НАЗАД",calls::incrementAndGet)),rows.getFirst().id());
        for(int i=0;i<ids.length;i++) {
            assertEquals("arena:"+ids[i],menu.selectedId());assertNotNull(ui.root().getChild("preview-"+ids[i]));
            if(i>=2) {
                assertNotNull(ui.root().getChild("read-focus"));assertNull(ui.selectedId());
                menu.activate();assertEquals(0,calls.get());
                if(i==2) {
                    assertFalse(ui.select("arena:"+ids[i]+":boss"));
                    menu.move(UiFocusModel.Direction.RIGHT);assertEquals("arena:"+ids[i],menu.selectedId());
                    for(String buttonId:List.of("arena:"+ids[i],"arena:"+ids[i]+":boss")) {
                        var position=ui.root().getChild(buttonId).getLocalTranslation();
                        menu.click(position.x+1,position.y+1);assertEquals(0,calls.get());
                    }
                }
            }
            menu.move(UiFocusModel.Direction.DOWN);
        }
        assertEquals("back",menu.selectedId());assertTrue(menu.scrollOffset()>0);menu.activate();assertEquals(1,calls.get());
        menu.move(UiFocusModel.Direction.UP);assertEquals("arena:"+ids[ids.length-1],menu.selectedId());
        menu.resize(1920,1080,1);assertEquals("arena:"+ids[ids.length-1],menu.selectedId());
        assertNotNull(ui.root().getChild("preview-"+ids[ids.length-1]));menu.activate();assertEquals(1,calls.get());
    }
    @Test void unlockedArenaKeepsIndependentPlayAndBossActionsInItsVisibleCard() {
        var menu=new MenuView(new GameUi(ASSETS,new Node()));menu.resize(640,480,1.5f);
        var selected=new AtomicInteger();var card=new MenuView.ArenaCard("arena",ArenaRegistry.load().definition("construction_17"),
                "Арена и босс открыты",true,()->selected.set(1),()->selected.set(2));
        menu.show("maps","АРЕНЫ","",List.of(),List.of(card),List.of(new MenuView.Action("back","НАЗАД",()->{})),"arena");
        menu.move(UiFocusModel.Direction.RIGHT);assertEquals("arena:boss",menu.selectedId());menu.activate();assertEquals(2,selected.get());
        menu.move(UiFocusModel.Direction.LEFT);assertEquals("arena",menu.selectedId());menu.activate();assertEquals(1,selected.get());
        menu.move(UiFocusModel.Direction.DOWN);assertEquals("back",menu.selectedId());
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

package game.wreckriff.ui;

import com.jme3.font.BitmapText;
import com.jme3.math.ColorRGBA;
import game.wreckriff.arena.ArenaDefinition;
import game.wreckriff.audio.UiCue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.DoubleConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Retained screen/focus/scroll state using the existing GameUi primitives. */
public final class MenuView {
    public sealed interface Row permits Action,Text,Metrics,Slider,Toggle,Choice,ArenaCard {String id();}
    public record Action(String id,String label,String detail,boolean enabled,UiCue cue,Runnable action) implements Row {
        public Action(String id,String label,String detail,boolean enabled,Runnable action) {this(id,label,detail,enabled,UiCue.CONFIRM,action);}
        public Action(String id,String label,Runnable action) {this(id,label,"",true,UiCue.CONFIRM,action);}
        public Action(String id,String label,UiCue cue,Runnable action) {this(id,label,"",true,cue,action);}
    }
    public record Text(String id,String text) implements Row { }
    public record Metric(String label,String value) { }
    public record Metrics(String id,List<Metric> values) implements Row {public Metrics {values=List.copyOf(values);}}
    public record Slider(String id,String label,float value,float minimum,float maximum,float step,String valueText,DoubleConsumer change) implements Row {
        public Slider {if(!Float.isFinite(value)||minimum>=maximum||step<=0)throw new IllegalArgumentException("Invalid slider");}
        public float quantized(double next) {return Math.clamp(minimum+Math.round((next-minimum)/step)*step,minimum,maximum);}
    }
    public record Toggle(String id,String label,boolean value,Consumer<Boolean> change) implements Row { }
    public record Choice(String id,String label,List<String> values,int index,IntConsumer change) implements Row {
        public Choice {values=List.copyOf(values);if(values.isEmpty()||index<0||index>=values.size())throw new IllegalArgumentException("Invalid choice");}
        public int shifted(int delta) {return Math.clamp(index+Integer.signum(delta),0,values.size()-1);}
    }
    public record ArenaCard(String id,ArenaDefinition arena,String detail,boolean enabled,Runnable play,Runnable duel) implements Row { }
    private record VehicleSelection(VehicleSelectionModel model,Runnable previous,Runnable next,Runnable choose,Runnable back) { }
    public record Tab(String id,String label,boolean selected,Runnable action) { }
    private record Page(String id,String title,String subtitle,List<Tab> tabs,List<Row> rows,List<Action> footer,String initialFocus) { }
    private record Span(float top,float height) { }
    private static final class State {final UiFocusModel focus=new UiFocusModel();final UiScrollModel scroll=new UiScrollModel();boolean initialized;}
    private final GameUi ui;
    private final Map<String,State> states=new HashMap<>();
    private final Map<String,Span> spans=new HashMap<>();
    private final Map<String,Slider> sliders=new HashMap<>();
    private final Map<String,Toggle> toggles=new HashMap<>();
    private final Map<String,Choice> choices=new HashMap<>();
    private final Map<String,UiBounds> sliderTracks=new HashMap<>();
    private Page page;
    private VehicleSelection vehicleSelection;
    private MenuLayout layout;
    private int width=1280,height=720;
    private float scale=1;
    private BitmapText statusText;
    private String status="",draggingSlider;
    private boolean revealFocusedRow;
    private float previousMouseX=Float.NaN,previousMouseY=Float.NaN;
    private Consumer<UiCue> feedback=ignored->{};
    public MenuView(GameUi ui) {this.ui=Objects.requireNonNull(ui);}
    public void onFeedback(Consumer<UiCue> listener) {feedback=Objects.requireNonNull(listener);}
    public MenuLayout layout() {return layout;}
    public String pageId() {return page==null?"":page.id;}
    public String selectedId() {return page==null?null:state().focus.selectedId();}
    public float scrollOffset() {return page==null?0:state().scroll.offset();}
    public float sceneLeftOcclusion() {return layout!=null&&isGaragePage()?Math.min(.8f,(layout.panel().right()+16*layout.scale())/width):0;}
    public float sceneBottomOcclusion() {return vehicleSelection!=null?.17f:0;}
    /** Select an existing control through the same focus/scroll owner as keyboard navigation. */
    public boolean focus(String id) {
        if(page==null||!state().focus.select(id))return false;
        ensureFocus();render();return Objects.equals(selectedId(),id);
    }
    public void resize(int width,int height,float scale) {
        if(width<320||height<240)return;
        if(this.width==width&&this.height==height&&this.scale==scale)return;
        this.width=width;this.height=height;this.scale=scale;revealFocusedRow=true;if(page!=null)render();
    }
    public void show(String id,String title,String subtitle,List<Tab> tabs,List<Row> rows,List<Action> footer,String initialFocus) {
        vehicleSelection=null;showPage(id,title,subtitle,tabs,rows,footer,initialFocus);
    }
    public void showVehicleSelection(VehicleSelectionModel model,float speedMetresPerSecond,Runnable previous,Runnable next,Runnable choose,Runnable back) {
        if(vehicleSelection==null||vehicleSelection.model!=model)states.remove("vehicles");
        vehicleSelection=new VehicleSelection(model,previous,next,choose,back);
        var definition=model.preview();
        showPage("vehicles",definition.displayName(),"ГАРАЖ    /    "+(definition.ordinal()+1)+" ИЗ 3",List.of(),List.of(
                new Metrics("vehicle:stats",List.of(new Metric("ПРОЧНОСТЬ",Math.round(definition.maximumHp())+" HP"),
                        new Metric("СКОРОСТЬ",Math.round(speedMetresPerSecond*3.6f)+" км/ч"))),
                new Text("vehicle:special",definition.specialName()+"\n"+definition.description())),
                List.of(new Action("vehicle:choose","ВЫБРАТЬ МАШИНУ",choose),new Action("back","НАЗАД",UiCue.BACK,back)),"vehicle:choose");
    }
    private void showPage(String id,String title,String subtitle,List<Tab> tabs,List<Row> rows,List<Action> footer,String initialFocus) {
        boolean changed=page==null||!page.id.equals(id);
        page=new Page(id,title,subtitle,List.copyOf(tabs),List.copyOf(rows),List.copyOf(footer),initialFocus);
        if(changed&&(id.startsWith("confirm:")||id.equals("video-confirm")))states.remove(id);
        render();
    }
    public void setStatus(String text) {if(!Objects.equals(status,text)){status=text;if(statusText!=null)statusText.setText(text);}}
    public void hover(float x,float y) {
        if(x==previousMouseX&&y==previousMouseY)return;previousMouseX=x;previousMouseY=y;
        String before=ui.selectedId();ui.hover(x,y);adoptUiFocus();
        if(ui.selectedId()!=null&&!Objects.equals(before,ui.selectedId()))feedback.accept(UiCue.NAVIGATE);
    }
    public void move(UiFocusModel.Direction direction) {
        if(page==null)return;
        boolean horizontal=direction==UiFocusModel.Direction.LEFT||direction==UiFocusModel.Direction.RIGHT;
        if(vehicleSelection!=null&&horizontal) {
            browseVehicle(direction==UiFocusModel.Direction.RIGHT);
            return;
        }
        Toggle toggle=toggles.get(selectedId());
        if(toggle!=null&&horizontal){
            boolean next=direction==UiFocusModel.Direction.RIGHT;
            changeToggle(toggle,next);
            return;
        }
        Choice choice=choices.get(selectedId());
        if(choice!=null&&horizontal){changeChoice(choice,direction==UiFocusModel.Direction.RIGHT?1:-1);return;}
        Slider slider=sliders.get(selectedId());
        if(slider!=null&&(direction==UiFocusModel.Direction.LEFT||direction==UiFocusModel.Direction.RIGHT)) {
            changeSlider(slider,slider.value+(direction==UiFocusModel.Direction.RIGHT?slider.step:-slider.step));return;
        }
        String before=selectedId();state().focus.move(direction);ensureFocus();render();
        if(!Objects.equals(before,selectedId()))feedback.accept(UiCue.NAVIGATE);
    }
    public void cycle(boolean backwards) {
        if(page==null)return;String before=selectedId();state().focus.cycle(backwards?-1:1);ensureFocus();render();
        if(!Objects.equals(before,selectedId()))feedback.accept(UiCue.NAVIGATE);
    }
    public void activate() {
        if(vehicleSelection!=null&&!"back".equals(selectedId())){perform(vehicleSelection.choose,UiCue.CONFIRM);return;}
        if(page!=null&&ui.select(selectedId()))ui.activate();
    }
    public void click(float x,float y) {
        if(page==null)return;
        for(var entry:sliderTracks.entrySet())if(entry.getValue().contains(x,y)) {
            draggingSlider=entry.getKey();state().focus.select(draggingSlider);drag(x,y);return;
        }
        ui.click(x,y);
        adoptUiFocus();
    }
    private void adoptUiFocus() {
        if(page==null||ui.selectedId()==null)return;
        String selected=ui.selectedId();
        if(state().focus.select(selected))return;
        int suffix=selected.lastIndexOf(':');
        if(suffix>0&&choices.containsKey(selected.substring(0,suffix)))state().focus.select(selected.substring(0,suffix));
    }
    private void changeChoice(Choice choice,int direction) {
        int next=choice.shifted(direction);if(next!=choice.index){choice.change.accept(next);feedback.accept(UiCue.CHANGE);}
    }
    private void changeToggle(Toggle toggle,boolean next) {
        if(next!=toggle.value){toggle.change.accept(next);feedback.accept(UiCue.CHANGE);}
    }
    private void changeSlider(Slider slider,double requested) {
        float next=slider.quantized(requested);
        if(Float.compare(next,slider.value)!=0){slider.change.accept(next);feedback.accept(UiCue.CHANGE);}
    }
    private void browseVehicle(boolean next) {
        var selection=vehicleSelection;String before=selection.model.preview().id();
        if(next)selection.next.run();else selection.previous.run();
        if(!before.equals(selection.model.preview().id()))feedback.accept(UiCue.VEHICLE);
    }
    private void perform(Runnable action,UiCue cue) {
        action.run();feedback.accept(cue);
    }
    public void drag(float x,float y) {
        if(draggingSlider==null)return;Slider slider=sliders.get(draggingSlider);UiBounds track=sliderTracks.get(draggingSlider);
        if(slider==null||track==null){draggingSlider=null;return;}
        changeSlider(slider,slider.minimum+(x-track.x())/track.width()*(slider.maximum-slider.minimum));
    }
    public void release() {draggingSlider=null;}
    public void scroll(float pixels) {if(page==null)return;state().scroll.scroll(pixels);render();}
    private State state() {return states.computeIfAbsent(page.id,ignored->new State());}
    private void ensureFocus() {Span span=spans.get(selectedId());if(span!=null)state().scroll.ensureVisible(span.top,span.height);}
    private boolean isGaragePage() {return page!=null&&(page.id.equals("main")||vehicleSelection!=null);}

    private void render() {
        layout=MenuLayout.compute(width,height,scale,!page.tabs.isEmpty(),vehicleSelection!=null?MenuLayout.Kind.VEHICLE:page.id.equals("main")?MenuLayout.Kind.MAIN:MenuLayout.Kind.STANDARD);ui.clear();ui.usePixelCoordinates();
        float s=layout.scale(),font=layout.fontSize(),gap=8*s;
        UiBounds panel=layout.panel(),body=layout.content();
        List<Row> contentRows=fitTextRows(page.rows,body.width(),body.height());
        drawShell();
        var focusItems=new ArrayList<UiFocusModel.Item>();spans.clear();sliders.clear();toggles.clear();choices.clear();sliderTracks.clear();
        float contentHeight=0;var heights=new ArrayList<Float>();
        for(Row row:contentRows){float h=rowHeight(row,body.width());heights.add(h);contentHeight+=h+gap;}
        state().scroll.resize(contentHeight,body.height());
        float offset=0;
        for(int i=0;i<contentRows.size();i++) {
            Row row=contentRows.get(i);float h=heights.get(i);
            List<String> ids=ids(row);
            for(int j=0;j<ids.size();j++) {
                String id=ids.get(j);spans.put(id,new Span(offset,h));
                UiBounds rowBounds=new UiBounds(body.x(),body.top()-offset-h,body.width(),h);
                focusItems.add(new UiFocusModel.Item(id,rowControlBounds(row,rowBounds,j),focusable(row)));
            }
            offset+=h+gap;
        }
        for(int i=0;i<page.tabs.size();i++)focusItems.add(new UiFocusModel.Item(page.tabs.get(i).id,tabBounds(i),true));
        if(vehicleSelection!=null) {
            focusItems.add(new UiFocusModel.Item("vehicle:previous",vehicleArrowBounds(false),true));
            focusItems.add(new UiFocusModel.Item("vehicle:next",vehicleArrowBounds(true),true));
        }
        for(int i=0;i<page.footer.size();i++) {
            UiBounds bounds=footerBounds(i);
            // Focus traverses the complete unscrolled list before reaching the pinned footer.
            bounds=new UiBounds(bounds.x(),bounds.y()-Math.max(0,contentHeight-body.height()),bounds.width(),bounds.height());
            focusItems.add(new UiFocusModel.Item(page.footer.get(i).id,bounds,page.footer.get(i).enabled));
        }
        state().focus.replace(focusItems);
        if(!state().initialized){if(page.initialFocus!=null)state().focus.select(page.initialFocus);state().initialized=true;ensureFocus();}
        if(revealFocusedRow){ensureFocus();revealFocusedRow=false;}
        offset=0;
        for(int i=0;i<contentRows.size();i++) {
            Row row=contentRows.get(i);float h=heights.get(i),top=body.top()-offset+state().scroll.offset();
            // Only whole rows are drawn, so no text or hit target leaks into fixed header/footer regions.
            if(top<=body.top()+.1f&&top-h>=body.y()-.1f)drawRow(row,new UiBounds(body.x(),top-h,body.width(),h));
            offset+=h+gap;
        }
        for(int i=0;i<page.tabs.size();i++) {
            Tab tab=page.tabs.get(i);
            ui.button(tab.id,tab.label,tabBounds(i),true,GameUi.ButtonStyle.TAB,tab.selected,()->{
                if(!tab.selected)perform(tab.action,UiCue.CHANGE);
            });
        }
        for(int i=0;i<page.footer.size();i++) {
            Action action=page.footer.get(i);
            ui.button(action.id,action.label,footerBounds(i),action.enabled,action.id.equals("vehicle:choose")?GameUi.ButtonStyle.PRIMARY:GameUi.ButtonStyle.QUIET,false,()->perform(action.action,action.cue));
        }
        if(vehicleSelection!=null) {
            ui.button("vehicle:previous","<",vehicleArrowBounds(false),true,GameUi.ButtonStyle.CONTROL,false,()->browseVehicle(false));
            ui.button("vehicle:next",">",vehicleArrowBounds(true),true,GameUi.ButtonStyle.CONTROL,false,()->browseVehicle(true));
            UiBounds left=vehicleArrowBounds(false),right=vehicleArrowBounds(true);
            for(int i=0;i<3;i++)ui.rect("vehicle-index-"+i,(left.right()+right.x())/2+(i-1)*18*s-4*s,left.centerY()-2*s,8*s,4*s,
                    i==vehicleSelection.model.preview().ordinal()?GameUi.ACCENT:GameUi.MUTED,3);
        }
        if(contentHeight>body.height()) {
            float barHeight=Math.max(16,body.height()*body.height()/contentHeight);
            float travel=body.height()-barHeight,fraction=state().scroll.offset()/state().scroll.maximumOffset();
            ui.rect("menu-scroll",body.right()+4*s,body.top()-barHeight-travel*fraction,3*s,barHeight,GameUi.ACCENT,3);
        }
        statusText=ui.paragraph(status,layout.status(),Math.max(14,15*s),GameUi.ACCENT);ui.select(selectedId());
    }
    private List<Row> fitTextRows(List<Row> rows,float width,float maximumHeight) {
        var result=new ArrayList<Row>();
        for(Row row:rows) {
            if(!(row instanceof Text text)||rowHeight(row,width)<=maximumHeight){result.add(row);continue;}
            StringBuilder chunk=new StringBuilder();int part=0;
            for(String word:text.text.split("(?<=\\s)")) {
                String candidate=chunk.toString()+word;
                if(!chunk.isEmpty()&&ui.paragraphHeight(candidate,width-24*layout.scale(),textFont())+12*layout.scale()>maximumHeight) {
                    result.add(new Text(text.id+":"+part++,chunk.toString()));chunk.setLength(0);
                }
                chunk.append(word);
            }
            if(!chunk.isEmpty())result.add(new Text(text.id+":"+part,chunk.toString()));
        }
        return result;
    }
    private float rowHeight(Row row,float width) {
        float s=layout.scale(),font=layout.fontSize();
        return switch(row) {
            case Action action->Math.max(44,action.detail.isBlank()?48*s:76*s);
            case Slider ignored->Math.max(66,78*s);
            case Toggle ignored->Math.max(48,58*s);
            case Choice ignored->Math.max(64,74*s);
            case Metrics ignored->vehicleSelection==null?Math.max(70,90*s):Math.max(54,64*s);
            case ArenaCard ignored->Math.min(layout.content().height(),Math.max(140,172*s));
            case Text text->Math.max(font*1.5f,ui.paragraphHeight(text.text,width-24*s,textFont())+12*s);
        };
    }
    private float textFont() {return vehicleSelection==null?layout.fontSize():Math.max(14,Math.min(16*layout.scale(),height/36f));}
    private List<String> ids(Row row) {
        return switch(row) {
            case Action action->List.of(action.id);
            case Slider slider->List.of(slider.id);
            case Toggle toggle->List.of(toggle.id);
            case Choice choice->List.of(choice.id);
            case ArenaCard card->!card.enabled||card.duel==null?List.of(card.id):List.of(card.id,card.id+":boss");
            case Text text->List.of(text.id);
            case Metrics metrics->List.of(metrics.id);
        };
    }
    private boolean focusable(Row row) {return !(row instanceof Action action)||action.enabled;}
    private UiBounds tabBounds(int index) {
        float s=layout.scale(),cell=layout.tabs().width()/page.tabs.size();
        return new UiBounds(layout.tabs().x()+index*cell,layout.tabs().y()+4*s,cell-8*s,layout.tabs().height()-8*s);
    }
    private UiBounds footerBounds(int index) {
        if(vehicleSelection!=null) {
            float gap=8*layout.scale(),cell=(layout.footer().height()-gap)/2;
            return new UiBounds(layout.footer().x(),layout.footer().y()+(1-index)*(cell+gap),layout.footer().width(),cell);
        }
        float cell=layout.footer().width()/page.footer.size();
        return new UiBounds(layout.footer().x()+index*cell,layout.footer().y(),cell-8*layout.scale(),layout.footer().height());
    }
    private UiBounds rowControlBounds(Row row,UiBounds bounds,int index) {
        float s=layout.scale();
        return switch(row) {
            case Action action->new UiBounds(bounds.x(),bounds.y()+(action.detail.isBlank()?0:28*s),bounds.width(),bounds.height()-(action.detail.isBlank()?0:28*s));
            case Slider ignored->new UiBounds(bounds.x(),bounds.y()+25*s,bounds.width(),bounds.height()-25*s);
            case ArenaCard card->card.enabled?arenaButtonBounds(card,bounds,index):bounds;
            default->bounds;
        };
    }
    private UiBounds arenaButtonBounds(ArenaCard card,UiBounds bounds,int index) {
        float s=layout.scale(),left=bounds.x()+bounds.width()*.37f+12*s;
        float available=bounds.right()-left,buttonWidth=card.duel==null?available:(available-8*s)/2;
        return new UiBounds(left+index*(buttonWidth+8*s),bounds.y(),buttonWidth,Math.max(36,40*s));
    }
    private void drawRow(Row row,UiBounds bounds) {
        float s=layout.scale(),font=layout.fontSize(),pad=12*s;
        if(row.id().equals(selectedId())&&(row instanceof Text||row instanceof Metrics||row instanceof ArenaCard card&&!card.enabled))
            ui.rect("read-focus",bounds.x(),bounds.y(),3*s,bounds.height(),GameUi.ACCENT,3);
        switch(row) {
            case Action action-> {
                ui.button(action.id,action.label,rowControlBounds(row,bounds,0),action.enabled,
                        isGaragePage()||page.id.equals("pause")?GameUi.ButtonStyle.NAVIGATION:GameUi.ButtonStyle.CONTROL,false,()->perform(action.action,action.cue));
                if(!action.detail.isBlank())ui.paragraph(action.detail,new UiBounds(bounds.x()+pad,bounds.y(),bounds.width()-pad*2,28*s),Math.max(14,16*s),GameUi.MUTED);
            }
            case Text text->ui.paragraph(text.text,new UiBounds(bounds.x()+pad,bounds.y(),bounds.width()-pad*2,bounds.height()),textFont(),GameUi.PAPER);
            case Metrics metrics-> {
                if(vehicleSelection!=null&&bounds.width()<320*s) {
                    float rowHeight=bounds.height()/metrics.values.size();
                    for(int i=0;i<metrics.values.size();i++) {
                        Metric metric=metrics.values.get(i);float y=bounds.top()-(i+1)*rowHeight;
                        ui.rect(metrics.id+"-card-"+i,bounds.x(),y,bounds.width(),rowHeight-2*s,new ColorRGBA(.065f,.072f,.076f,.84f),1);
                        float labelWidth=bounds.width()*.45f-pad,valueX=bounds.x()+bounds.width()*.48f,valueWidth=bounds.right()-valueX-pad;
                        float labelSize=ui.fitTextSize(metric.label,labelWidth,Math.max(14,15*s)),valueSize=ui.fitTextSize(metric.value,valueWidth,Math.max(18,21*s));
                        float labelHeight=ui.paragraphHeight(metric.label,labelWidth,labelSize),valueHeight=ui.paragraphHeight(metric.value,valueWidth,valueSize);
                        ui.paragraph(metric.label,new UiBounds(bounds.x()+pad,y+(rowHeight-labelHeight)/2,labelWidth,labelHeight),labelSize,GameUi.MUTED);
                        ui.paragraph(metric.value,new UiBounds(valueX,y+(rowHeight-valueHeight)/2,valueWidth,valueHeight),valueSize,GameUi.PAPER);
                    }
                    break;
                }
                float cell=bounds.width()/metrics.values.size();
                for(int i=0;i<metrics.values.size();i++) {
                    Metric metric=metrics.values.get(i);float x=bounds.x()+i*cell;
                    ui.rect(metrics.id+"-card-"+i,x,bounds.y(),cell-8*s,bounds.height(),new ColorRGBA(.065f,.072f,.076f,.84f),1);
                    ui.rect(metrics.id+"-rule-"+i,x,bounds.y(),cell-8*s,2*s,new ColorRGBA(.42f,.25f,.15f,.85f),2);
                    ui.paragraph(metric.value,new UiBounds(x+pad,bounds.y()+34*s,cell-pad*2,bounds.height()-36*s),ui.fitTextSize(metric.value,cell-pad*2,Math.max(22,28*s)),GameUi.PAPER);
                    ui.paragraph(metric.label,new UiBounds(x+pad,bounds.y()+3*s,cell-pad*2,31*s),ui.fitTextSize(metric.label,cell-pad*2,Math.max(14,15*s)),GameUi.MUTED);
                }
            }
            case Toggle toggle-> {
                toggles.put(toggle.id,toggle);
                ui.button(toggle.id,toggle.label,bounds,true,GameUi.ButtonStyle.CONTROL,false,()->changeToggle(toggle,!toggle.value));
                float trackWidth=48*s,trackHeight=24*s,x=bounds.right()-trackWidth-pad,y=bounds.centerY()-trackHeight/2;
                ui.rect(toggle.id+"-state",x,y,trackWidth,trackHeight,toggle.value?GameUi.ACCENT:new ColorRGBA(.19f,.22f,.23f,1),2);
                float knob=trackHeight-6*s;
                ui.rect(toggle.id+"-knob",x+3*s+(toggle.value?trackWidth-trackHeight:0),y+3*s,knob,knob,GameUi.PAPER,3);
                String label=toggle.value?"ВКЛ.":"ВЫКЛ.";
                ui.paragraph(label,new UiBounds(x-66*s,bounds.centerY()-12*s,60*s,24*s),Math.max(14,15*s),GameUi.MUTED);
            }
            case Choice choice-> {
                choices.put(choice.id,choice);
                ui.rect(choice.id+"-surface",bounds.x(),bounds.y(),bounds.width(),bounds.height(),new ColorRGBA(.065f,.072f,.076f,.9f),0);
                ui.paragraph(choice.label,new UiBounds(bounds.x()+pad,bounds.top()-30*s,bounds.width()-pad*2,28*s),font,GameUi.PAPER);
                float arrow=Math.max(36,40*s),valueHeight=Math.max(30,34*s),gap=6*s;
                UiBounds previous=new UiBounds(bounds.x(),bounds.y(),arrow,valueHeight),next=new UiBounds(bounds.right()-arrow,bounds.y(),arrow,valueHeight);
                ui.button(choice.id,choice.values.get(choice.index),new UiBounds(previous.right()+gap,bounds.y(),bounds.width()-2*(arrow+gap),valueHeight),true,
                        GameUi.ButtonStyle.CONTROL,false,()->changeChoice(choice,1));
                ui.button(choice.id+":previous","<",previous,choice.index>0,GameUi.ButtonStyle.QUIET,false,()->{
                    state().focus.select(choice.id);changeChoice(choice,-1);});
                ui.button(choice.id+":next",">",next,choice.index<choice.values.size()-1,GameUi.ButtonStyle.QUIET,false,()->{
                    state().focus.select(choice.id);changeChoice(choice,1);});
            }
            case Slider slider-> {
                sliders.put(slider.id,slider);
                ui.button(slider.id,"",rowControlBounds(row,bounds,0),true,()->{});
                UiBounds control=rowControlBounds(row,bounds,0);
                ui.paragraph(slider.label,new UiBounds(control.x()+pad,control.y()+5*s,control.width()*.72f-pad,control.height()-10*s),font,GameUi.PAPER);
                ui.paragraph(slider.valueText,new UiBounds(control.x()+control.width()*.74f,control.y()+5*s,control.width()*.26f-pad,control.height()-10*s),
                        ui.fitTextSize(slider.valueText,control.width()*.26f-pad,font),GameUi.ACCENT);
                UiBounds track=new UiBounds(bounds.x()+pad,bounds.y()+9*s,bounds.width()-pad*2,10*s);sliderTracks.put(slider.id,track);
                ui.rect(slider.id+"-track",track.x(),track.y(),track.width(),track.height(),new ColorRGBA(.18f,.21f,.24f,1),2);
                float fraction=(slider.value-slider.minimum)/(slider.maximum-slider.minimum);
                ui.rect(slider.id+"-fill",track.x(),track.y(),track.width()*Math.clamp(fraction,0,1),track.height(),GameUi.ACCENT,3);
                ui.rect(slider.id+"-thumb",track.x()+track.width()*Math.clamp(fraction,0,1)-3*s,track.y()-4*s,6*s,track.height()+8*s,GameUi.PAPER,4);
            }
            case ArenaCard card-> {
                float preview=bounds.width()*.37f,left=bounds.x()+preview+pad;
                ui.rect(card.id+"-surface",bounds.x(),bounds.y(),bounds.width(),bounds.height(),new ColorRGBA(.065f,.072f,.076f,.92f),0);
                ui.image("preview-"+card.arena.id(),"textures/ui/arenas/"+card.arena.id()+".png",new UiBounds(bounds.x(),bounds.y(),preview,bounds.height()),1);
                ui.paragraph(card.arena.metadata().title(),new UiBounds(left,bounds.top()-34*s,bounds.right()-left,32*s),
                        ui.fitTextSize(card.arena.metadata().title(),bounds.right()-left,font),GameUi.PAPER);
                ui.paragraph(card.detail,new UiBounds(left,bounds.y()+44*s,bounds.right()-left,bounds.height()-80*s),Math.max(14,16*s),GameUi.MUTED);
                ui.button(card.id,card.enabled?"БОЙ":"ЗАКРЫТО",arenaButtonBounds(card,bounds,0),card.enabled,()->perform(card.play,UiCue.CONFIRM));
                if(card.duel!=null)ui.button(card.id+":boss","БОСС",arenaButtonBounds(card,bounds,1),card.enabled,()->perform(card.duel,UiCue.CONFIRM));
            }
        }
    }
    private UiBounds vehicleArrowBounds(boolean next) {
        float s=layout.scale(),edge=Math.max(16,28*s),size=Math.max(42,52*s);
        float left=layout.panel().right()+edge,right=width-edge;
        return new UiBounds(next?right-size:left,Math.max(28,height*.075f),size,size);
    }
    private void drawShell() {
        float s=layout.scale();UiBounds panel=layout.panel(),header=layout.header();
        if(!isGaragePage())ui.rect("menu-backdrop",0,0,width,height,new ColorRGBA(.012f,.017f,.023f,.72f),-2);
        ui.rect("menu-panel",panel.x(),panel.y(),panel.width(),panel.height(),new ColorRGBA(.028f,.033f,.039f,isGaragePage()?.82f:.97f),-1);
        if(isGaragePage())for(int i=0;i<8;i++)ui.rect("menu-edge-"+i,panel.right()+i*4*s,panel.y(),4*s,panel.height(),
                new ColorRGBA(.028f,.033f,.039f,.48f*(1-i/8f)),0);
        ui.rect("menu-copper-edge",panel.x(),panel.top()-3*s,panel.width()*.22f,3*s,GameUi.ACCENT,1);
        ui.rect("menu-top-rule",panel.x()+panel.width()*.22f,panel.top()-s,panel.width()*.78f,s,new ColorRGBA(.39f,.26f,.17f,.65f),1);
        ui.rect("menu-bottom-rule",header.x(),layout.footer().top()+6*s,header.width(),s,new ColorRGBA(.29f,.32f,.33f,.5f),1);
        float subtitleSize=Math.max(14,15*s),subtitleHeight=ui.paragraphHeight(page.subtitle,header.width(),subtitleSize);
        float titleTop=header.top()-4*s,maximumTitleHeight=titleTop-header.y()-subtitleHeight-8*s;
        float titleSize=ui.fitTextSize(page.title,header.width(),Math.min(maximumTitleHeight,46*s));
        float titleHeight=ui.paragraphHeight(page.title,header.width(),titleSize);
        for(int attempt=0;attempt<4&&titleHeight>maximumTitleHeight&&titleSize>14;attempt++) {
            titleSize=ui.fitTextSize(page.title,header.width(),Math.max(14,titleSize*maximumTitleHeight/titleHeight-.2f));
            titleHeight=ui.paragraphHeight(page.title,header.width(),titleSize);
        }
        ui.paragraph(page.title,new UiBounds(header.x(),titleTop-titleHeight,header.width(),titleHeight),titleSize,GameUi.PAPER);
        ui.paragraph(page.subtitle,new UiBounds(header.x(),header.y(),header.width(),subtitleHeight),subtitleSize,GameUi.MUTED);
    }
}

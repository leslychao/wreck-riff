package game.wreckriff.ui;

import com.jme3.font.BitmapText;
import com.jme3.math.ColorRGBA;
import game.wreckriff.arena.ArenaDefinition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.DoubleConsumer;

/** Retained screen/focus/scroll state using the existing GameUi primitives. */
public final class MenuView {
    public sealed interface Row permits Action,Text,Metrics,Slider,ArenaCard {String id();}
    public record Action(String id,String label,String detail,boolean enabled,Runnable action) implements Row {
        public Action(String id,String label,Runnable action) {this(id,label,"",true,action);}
    }
    public record Text(String id,String text) implements Row { }
    public record Metric(String label,String value) { }
    public record Metrics(String id,List<Metric> values) implements Row {public Metrics {values=List.copyOf(values);}}
    public record Slider(String id,String label,float value,float minimum,float maximum,float step,String valueText,DoubleConsumer change) implements Row {
        public Slider {if(!Float.isFinite(value)||minimum>=maximum||step<=0)throw new IllegalArgumentException("Invalid slider");}
        public float quantized(double next) {return Math.clamp(minimum+Math.round((next-minimum)/step)*step,minimum,maximum);}
    }
    public record ArenaCard(String id,ArenaDefinition arena,String detail,boolean enabled,Runnable play,Runnable duel) implements Row { }
    public record Tab(String id,String label,boolean selected,Runnable action) { }
    private record Page(String id,String title,String subtitle,List<Tab> tabs,List<Row> rows,List<Action> footer,String initialFocus) { }
    private record Span(float top,float height) { }
    private static final class State {final UiFocusModel focus=new UiFocusModel();final UiScrollModel scroll=new UiScrollModel();boolean initialized;}
    private final GameUi ui;
    private final Map<String,State> states=new HashMap<>();
    private final Map<String,Span> spans=new HashMap<>();
    private final Map<String,Slider> sliders=new HashMap<>();
    private final Map<String,UiBounds> sliderTracks=new HashMap<>();
    private Page page;
    private MenuLayout layout;
    private int width=1280,height=720;
    private float scale=1;
    private BitmapText statusText;
    private String status="",draggingSlider;
    public MenuView(GameUi ui) {this.ui=Objects.requireNonNull(ui);}
    public MenuLayout layout() {return layout;}
    public String selectedId() {return page==null?null:state().focus.selectedId();}
    public float scrollOffset() {return page==null?0:state().scroll.offset();}
    public void resize(int width,int height,float scale) {
        if(width<320||height<240)return;
        if(this.width==width&&this.height==height&&this.scale==scale)return;
        this.width=width;this.height=height;this.scale=scale;if(page!=null)render();
    }
    public void show(String id,String title,String subtitle,List<Tab> tabs,List<Row> rows,List<Action> footer,String initialFocus) {
        page=new Page(id,title,subtitle,List.copyOf(tabs),List.copyOf(rows),List.copyOf(footer),initialFocus);render();
    }
    public void setStatus(String text) {if(!Objects.equals(status,text)){status=text;if(statusText!=null)statusText.setText(text);}}
    public void hover(float x,float y) {ui.hover(x,y);if(page!=null&&ui.selectedId()!=null)state().focus.select(ui.selectedId());}
    public void move(UiFocusModel.Direction direction) {
        if(page==null)return;
        Slider slider=sliders.get(selectedId());
        if(slider!=null&&(direction==UiFocusModel.Direction.LEFT||direction==UiFocusModel.Direction.RIGHT)) {
            slider.change.accept(slider.quantized(slider.value+(direction==UiFocusModel.Direction.RIGHT?slider.step:-slider.step)));return;
        }
        state().focus.move(direction);ensureFocus();render();
    }
    public void cycle(boolean backwards) {if(page==null)return;state().focus.cycle(backwards?-1:1);ensureFocus();render();}
    public void activate() {if(page==null)return;ui.select(selectedId());ui.activate();}
    public void click(float x,float y) {
        if(page==null)return;
        for(var entry:sliderTracks.entrySet())if(entry.getValue().contains(x,y)) {
            draggingSlider=entry.getKey();state().focus.select(draggingSlider);drag(x,y);return;
        }
        ui.click(x,y);
        if(ui.selectedId()!=null)state().focus.select(ui.selectedId());
    }
    public void drag(float x,float y) {
        if(draggingSlider==null)return;Slider slider=sliders.get(draggingSlider);UiBounds track=sliderTracks.get(draggingSlider);
        if(slider==null||track==null){draggingSlider=null;return;}
        slider.change.accept(slider.quantized(slider.minimum+(x-track.x())/track.width()*(slider.maximum-slider.minimum)));
    }
    public void release() {draggingSlider=null;}
    public void scroll(float pixels) {if(page==null)return;state().scroll.scroll(pixels);render();}
    private State state() {return states.computeIfAbsent(page.id,ignored->new State());}
    private void ensureFocus() {Span span=spans.get(selectedId());if(span!=null)state().scroll.ensureVisible(span.top,span.height);}

    private void render() {
        layout=MenuLayout.compute(width,height,scale,!page.tabs.isEmpty());ui.clear();ui.usePixelCoordinates();
        float s=layout.scale(),font=layout.fontSize(),gap=8*s;
        UiBounds panel=layout.panel(),body=layout.content();
        ui.rect("menu-panel",panel.x(),panel.y(),panel.width(),panel.height(),GameUi.INK,0);
        ui.paragraph(page.title,new UiBounds(layout.header().x(),layout.header().top()-42*s,layout.header().width(),Math.max(32,42*s)),Math.max(24,34*s),GameUi.PAPER);
        ui.paragraph(page.subtitle,new UiBounds(layout.header().x(),layout.header().y(),layout.header().width(),Math.max(24,30*s)),Math.max(14,16*s),GameUi.ACCENT);
        var focusItems=new ArrayList<UiFocusModel.Item>();spans.clear();sliders.clear();sliderTracks.clear();
        float contentHeight=0;var heights=new ArrayList<Float>();
        for(Row row:page.rows){float h=rowHeight(row,body.width());heights.add(h);contentHeight+=h+gap;}
        state().scroll.resize(contentHeight,body.height());
        float offset=0;
        for(int i=0;i<page.rows.size();i++) {
            Row row=page.rows.get(i);float h=heights.get(i);
            List<String> ids=ids(row);
            for(int j=0;j<ids.size();j++) {
                String id=ids.get(j);spans.put(id,new Span(offset,h));
                focusItems.add(new UiFocusModel.Item(id,new UiBounds(j*200,-offset-h,180,h),enabled(row)));
            }
            offset+=h+gap;
        }
        for(int i=0;i<page.tabs.size();i++)focusItems.add(new UiFocusModel.Item(page.tabs.get(i).id,new UiBounds(i*200,100,180,40),true));
        for(int i=0;i<page.footer.size();i++)focusItems.add(new UiFocusModel.Item(page.footer.get(i).id,new UiBounds(i*200,-contentHeight-80,180,40),page.footer.get(i).enabled));
        state().focus.replace(focusItems);
        if(!state().initialized){if(page.initialFocus!=null)state().focus.select(page.initialFocus);state().initialized=true;ensureFocus();}
        offset=0;
        for(int i=0;i<page.rows.size();i++) {
            Row row=page.rows.get(i);float h=heights.get(i),top=body.top()-offset+state().scroll.offset();
            // Only whole rows are drawn, so no text or hit target leaks into fixed header/footer regions.
            if(top<=body.top()+.1f&&top-h>=body.y()-.1f)drawRow(row,new UiBounds(body.x(),top-h,body.width(),h));
            offset+=h+gap;
        }
        for(int i=0;i<page.tabs.size();i++) {
            Tab tab=page.tabs.get(i);float cell=layout.tabs().width()/page.tabs.size();
            ui.button(tab.id,(tab.selected?"[ ":"")+tab.label+(tab.selected?" ]":""),new UiBounds(layout.tabs().x()+i*cell,layout.tabs().y()+4*s,cell-gap,layout.tabs().height()-8*s),true,tab.action);
        }
        for(int i=0;i<page.footer.size();i++) {
            Action action=page.footer.get(i);float cell=layout.footer().width()/page.footer.size();
            ui.button(action.id,action.label,new UiBounds(layout.footer().x()+i*cell,layout.footer().y(),cell-gap,layout.footer().height()),action.enabled,action.action);
        }
        if(contentHeight>body.height()) {
            float barHeight=Math.max(16,body.height()*body.height()/contentHeight);
            float travel=body.height()-barHeight,fraction=state().scroll.offset()/state().scroll.maximumOffset();
            ui.rect("menu-scroll",body.right()+4*s,body.top()-barHeight-travel*fraction,3*s,barHeight,GameUi.ACCENT,3);
        }
        statusText=ui.paragraph(status,layout.status(),Math.max(14,15*s),GameUi.ACCENT);ui.select(selectedId());
    }
    private float rowHeight(Row row,float width) {
        float s=layout.scale(),font=layout.fontSize();
        return switch(row) {
            case Action action->Math.max(44,action.detail.isBlank()?48*s:76*s);
            case Slider ignored->Math.max(66,78*s);
            case Metrics ignored->Math.max(70,90*s);
            case ArenaCard ignored->Math.max(108,132*s);
            case Text text->Math.max(font*1.5f,ui.paragraphHeight(text.text,width-24*s,font)+12*s);
        };
    }
    private List<String> ids(Row row) {
        return switch(row) {
            case Action action->List.of(action.id);
            case Slider slider->List.of(slider.id);
            case ArenaCard card->card.duel==null?List.of(card.id):List.of(card.id,card.id+":boss");
            default->List.of();
        };
    }
    private boolean enabled(Row row) {return row instanceof Action action?action.enabled:!(row instanceof ArenaCard card)||card.enabled;}
    private void drawRow(Row row,UiBounds bounds) {
        float s=layout.scale(),font=layout.fontSize(),pad=12*s;
        switch(row) {
            case Action action-> {
                ui.button(action.id,action.label,new UiBounds(bounds.x(),bounds.y()+(action.detail.isBlank()?0:28*s),bounds.width(),bounds.height()-(action.detail.isBlank()?0:28*s)),action.enabled,action.action);
                if(!action.detail.isBlank())ui.paragraph(action.detail,new UiBounds(bounds.x()+pad,bounds.y(),bounds.width()-pad*2,28*s),Math.max(14,16*s),GameUi.PAPER);
            }
            case Text text->ui.paragraph(text.text,new UiBounds(bounds.x()+pad,bounds.y(),bounds.width()-pad*2,bounds.height()),font,GameUi.PAPER);
            case Metrics metrics-> {
                float cell=bounds.width()/metrics.values.size();
                for(int i=0;i<metrics.values.size();i++) {
                    Metric metric=metrics.values.get(i);float x=bounds.x()+i*cell;
                    ui.rect(metrics.id+"-card-"+i,x,bounds.y(),cell-8*s,bounds.height(),new ColorRGBA(.1f,.12f,.14f,.98f),1);
                    ui.paragraph(metric.value,new UiBounds(x+pad,bounds.y()+29*s,cell-pad*2,bounds.height()-32*s),Math.max(22,28*s),GameUi.ACCENT);
                    ui.paragraph(metric.label,new UiBounds(x+pad,bounds.y()+3*s,cell-pad*2,28*s),Math.max(14,16*s),GameUi.PAPER);
                }
            }
            case Slider slider-> {
                sliders.put(slider.id,slider);
                ui.button(slider.id,slider.label+"   "+slider.valueText,new UiBounds(bounds.x(),bounds.y()+25*s,bounds.width(),bounds.height()-25*s),true,()->{});
                UiBounds track=new UiBounds(bounds.x()+pad,bounds.y()+9*s,bounds.width()-pad*2,10*s);sliderTracks.put(slider.id,track);
                ui.rect(slider.id+"-track",track.x(),track.y(),track.width(),track.height(),new ColorRGBA(.18f,.21f,.24f,1),2);
                float fraction=(slider.value-slider.minimum)/(slider.maximum-slider.minimum);
                ui.rect(slider.id+"-fill",track.x(),track.y(),track.width()*Math.clamp(fraction,0,1),track.height(),GameUi.ACCENT,3);
                ui.rect(slider.id+"-thumb",track.x()+track.width()*Math.clamp(fraction,0,1)-3*s,track.y()-4*s,6*s,track.height()+8*s,GameUi.PAPER,4);
            }
            case ArenaCard card-> {
                float preview=Math.min(150*s,bounds.width()*.25f),left=bounds.x()+preview+pad;
                preview(card.arena,new UiBounds(bounds.x(),bounds.y(),preview,bounds.height()));
                ui.paragraph(card.arena.metadata().title(),new UiBounds(left,bounds.top()-33*s,bounds.right()-left,31*s),font,GameUi.PAPER);
                ui.paragraph(card.detail,new UiBounds(left,bounds.y()+42*s,bounds.right()-left,45*s),Math.max(14,16*s),GameUi.ACCENT);
                float available=bounds.right()-left,buttonWidth=card.duel==null?available:(available-8*s)/2;
                ui.button(card.id,card.enabled?"БОЙ":"ЗАКРЫТО",new UiBounds(left,bounds.y(),buttonWidth,Math.max(36,40*s)),card.enabled,card.play);
                if(card.duel!=null)ui.button(card.id+":boss","БОСС",new UiBounds(left+buttonWidth+8*s,bounds.y(),buttonWidth,Math.max(36,40*s)),card.enabled,card.duel);
            }
        }
    }
    private void preview(ArenaDefinition arena,UiBounds bounds) {
        ui.rect("preview-"+arena.id(),bounds.x(),bounds.y(),bounds.width(),bounds.height(),new ColorRGBA(.085f,.11f,.13f,1),1);
        float padding=6*layout.scale(),factor=Math.min((bounds.width()-padding*2)/(arena.bounds().maxX()-arena.bounds().minX()),(bounds.height()-padding*2)/(arena.bounds().maxZ()-arena.bounds().minZ()));
        float originX=bounds.centerX()-(arena.bounds().minX()+arena.bounds().maxX())/2*factor,originY=bounds.centerY()-(arena.bounds().minZ()+arena.bounds().maxZ())/2*factor;
        for(var box:arena.boxes()) {
            float w=Math.max(1,box.size().x()*factor),h=Math.max(1,box.size().z()*factor);
            ui.rect("preview-box-"+box.id(),originX+(box.center().x()-box.size().x()/2)*factor,originY+(box.center().z()-box.size().z()/2)*factor,w,h,new ColorRGBA(.30f,.37f,.39f,.8f),2);
        }
        for(var launch:arena.launchPads())ui.rect("preview-launch-"+launch.id(),originX+launch.source().x()*factor-2,originY+launch.source().z()*factor-2,4,4,GameUi.ACCENT,3);
    }
}

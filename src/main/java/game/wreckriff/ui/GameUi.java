package game.wreckriff.ui;

import com.jme3.asset.AssetManager;
import com.jme3.font.*;
import com.jme3.material.*;
import com.jme3.math.*;
import com.jme3.scene.*;
import com.jme3.scene.shape.Quad;
import java.util.*;

/** Existing guiNode UI, with keyed focus and reusable controls for adaptive screens. */
public final class GameUi {
    public static final ColorRGBA INK=new ColorRGBA(0.055f,0.065f,0.078f,0.96f);
    public static final ColorRGBA PAPER=new ColorRGBA(0.92f,0.9f,0.82f,1);
    public static final ColorRGBA ACCENT=new ColorRGBA(1,0.48f,0.16f,1);
    private record Button(String id,UiBounds bounds,Geometry plate,BitmapText label,boolean enabled,Runnable action) {}
    private final AssetManager assets;
    private final BitmapFont font;
    private final BitmapFont boldFont;
    private final Node root=new Node("ui-grid");
    private final List<Button> buttons=new ArrayList<>();
    private final UiFocusModel focus=new UiFocusModel();
    private final Map<String,UiScrollModel> scrollModels=new HashMap<>();
    private String restoreFocus;
    private float scale=1,offsetX,offsetY;
    private float previousMouseX=Float.NaN,previousMouseY=Float.NaN;
    public GameUi(AssetManager assets,Node gui) {
        this.assets=assets; font=assets.loadFont("fonts/wreck.fnt"); boldFont=assets.loadFont("fonts/wreck-bold.fnt"); gui.attachChild(root);
    }
    public Node root() { return root; }
    public void resize(int width,int height) {
        scale=Math.min(width/1920f,height/1080f);
        offsetX=(width-1920*scale)/2; offsetY=(height-1080*scale)/2;
        root.setLocalScale(scale); root.setLocalTranslation(offsetX,offsetY,0);
    }
    public void usePixelCoordinates() {scale=1;offsetX=offsetY=0;root.setLocalScale(1);root.setLocalTranslation(0,0,0);}
    public void clear() {root.detachAllChildren();buttons.clear();focus.replace(List.of());restoreFocus=null;}
    public void clearPreservingFocus() {String selected=focus.selectedId();clear();restoreFocus=selected;}
    public int selection() {return Math.max(0,focus.selectedIndex());}
    public String selectedId() {return focus.selectedId();}
    public void select(int index) {if(!buttons.isEmpty())focus.select(buttons.get(Math.clamp(index,0,buttons.size()-1)).id);highlight();}
    public boolean select(String id) {boolean changed=focus.select(id);highlight();return changed;}
    public UiScrollModel scrollModel(String id,float contentHeight,float viewportHeight) {
        UiScrollModel model=scrollModels.computeIfAbsent(id,ignored->new UiScrollModel());model.resize(contentHeight,viewportHeight);return model;
    }
    public Geometry rect(String name,float x,float y,float width,float height,ColorRGBA color,float depth) {
        Geometry geometry=new Geometry(name,new Quad(width,height));
        Material material=new Material(assets,"Common/MatDefs/Misc/Unshaded.j3md"); material.setColor("Color",color);
        material.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        geometry.setMaterial(material); geometry.setLocalTranslation(x,y,depth); root.attachChild(geometry); return geometry;
    }
    public BitmapText text(String value,float x,float top,float size,ColorRGBA color) {
        BitmapText text=new BitmapText(size>=27?boldFont:font); text.setText(value); text.setSize(size); text.setColor(color);
        text.setLocalTranslation(x,top,5); root.attachChild(text); return text;
    }
    public BitmapText paragraph(String value,UiBounds bounds,float size,ColorRGBA color) {
        BitmapText text=text(value,bounds.x(),bounds.top(),Math.max(14,size),color);
        text.setBox(new com.jme3.font.Rectangle(0,0,bounds.width(),bounds.height()));
        text.setLineWrapMode(LineWrapMode.Word);return text;
    }
    public void title(String title,String subtitle) {
        rect("panel",90,85,850,910,INK,0);
        rect("stripe",90,85,8,910,ACCENT,1);
        text(title,140,930,62,PAPER); text(subtitle,144,855,21,ACCENT);
    }
    public void button(String label,float x,float y,float width,Runnable action) {
        button("button-"+buttons.size(),label,new UiBounds(x,y,width,55),true,action);
    }
    public void button(String id,String label,UiBounds bounds,boolean enabled,Runnable action) {
        if(buttons.stream().anyMatch(button->button.id.equals(id)))throw new IllegalArgumentException("Duplicate button: "+id);
        Geometry plate=rect(id,bounds.x(),bounds.y(),bounds.width(),bounds.height(),INK,1);
        float size=Math.max(14,Math.min(24,bounds.height()*.44f));
        BitmapText text=paragraph(label,new UiBounds(bounds.x()+14,bounds.y()+5,Math.max(0,bounds.width()-28),bounds.height()-10),size,PAPER);
        buttons.add(new Button(id,bounds,plate,text,enabled,Objects.requireNonNull(action)));
        refreshFocus();
        if(restoreFocus!=null && focus.select(restoreFocus))restoreFocus=null;
        highlight();
    }
    public void setEnabled(String id,boolean enabled) {
        for(int i=0;i<buttons.size();i++) {
            Button button=buttons.get(i);
            if(button.id.equals(id))buttons.set(i,new Button(button.id,button.bounds,button.plate,button.label,enabled,button.action));
        }
        refreshFocus();highlight();
    }
    private void refreshFocus() {focus.replace(buttons.stream().map(button->new UiFocusModel.Item(button.id,button.bounds,button.enabled)).toList());}
    public void move(int delta) {focus.cycle(delta);highlight();}
    public void move(UiFocusModel.Direction direction) {focus.move(direction);highlight();}
    public void activate() {int index=focus.selectedIndex();if(index>=0)buttons.get(index).action.run();}
    public void hover(float screenX,float screenY) {
        if(screenX==previousMouseX && screenY==previousMouseY) return;
        previousMouseX=screenX; previousMouseY=screenY;
        float x=(screenX-offsetX)/scale,y=(screenY-offsetY)/scale;
        for(Button button:buttons)if(button.enabled && button.bounds.contains(x,y)) {focus.select(button.id);highlight();return;}
    }
    public void click(float screenX,float screenY) {
        float x=(screenX-offsetX)/scale,y=(screenY-offsetY)/scale;
        for(Button button:List.copyOf(buttons))if(button.enabled && button.bounds.contains(x,y)) {focus.select(button.id);button.action.run();return;}
    }
    private void highlight() {
        for(Button button:buttons) {
            boolean selected=button.id.equals(focus.selectedId());
            button.plate.getMaterial().setColor("Color",!button.enabled?new ColorRGBA(.09f,.10f,.11f,.85f):selected?new ColorRGBA(.45f,.2f,.08f,1):new ColorRGBA(.13f,.15f,.17f,.98f));
            button.label.setColor(!button.enabled?new ColorRGBA(.48f,.49f,.49f,1):PAPER);
        }
    }
}

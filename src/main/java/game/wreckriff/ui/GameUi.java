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
    public static final ColorRGBA INK=new ColorRGBA(.035f,.039f,.043f,.96f);
    public static final ColorRGBA PAPER=new ColorRGBA(.94f,.91f,.84f,1);
    public static final ColorRGBA ACCENT=new ColorRGBA(.96f,.49f,.25f,1);
    public static final ColorRGBA MUTED=new ColorRGBA(.63f,.66f,.66f,1);
    public enum ButtonStyle {CONTROL,NAVIGATION,PRIMARY,QUIET,TAB}
    private record Button(String id,UiBounds bounds,Geometry plate,Geometry rail,BitmapText label,boolean enabled,
                          ButtonStyle style,boolean active,Runnable action) {}
    private final AssetManager assets;
    private final BitmapFont font;
    private final BitmapFont boldFont;
    private final Node root=new Node("ui");
    private final List<Button> buttons=new ArrayList<>();
    private final UiFocusModel focus=new UiFocusModel();
    private final Map<String,UiScrollModel> scrollModels=new HashMap<>();
    private String restoreFocus;
    private float previousMouseX=Float.NaN,previousMouseY=Float.NaN;
    public GameUi(AssetManager assets,Node gui) {
        this.assets=assets; font=assets.loadFont("fonts/wreck.fnt"); boldFont=assets.loadFont("fonts/wreck-bold.fnt"); gui.attachChild(root);
    }
    public Node root() { return root; }
    public void usePixelCoordinates() {root.setLocalScale(1);root.setLocalTranslation(0,0,0);}
    public void clear() {root.detachAllChildren();buttons.clear();focus.replace(List.of());restoreFocus=null;}
    public void clearPreservingFocus() {String selected=focus.selectedId();clear();restoreFocus=selected;}
    public String selectedId() {return focus.selectedId();}
    public boolean select(String id) {boolean found=focus.select(id);if(!found)focus.clearSelection();highlight();return found;}
    public UiScrollModel scrollModel(String id,float contentHeight,float viewportHeight) {
        UiScrollModel model=scrollModels.computeIfAbsent(id,ignored->new UiScrollModel());model.resize(contentHeight,viewportHeight);return model;
    }
    public Geometry rect(String name,float x,float y,float width,float height,ColorRGBA color,float depth) {
        Geometry geometry=new Geometry(name,new Quad(width,height));
        Material material=new Material(assets,"Common/MatDefs/Misc/Unshaded.j3md"); material.setColor("Color",color);
        material.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        geometry.setMaterial(material); geometry.setLocalTranslation(x,y,depth); root.attachChild(geometry); return geometry;
    }
    /** Locally vendored images only; missing presentation assets are visible build/runtime failures. */
    public Geometry image(String name,String path,UiBounds bounds,float depth) {
        Geometry geometry=rect(name,bounds.x(),bounds.y(),bounds.width(),bounds.height(),ColorRGBA.White,depth);
        var texture=assets.loadTexture(path);geometry.getMaterial().setTexture("ColorMap",texture);
        float targetAspect=bounds.width()/Math.max(1,bounds.height()),sourceAspect=(float)texture.getImage().getWidth()/texture.getImage().getHeight();
        float uSpan=Math.min(1,targetAspect/sourceAspect),vSpan=Math.min(1,sourceAspect/targetAspect);
        float u=(1-uSpan)/2,v=(1-vSpan)/2;
        geometry.getMesh().setBuffer(VertexBuffer.Type.TexCoord,2,new float[]{u,v,u+uSpan,v,u+uSpan,v+vSpan,u,v+vSpan});
        return geometry;
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
    public float paragraphHeight(String value,float width,float size) {
        BitmapText text=new BitmapText(size>=27?boldFont:font);text.setSize(Math.max(14,size));
        text.setBox(new com.jme3.font.Rectangle(0,0,width,100000));text.setLineWrapMode(LineWrapMode.Word);text.setText(value);
        return text.getLineHeight()*text.getLineCount();
    }
    public float fitTextSize(String value,float width,float requested) {
        float size=Math.max(14,requested);
        // Crossing the heading threshold changes the actual font. Measure that font again
        // after shrinking; fitting the bold face once can overflow when regular is rendered.
        for(int attempt=0;attempt<4;attempt++) {
            BitmapText text=new BitmapText(size>=27?boldFont:font);text.setSize(size);text.setText(value);
            if(text.getLineWidth()<=width||size<=14)return size;
            size=Math.max(14,size*Math.max(1,width-1)/Math.max(1,text.getLineWidth()));
        }
        return size;
    }
    public void button(String id,String label,UiBounds bounds,boolean enabled,Runnable action) {
        button(id,label,bounds,enabled,ButtonStyle.CONTROL,false,action);
    }
    public void button(String id,String label,UiBounds bounds,boolean enabled,ButtonStyle style,boolean active,Runnable action) {
        if(buttons.stream().anyMatch(button->button.id.equals(id)))throw new IllegalArgumentException("Duplicate button: "+id);
        Geometry plate=rect(id,bounds.x(),bounds.y(),bounds.width(),bounds.height(),INK,1);
        boolean tab=style==ButtonStyle.TAB;
        Geometry rail=rect(id+"-focus",bounds.x(),bounds.y(),tab?bounds.width():3,tab?3:bounds.height(),ACCENT,2);
        float size=fitTextSize(label,Math.max(1,bounds.width()-28),Math.max(14,Math.min(48,bounds.height()*.4f)));
        float textHeight=paragraphHeight(label,Math.max(1,bounds.width()-28),size);
        BitmapText text=paragraph(label,new UiBounds(bounds.x()+14,bounds.centerY()-textHeight/2,Math.max(0,bounds.width()-28),textHeight),size,PAPER);
        buttons.add(new Button(id,bounds,plate,rail,text,enabled,style,active,Objects.requireNonNull(action)));
        refreshFocus();
        if(restoreFocus!=null && focus.select(restoreFocus))restoreFocus=null;
        highlight();
    }
    public void setEnabled(String id,boolean enabled) {
        for(int i=0;i<buttons.size();i++) {
            Button button=buttons.get(i);
            if(button.id.equals(id))buttons.set(i,new Button(button.id,button.bounds,button.plate,button.rail,button.label,enabled,button.style,button.active,button.action));
        }
        refreshFocus();highlight();
    }
    private void refreshFocus() {focus.replace(buttons.stream().map(button->new UiFocusModel.Item(button.id,button.bounds,button.enabled)).toList());}
    public void move(UiFocusModel.Direction direction) {focus.move(direction);highlight();}
    public void activate() {int index=focus.selectedIndex();if(index>=0)buttons.get(index).action.run();}
    public void hover(float screenX,float screenY) {
        if(screenX==previousMouseX && screenY==previousMouseY) return;
        previousMouseX=screenX; previousMouseY=screenY;
        float x=screenX,y=screenY;
        for(Button button:buttons)if(button.enabled && button.bounds.contains(x,y)) {focus.select(button.id);highlight();return;}
    }
    public void click(float screenX,float screenY) {
        float x=screenX,y=screenY;
        for(Button button:List.copyOf(buttons))if(button.enabled && button.bounds.contains(x,y)) {focus.select(button.id);button.action.run();return;}
    }
    private void highlight() {
        for(Button button:buttons) {
            boolean selected=button.id.equals(focus.selectedId());
            ColorRGBA idle=switch(button.style) {
                case PRIMARY->new ColorRGBA(.48f,.20f,.085f,.98f);
                case NAVIGATION,QUIET->new ColorRGBA(.055f,.059f,.064f,.38f);
                case TAB->new ColorRGBA(.055f,.059f,.064f,.76f);
                case CONTROL->new ColorRGBA(.075f,.08f,.084f,.9f);
            };
            button.plate.getMaterial().setColor("Color",!button.enabled?new ColorRGBA(.045f,.05f,.055f,.65f)
                    :selected?new ColorRGBA(.31f,.16f,.095f,.97f):button.active?new ColorRGBA(.18f,.105f,.069f,.92f):idle);
            button.rail.setCullHint(button.enabled&&(selected||button.active||button.style==ButtonStyle.PRIMARY)?Spatial.CullHint.Never:Spatial.CullHint.Always);
            button.label.setColor(!button.enabled?new ColorRGBA(.43f,.46f,.46f,1):selected||button.active?ColorRGBA.White:PAPER);
        }
    }
}

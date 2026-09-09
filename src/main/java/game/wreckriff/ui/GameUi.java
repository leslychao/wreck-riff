package game.wreckriff.ui;

import com.jme3.asset.AssetManager;
import com.jme3.font.*;
import com.jme3.material.*;
import com.jme3.math.*;
import com.jme3.scene.*;
import com.jme3.scene.shape.Quad;
import java.util.*;

/** Small, keyboard/controller-accessible guiNode UI in a 1920x1080 logical grid. */
public final class GameUi {
    public static final ColorRGBA INK=new ColorRGBA(0.055f,0.065f,0.078f,0.96f);
    public static final ColorRGBA PAPER=new ColorRGBA(0.92f,0.9f,0.82f,1);
    public static final ColorRGBA ACCENT=new ColorRGBA(1,0.48f,0.16f,1);
    private record Button(float x,float y,float width,float height,Node node,Runnable action) {}
    private final AssetManager assets;
    private final BitmapFont font;
    private final BitmapFont boldFont;
    private final Node root=new Node("ui-grid");
    private final List<Button> buttons=new ArrayList<>();
    private int selected;
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
    public void clear() { root.detachAllChildren(); buttons.clear(); selected=0; }
    public int selection() { return selected; }
    public void select(int index) { selected=buttons.isEmpty()?0:Math.clamp(index,0,buttons.size()-1);highlight(); }
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
    public void title(String title,String subtitle) {
        rect("panel",90,85,850,910,INK,0);
        rect("stripe",90,85,8,910,ACCENT,1);
        text(title,140,930,62,PAPER); text(subtitle,144,855,21,ACCENT);
    }
    public void button(String label,float x,float y,float width,Runnable action) {
        int index=buttons.size();
        Geometry plate=rect("button-"+index,x,y,width,55,new ColorRGBA(0.13f,0.15f,0.17f,0.98f),1);
        BitmapText text=text(label,x+22,y+38,24,PAPER);
        Node marker=new Node("focus-"+index); root.attachChild(marker);
        marker.setUserData("plate",plate.getName()); marker.setUserData("text",text.getName());
        buttons.add(new Button(x,y,width,55,marker,action)); highlight();
    }
    public void move(int delta) { if(!buttons.isEmpty()) { selected=Math.floorMod(selected+delta,buttons.size()); highlight(); } }
    public void activate() { if(!buttons.isEmpty()) buttons.get(selected).action().run(); }
    public void hover(float screenX,float screenY) {
        if(screenX==previousMouseX && screenY==previousMouseY) return;
        previousMouseX=screenX; previousMouseY=screenY;
        float x=(screenX-offsetX)/scale,y=(screenY-offsetY)/scale;
        for(int i=0;i<buttons.size();i++) {
            Button b=buttons.get(i);
            if(x>=b.x && x<=b.x+b.width && y>=b.y && y<=b.y+b.height) { selected=i; highlight(); return; }
        }
    }
    public void click(float screenX,float screenY) {
        float x=(screenX-offsetX)/scale,y=(screenY-offsetY)/scale;
        for(Button b:List.copyOf(buttons)) if(x>=b.x && x<=b.x+b.width && y>=b.y && y<=b.y+b.height) { b.action.run(); return; }
    }
    private void highlight() {
        for(int i=0;i<buttons.size();i++) {
            Geometry plate=(Geometry)root.getChild("button-"+i);
            plate.getMaterial().setColor("Color",i==selected?new ColorRGBA(0.45f,0.2f,0.08f,1):new ColorRGBA(0.13f,0.15f,0.17f,0.98f));
        }
    }
}

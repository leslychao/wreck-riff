package game.wreckriff.ui;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Quad;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pixel-space enemy health overlay. Owns presentation only, with one retained view per match participant. */
public final class EnemyHealthBars implements AutoCloseable {
    /** x is the hull's projected centre; y is its projected top, before the visual gap. */
    public record Marker(int participantId,float x,float y,float healthFraction) {
        public Marker {
            if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(healthFraction))
                throw new IllegalArgumentException("Non-finite enemy health marker");
            healthFraction=Math.clamp(healthFraction,0,1);
        }
    }

    private final Node root=new Node("enemy-health-bars");
    private final Quad quad=new Quad(1,1);
    private final Material background,fill;
    private final Map<Integer,Bar> bars=new HashMap<>();
    private int viewportWidth,viewportHeight;
    private float barWidth,barHeight,gap;

    public EnemyHealthBars(AssetManager assets,Node guiNode) {
        background=material(assets,new ColorRGBA(.055f,.065f,.075f,.92f));
        fill=material(assets,new ColorRGBA(.96f,.22f,.10f,1));
        guiNode.attachChild(root);
    }

    public Node root() {return root;}
    public int retainedCount() {return bars.size();}
    public void setVisible(boolean visible) {root.setCullHint(visible?Spatial.CullHint.Inherit:Spatial.CullHint.Always);}

    public void resize(int width,int height,float uiScale) {
        if(width<=0||height<=0||!Float.isFinite(uiScale)||uiScale<=0)
            throw new IllegalArgumentException("Invalid enemy health viewport");
        viewportWidth=width;viewportHeight=height;
        float scale=height/1080f*uiScale;
        barWidth=Math.min(width,48*scale);barHeight=Math.min(height,Math.max(4,5*scale));
        gap=Math.max(3,6*scale);
        for(Bar bar:bars.values())bar.place();
    }

    public void update(List<Marker> markers) {
        for(Bar bar:bars.values())bar.node.setCullHint(Spatial.CullHint.Always);
        for(Marker marker:markers) {
            if(marker.healthFraction<=0)continue;
            Bar bar=bars.computeIfAbsent(marker.participantId,Bar::new);
            bar.marker=marker;bar.place();
            bar.node.setCullHint(viewportWidth>0?Spatial.CullHint.Inherit:Spatial.CullHint.Always);
        }
    }

    public void clear() {bars.clear();root.detachAllChildren();}
    @Override public void close() {clear();root.removeFromParent();}

    private Material material(AssetManager assets,ColorRGBA color) {
        Material material=new Material(assets,"Common/MatDefs/Misc/Unshaded.j3md");
        material.setColor("Color",color);
        material.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        material.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
        return material;
    }

    private final class Bar {
        final Node node;
        final Geometry back,health;
        Marker marker;
        Bar(int id) {
            node=new Node("enemy-health-"+id);
            back=new Geometry("background",quad);back.setMaterial(background);
            health=new Geometry("health",quad);health.setMaterial(fill);health.setLocalTranslation(0,0,.1f);
            node.attachChild(back);node.attachChild(health);root.attachChild(node);
        }
        void place() {
            if(marker==null||viewportWidth==0)return;
            float x=Math.clamp(marker.x-barWidth/2,0,viewportWidth-barWidth);
            float y=Math.clamp(marker.y+gap,0,viewportHeight-barHeight);
            node.setLocalTranslation(x,y,1);
            back.setLocalScale(barWidth,barHeight,1);
            health.setLocalScale(barWidth*marker.healthFraction,barHeight,1);
        }
    }
}

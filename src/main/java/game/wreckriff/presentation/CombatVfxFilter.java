package game.wreckriff.presentation;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.post.Filter;
import com.jme3.renderer.*;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.texture.*;
import com.jme3.texture.FrameBuffer.FrameBufferTarget;

/** Soft VFX read the scene depth while writing a different, depthless framebuffer.
 * The MSAA scene remains untouched; coverage is evaluated per original depth sample.
 */
public final class CombatVfxFilter extends Filter {
    public interface Probe { void begin(String pass); void end(String pass); }
    private CombatVisuals visuals;
    private RenderManager manager;
    private FrameBuffer composite;
    private Texture2D color;
    private Texture depth;
    private Probe probe;
    public CombatVfxFilter() {super("CombatVfx");setEnabled(false);}
    public void setProbe(Probe probe) {this.probe=probe;}
    public void bind(CombatVisuals next) {
        if(visuals!=null&&visuals!=next)visuals.bindSoftDepth(null);
        visuals=next;setEnabled(next!=null);
        if(manager!=null)manager.setHandleTranslucentBucket(next==null);
        if(next!=null&&depth!=null)next.bindSoftDepth(depth);
    }
    @Override protected void initFilter(AssetManager assets,RenderManager rm,ViewPort view,int width,int height) {
        manager=rm;
        releaseTarget();
        color=new Texture2D(width,height,Image.Format.RGBA8);
        color.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);color.setMagFilter(Texture.MagFilter.Bilinear);
        composite=new FrameBuffer(width,height,1);composite.addColorTarget(FrameBufferTarget.newTarget(color));
        material=new Material(assets,"materials/CombatComposite.j3md");
        material.setColor("Color",ColorRGBA.White);material.setTexture("Texture",color);
        manager.setHandleTranslucentBucket(visuals==null);
    }
    @Override protected boolean isRequiresDepthTexture() {return true;}
    @Override protected boolean isRequiresSceneTexture() {return false;}
    @Override protected void setDepthTexture(Texture texture) {
        depth=texture;if(visuals!=null)visuals.bindSoftDepth(texture);
    }
    @Override protected Material getMaterial() {return material;}
    @Override protected void postFrame(RenderManager rm,ViewPort view,FrameBuffer previous,FrameBuffer scene) {
        if(probe!=null)probe.begin("combat-vfx-composite");
        try {
            Renderer renderer=rm.getRenderer();
            // Color-only blit resolves an MSAA input. Scene depth is never attached to our target.
            renderer.copyFrameBuffer(previous,composite,true,false);
            renderer.setFrameBuffer(composite);rm.setCamera(view.getCamera(),false);
            if(visuals!=null)visuals.prepareSoftCamera(view.getCamera());
            view.getQueue().renderQueue(RenderQueue.Bucket.Translucent,rm,view.getCamera());
        } finally {if(probe!=null)probe.end("combat-vfx-composite");}
    }
    @Override protected void cleanUpFilter(Renderer renderer) {
        if(manager!=null)manager.setHandleTranslucentBucket(true);
        if(visuals!=null)visuals.bindSoftDepth(null);
        depth=null;manager=null;releaseTarget();
    }
    private void releaseTarget() {
        if(composite!=null){composite.dispose();composite=null;}
        if(color!=null){color.getImage().dispose();color=null;}
    }
    /** Reference for the shader's per-sample comparison, also used by boundary tests. */
    static float coverage(float particleDistance,float[] sceneDistances,float softness) {
        if(sceneDistances.length==0)throw new IllegalArgumentException("At least one depth sample required");
        float coverage=0;
        for(float distance:sceneDistances)coverage+=Math.clamp((distance-particleDistance)/Math.max(.001f,softness),0,1);
        return coverage/sceneDistances.length;
    }
}

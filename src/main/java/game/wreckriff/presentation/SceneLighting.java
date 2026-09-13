package game.wreckriff.presentation;

import com.jme3.asset.AssetManager;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Node;
import com.jme3.shadow.DirectionalLightShadowRenderer;
import com.jme3.shadow.EdgeFilteringMode;
import com.jme3.post.FilterPostProcessor;
import com.jme3.post.filters.BloomFilter;
import game.wreckriff.arena.ArenaDefinition.Theme;
import java.util.Objects;

/** One shared sun/shadow pipeline for menu and matches; never allocated again on Retry. */
public final class SceneLighting {
    private SceneLighting(){}
    public static Handle install(AssetManager assets,Node root,ViewPort viewport) {
        AmbientLight ambient=new AmbientLight();ambient.setColor(new ColorRGBA(.24f,.29f,.38f,1));root.addLight(ambient);
        DirectionalLight sun=new DirectionalLight();sun.setDirection(new Vector3f(-.72f,-.70f,.38f).normalizeLocal());
        sun.setColor(new ColorRGBA(1.32f,1.12f,.87f,1));root.addLight(sun);
        DirectionalLight rim=new DirectionalLight();rim.setDirection(new Vector3f(.65f,-.27f,-.72f).normalizeLocal());
        rim.setColor(new ColorRGBA(.14f,.20f,.29f,1));root.addLight(rim);
        DirectionalLightShadowRenderer shadows=new DirectionalLightShadowRenderer(assets,2048,3);
        shadows.setLight(sun);shadows.setLambda(.65f);shadows.setShadowIntensity(.65f);
        shadows.setEdgeFilteringMode(EdgeFilteringMode.PCF4);shadows.setShadowZExtend(180);shadows.setShadowZFadeLength(30);
        viewport.addProcessor(shadows);
        // Objects alone opt in with GlowColor. Road paint, warning frames and bright sky never bloom.
        BloomFilter bloom=new BloomFilter(BloomFilter.GlowMode.Objects);
        bloom.setDownSamplingFactor(4);bloom.setBlurScale(1.2f);bloom.setBloomIntensity(.65f);
        CombatVfxFilter combatVfx=new CombatVfxFilter();
        GlowPostProcessor post=new GlowPostProcessor(assets);post.addFilter(bloom);post.addFilter(combatVfx);viewport.addProcessor(post);
        Handle handle=new Handle(assets,ambient,sun,rim,shadows,bloom,combatVfx,post,viewport);
        handle.apply(Theme.INDUSTRIAL_YARD,true);return handle;
    }

    /** Immutable per-theme art direction. The road receives generous fill even on the night maps. */
    public record Profile(ColorRGBA ambient,ColorRGBA key,ColorRGBA rim,ColorRGBA sky,float glow) {}
    public static Profile profile(Theme theme) {
        return switch(Objects.requireNonNull(theme)) {
            case INDUSTRIAL_YARD -> new Profile(c(.28f,.33f,.43f),c(.99f,.82f,.62f),c(.20f,.30f,.45f),c(.085f,.105f,.16f),.52f);
            case CONSTRUCTION -> new Profile(c(.48f,.51f,.54f),c(.92f,.92f,.88f),c(.22f,.24f,.27f),c(.50f,.57f,.60f),.35f);
            case NEON -> new Profile(c(.29f,.34f,.45f),c(.76f,.86f,1.10f),c(.30f,.14f,.34f),c(.035f,.043f,.095f),.85f);
            case CARNIVAL -> new Profile(c(.33f,.31f,.42f),c(1.03f,.79f,.58f),c(.16f,.28f,.39f),c(.09f,.06f,.13f),.72f);
        };
    }
    private static ColorRGBA c(float red,float green,float blue) {return new ColorRGBA(red,green,blue,1);}

    private static final class GlowPostProcessor extends FilterPostProcessor {
        private Camera camera;
        private RenderManager manager;
        GlowPostProcessor(AssetManager assets) {super(assets);}
        @Override public void initialize(RenderManager renderManager,ViewPort viewport) {
            manager=renderManager;camera=viewport.getCamera();super.initialize(renderManager,viewport);
        }
        @Override public void preFrame(float tpf) {
            float near=camera.getFrustumNear(),far=camera.getFrustumFar(),left=camera.getFrustumLeft(),right=camera.getFrustumRight();
            float top=camera.getFrustumTop(),bottom=camera.getFrustumBottom();int width=camera.getWidth(),height=camera.getHeight();
            super.preFrame(tpf);
            // Disabling the final filter makes jME resize the camera with fixAspect=true,
            // even when its dimensions did not change. Preserve the garage's off-axis frame.
            // A genuine reshape or multiview resize retains jME's normal dimension handling.
            if(camera.getWidth()==width&&camera.getHeight()==height
                    &&(camera.getFrustumLeft()!=left||camera.getFrustumRight()!=right))
                camera.setFrustum(near,far,left,right,top,bottom);
        }
        void setGlowEnabled(BloomFilter bloom,boolean enabled) {
            // Before initialize(), Filter.setEnabled only changes its own flag: jME has not
            // linked it to this processor yet. Keep the active-filter index in sync as well.
            setFilterState(bloom,enabled);
        }
        void setCombatEnabled(CombatVfxFilter filter,boolean enabled) {setFilterState(filter,enabled);}
    }

    public static final class Handle {
        private final AssetManager assets;
        private final AmbientLight ambient;
        private final DirectionalLight key,rim;
        private final DirectionalLightShadowRenderer shadows;
        private final BloomFilter bloom;
        private final CombatVfxFilter combatVfx;
        private CombatVisuals combatVisuals;
        private GlowPostProcessor post;
        private final ViewPort viewport;
        private Theme theme;
        private boolean glowEnabled,menu;
        private int samples=-1;
        private Handle(AssetManager assets,AmbientLight ambient,DirectionalLight key,DirectionalLight rim,
                DirectionalLightShadowRenderer shadows,BloomFilter bloom,CombatVfxFilter combatVfx,GlowPostProcessor post,ViewPort viewport) {
            this.assets=assets;this.ambient=ambient;this.key=key;this.rim=rim;this.shadows=shadows;this.bloom=bloom;this.combatVfx=combatVfx;this.post=post;this.viewport=viewport;
        }
        /** Complete jME's one-time camera reshape before the caller frames its first scene. */
        public void initialize(RenderManager renderManager) {
            Objects.requireNonNull(renderManager,"Render manager required");
            if(!shadows.isInitialized())shadows.initialize(renderManager,viewport);
            if(!post.isInitialized())post.initialize(renderManager,viewport);
        }
        public void apply(Theme theme,boolean glowEnabled) {
            if(!menu&&this.theme==theme&&this.glowEnabled==glowEnabled)return;
            menu=false;
            Profile colors=profile(theme);this.theme=theme;this.glowEnabled=glowEnabled;
            key.setDirection(new Vector3f(-.72f,-.70f,.38f).normalizeLocal());
            rim.setDirection(new Vector3f(.65f,-.27f,-.72f).normalizeLocal());
            ambient.setColor(colors.ambient());key.setColor(colors.key());rim.setColor(colors.rim());
            viewport.setBackgroundColor(colors.sky());shadows.setShadowIntensity(.57f);
            bloom.setBloomIntensity(colors.glow());post.setGlowEnabled(bloom,glowEnabled);
            updateParticleLight();
        }
        /** Garage art direction reuses the same key, rim, shadows and optional object glow. */
        public void applyMenu(boolean glowEnabled) {
            if(menu&&this.glowEnabled==glowEnabled)return;
            menu=true;this.glowEnabled=glowEnabled;
            ambient.setColor(c(.15f,.18f,.23f));key.setColor(c(1.08f,.74f,.43f));rim.setColor(c(.25f,.53f,.75f));
            key.setDirection(new Vector3f(.50f,-.83f,-.23f).normalizeLocal());
            rim.setDirection(new Vector3f(-.55f,-.32f,.78f).normalizeLocal());
            viewport.setBackgroundColor(c(.009f,.012f,.019f));shadows.setShadowIntensity(.66f);
            bloom.setBloomIntensity(.43f);post.setGlowEnabled(bloom,glowEnabled);
            updateParticleLight();
        }
        /** Matches share this existing post processor; a closed/replaced match is explicitly unbound. */
        public void bindCombatVisuals(CombatVisuals visuals) {
            combatVisuals=visuals;combatVfx.bind(visuals);updateParticleLight();
            // Filter.setEnabled before initialization does not update jME's last-filter index.
            post.setCombatEnabled(combatVfx,visuals!=null);
        }
        public void setCombatVfxProbe(CombatVfxFilter.Probe probe) {combatVfx.setProbe(probe);}
        private void updateParticleLight() {
            if(combatVisuals!=null)combatVisuals.setLighting(key.getDirection(),key.getColor(),ambient.getColor());
        }
        public Theme theme() {return theme;}
        public boolean glowEnabled() {return glowEnabled;}
        public void setSamples(int samples) {
            if(samples!=0&&samples!=2&&samples!=4&&samples!=8)throw new IllegalArgumentException("Invalid MSAA samples");
            if(this.samples==samples)return;
            this.samples=samples;
            // UI/native-window 0 means MSAA off; post-processing represents that as one sample.
            if(!post.isInitialized()){post.setNumSamples(Math.max(1,samples));return;}
            // jME 3.8.1 setNumSamples only changes an int; reshape also retains the
            // opposite single/MS framebuffer. Recreate this one processor only on
            // a user render-setting change, keeping the same filters and lights.
            Camera camera=viewport.getCamera();RenderManager manager=post.manager;
            float near=camera.getFrustumNear(),far=camera.getFrustumFar(),left=camera.getFrustumLeft(),right=camera.getFrustumRight();
            float top=camera.getFrustumTop(),bottom=camera.getFrustumBottom();
            viewport.removeProcessor(post);
            post=new GlowPostProcessor(assets);post.setNumSamples(Math.max(1,samples));
            post.addFilter(bloom);post.addFilter(combatVfx);viewport.addProcessor(post);
            post.initialize(manager,viewport);camera.setFrustum(near,far,left,right,top,bottom);
        }
    }
}

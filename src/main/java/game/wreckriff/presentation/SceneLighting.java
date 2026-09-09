package game.wreckriff.presentation;

import com.jme3.asset.AssetManager;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
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
        FilterPostProcessor post=new FilterPostProcessor(assets);post.addFilter(bloom);viewport.addProcessor(post);
        Handle handle=new Handle(ambient,sun,rim,shadows,bloom,post,viewport);
        handle.apply(Theme.INDUSTRIAL_YARD,true);return handle;
    }

    /** Immutable per-theme art direction. The road receives generous fill even on the night maps. */
    public record Profile(ColorRGBA ambient,ColorRGBA key,ColorRGBA rim,ColorRGBA sky,float glow) {}
    public static Profile profile(Theme theme) {
        return switch(Objects.requireNonNull(theme)) {
            case INDUSTRIAL_YARD -> new Profile(c(.28f,.33f,.43f),c(.99f,.82f,.62f),c(.20f,.30f,.45f),c(.085f,.105f,.16f),.52f);
            case CONSTRUCTION -> new Profile(c(.31f,.36f,.45f),c(.79f,.92f,1.15f),c(.38f,.25f,.12f),c(.052f,.075f,.12f),.65f);
            case NEON -> new Profile(c(.29f,.34f,.45f),c(.76f,.86f,1.10f),c(.30f,.14f,.34f),c(.035f,.043f,.095f),.85f);
            case CARNIVAL -> new Profile(c(.33f,.31f,.42f),c(1.03f,.79f,.58f),c(.16f,.28f,.39f),c(.09f,.06f,.13f),.72f);
            case NECROPOLIS -> new Profile(c(.31f,.36f,.40f),c(.77f,.89f,.96f),c(.30f,.24f,.16f),c(.095f,.13f,.15f),.55f);
            case SHOW -> new Profile(c(.33f,.35f,.43f),c(.96f,1.02f,1.13f),c(.33f,.16f,.19f),c(.04f,.045f,.075f),.75f);
        };
    }
    private static ColorRGBA c(float red,float green,float blue) {return new ColorRGBA(red,green,blue,1);}

    public static final class Handle {
        private final AmbientLight ambient;
        private final DirectionalLight key,rim;
        private final DirectionalLightShadowRenderer shadows;
        private final BloomFilter bloom;
        private final FilterPostProcessor post;
        private final ViewPort viewport;
        private Theme theme;
        private boolean glowEnabled;
        private int samples=-1;
        private Handle(AmbientLight ambient,DirectionalLight key,DirectionalLight rim,
                DirectionalLightShadowRenderer shadows,BloomFilter bloom,FilterPostProcessor post,ViewPort viewport) {
            this.ambient=ambient;this.key=key;this.rim=rim;this.shadows=shadows;this.bloom=bloom;this.post=post;this.viewport=viewport;
        }
        public void apply(Theme theme,boolean glowEnabled) {
            if(this.theme==theme&&this.glowEnabled==glowEnabled)return;
            Profile colors=profile(theme);this.theme=theme;this.glowEnabled=glowEnabled;
            ambient.setColor(colors.ambient());key.setColor(colors.key());rim.setColor(colors.rim());
            viewport.setBackgroundColor(colors.sky());shadows.setShadowIntensity(.57f);
            bloom.setBloomIntensity(colors.glow());bloom.setEnabled(glowEnabled);
        }
        public Theme theme() {return theme;}
        public boolean glowEnabled() {return glowEnabled;}
        public void setSamples(int samples) {
            if(samples!=0&&samples!=2&&samples!=4&&samples!=8)throw new IllegalArgumentException("Invalid MSAA samples");
            if(this.samples==samples)return;
            this.samples=samples;
            post.setNumSamples(samples);
        }
    }
}

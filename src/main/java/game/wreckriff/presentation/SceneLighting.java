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

/** One shared sun/shadow pipeline for menu and matches; never allocated again on Retry. */
public final class SceneLighting {
    private SceneLighting(){}
    public static void install(AssetManager assets,Node root,ViewPort viewport) {
        AmbientLight ambient=new AmbientLight();ambient.setColor(new ColorRGBA(.24f,.29f,.38f,1));root.addLight(ambient);
        DirectionalLight sun=new DirectionalLight();sun.setDirection(new Vector3f(-.72f,-.70f,.38f).normalizeLocal());
        sun.setColor(new ColorRGBA(1.32f,1.12f,.87f,1));root.addLight(sun);
        DirectionalLight rim=new DirectionalLight();rim.setDirection(new Vector3f(.65f,-.27f,-.72f).normalizeLocal());
        rim.setColor(new ColorRGBA(.14f,.20f,.29f,1));root.addLight(rim);
        DirectionalLightShadowRenderer shadows=new DirectionalLightShadowRenderer(assets,2048,3);
        shadows.setLight(sun);shadows.setLambda(.65f);shadows.setShadowIntensity(.65f);
        shadows.setEdgeFilteringMode(EdgeFilteringMode.PCF4);shadows.setShadowZExtend(180);shadows.setShadowZFadeLength(30);
        viewport.addProcessor(shadows);viewport.setBackgroundColor(new ColorRGBA(.20f,.23f,.29f,1));
    }
}

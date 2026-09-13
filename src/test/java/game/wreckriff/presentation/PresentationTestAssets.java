package game.wreckriff.presentation;

import com.jme3.asset.AssetManager;
import com.jme3.asset.DesktopAssetManager;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** One decoder owner, with the completed test scene's cache released after each class. */
final class PresentationTestAssets implements AfterAllCallback {
    private static final AssetManager ASSETS=new DesktopAssetManager(true);
    PresentationTestAssets() { }
    static AssetManager shared() {return ASSETS;}
    @Override public void afterAll(ExtensionContext context) {ASSETS.clearCache();}
}

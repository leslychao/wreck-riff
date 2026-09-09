package game.wreckriff.presentation;

import com.jme3.asset.AssetManager;
import com.jme3.asset.DesktopAssetManager;

/** Mirror the application's one asset manager: decode each immutable 2K image once per test JVM. */
final class PresentationTestAssets {
    private static final AssetManager ASSETS=new DesktopAssetManager(true);
    private PresentationTestAssets() { }
    static AssetManager shared() {return ASSETS;}
}

package game.wreckriff.arena;

import com.jme3.asset.DesktopAssetManager;

/** One decoder across native fixtures; completed classes release their scene cache. */
public final class NativeArenaAssets implements org.junit.jupiter.api.extension.AfterAllCallback {
    public static final DesktopAssetManager MANAGER=new DesktopAssetManager(true);
    public NativeArenaAssets() {}
    @Override public void afterAll(org.junit.jupiter.api.extension.ExtensionContext context) {MANAGER.clearCache();}
}

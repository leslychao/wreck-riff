package game.wreckriff.arena;

import com.jme3.asset.DesktopAssetManager;

/** Match fixtures share the process asset cache exactly as real retries do. */
public final class NativeArenaAssets {
    public static final DesktopAssetManager MANAGER=new DesktopAssetManager(true);
    private NativeArenaAssets() {}
}

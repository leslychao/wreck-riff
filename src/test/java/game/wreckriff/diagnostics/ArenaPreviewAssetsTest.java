package game.wreckriff.diagnostics;

import org.junit.jupiter.api.Test;
import game.wreckriff.arena.ArenaRegistry;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class ArenaPreviewAssetsTest {
    @Test void everyRegisteredArenaHasAnUnalteredLocalPreviewWithRetainedSource() throws Exception {
        var assets=new ArrayList<VerifyAssets.Asset>();
        VerifyAssets.verifyArenaPreviews(assets);
        assertEquals(ArenaRegistry.load().entries().size(),assets.stream().filter(asset->asset.category().equals("arena-preview")).count());
        assertTrue(assets.stream().anyMatch(asset->asset.path().equals("textures/ui/arenas/provenance.json")));
    }
}

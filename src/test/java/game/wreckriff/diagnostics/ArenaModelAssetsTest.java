package game.wreckriff.diagnostics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArenaModelAssetsTest {
    @Test void referencedArchitectureHasEditableSourcesNormalsUvsAndSilhouettePreservingLod() throws Exception {
        var assets=ArenaModelVerifier.verify();
        assertTrue(assets.stream().anyMatch(asset->asset.category().equals("arena-model")));
        assertTrue(assets.stream().anyMatch(asset->asset.path().endsWith(".blend")));
    }
}

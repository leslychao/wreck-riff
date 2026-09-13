package game.wreckriff.presentation;

import com.google.gson.JsonParser;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VehicleAssetPipelineTest {
    @Test void everyPlayableAndBossProfileHasPreparedAuthoredDamageAndLodAssets() throws Exception {
        Path manifest=Path.of("src/main/resources/models/vehicles/provenance.json");
        assertTrue(Files.isRegularFile(manifest),"Offline vehicle exports must be present before an ordinary build");
        var data=JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
        Set<String> expected=new HashSet<>(List.of("rivet","grinder","spark","boss_foreman","boss_prefect","boss_emcee"));
        for(var item:data.getAsJsonArray("profiles")) {
            var profile=item.getAsJsonObject();assertTrue(expected.remove(profile.get("id").getAsString()));
            assertEquals(5,profile.get("stages").getAsInt());assertEquals(8,profile.get("regions").getAsInt());
            assertEquals(3,profile.getAsJsonArray("lodTriangles").size());
            assertTrue(Files.isRegularFile(Path.of("src/main/resources",profile.get("model").getAsString())));
        }
        assertTrue(expected.isEmpty());
    }
}

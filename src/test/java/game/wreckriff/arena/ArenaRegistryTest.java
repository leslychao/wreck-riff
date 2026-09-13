package game.wreckriff.arena;

import com.google.gson.JsonObject;
import game.wreckriff.config.Configs;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ArenaRegistryTest {
    @Test void shippedCampaignHasThreeLargeDistinctLocationsAndRetainsYard() {
        ArenaRegistry registry=ArenaRegistry.load();
        assertEquals(List.of("construction_17","neon_zero","euphoria_park"),registry.campaignIds());
        assertEquals(4,registry.entries().size());
        int[] counts={8,10,12};float[][] sizes={{1600,1200},{1800,1400},{1500,1300}};
        for(int i=0;i<counts.length;i++) {
            ArenaDefinition arena=registry.definition(registry.campaignIds().get(i));
            assertEquals(counts[i]+1,arena.spawns().size(),arena.id());
            assertEquals(0,arena.metadata().durationSeconds());
            assertEquals(4,arena.schemaVersion());assertEquals(3,arena.layoutRevision());
            assertEquals(sizes[i][0],arena.bounds().maxX()-arena.bounds().minX());
            assertEquals(sizes[i][1],arena.bounds().maxZ()-arena.bounds().minZ());
            assertEquals(i==2?6:5,arena.districts().size());
            assertTrue(arena.pickups().size()>=24);
            assertEquals(1,arena.bosses().size());
            assertTrue(arena.surfaces().stream().anyMatch(s->s.level()==1));
            for(var pad:arena.launchPads()) {
                assertTrue(arena.surfaceAt(pad.source().vector(),0,.01f).isPresent());
                assertTrue(arena.surfaceAt(pad.target().vector(),0,.01f).isPresent());
            }
            assertEquals(arena.nodes().size(),new NavGraph(arena).reachable(arena.nodes().getFirst().id()).size());
        }
        var yard=registry.definition("dead-air-yard");
        assertEquals(1,yard.layoutRevision());
        assertEquals(360,yard.metadata().durationSeconds());
        assertEquals(5,yard.spawns().size());
        assertThrows(IllegalArgumentException.class,()->registry.definition("missing"));
    }
    @Test void danglingRoadSurfaceAndDuplicateObjectAreRejectedWithIdentity() {
        JsonObject json=Configs.gson().toJsonTree(ArenaDefinition.load()).getAsJsonObject();
        json.getAsJsonArray("nodes").get(0).getAsJsonObject().addProperty("surfaceId","missing-road");
        var error=assertThrows(RuntimeException.class,()->Configs.gson().fromJson(json,ArenaDefinition.class));
        assertTrue(causes(error).contains("missing-road"));
        JsonObject duplicate=Configs.gson().toJsonTree(ArenaDefinition.load()).getAsJsonObject();
        duplicate.getAsJsonArray("boxes").add(duplicate.getAsJsonArray("boxes").get(0).deepCopy());
        assertThrows(RuntimeException.class,()->Configs.gson().fromJson(duplicate,ArenaDefinition.class));
    }
    @Test void strictBundledSchemaDoesNotSilentlyAcceptMissingTransitions() {
        JsonObject json=Configs.gson().toJsonTree(ArenaDefinition.load()).getAsJsonObject();
        json.remove("launchPads");
        assertThrows(IllegalArgumentException.class,()->Configs.validate(json,ArenaDefinition.class,"arena"));
    }
    private static String causes(Throwable t) {
        StringBuilder text=new StringBuilder();
        while(t!=null){text.append(t.getMessage());t=t.getCause();}
        return text.toString();
    }
}

package game.wreckriff.arena;

import com.google.gson.JsonObject;
import game.wreckriff.config.Configs;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ArenaRegistryTest {
    @Test void shippedCampaignHasFiveDistinctCompleteArenasAndRetainsYard() {
        ArenaRegistry registry=ArenaRegistry.load();
        assertEquals(List.of("construction_17","neon_zero","euphoria_park","ash_necropolis","doomsday_arena"),registry.campaignIds());
        assertEquals(6,registry.entries().size());
        int[] counts={6,7,7,8,4};
        for(int i=0;i<counts.length;i++) {
            ArenaDefinition arena=registry.definition(registry.campaignIds().get(i));
            assertEquals(counts[i]+1,arena.spawns().size(),arena.id());
            assertEquals(0,arena.metadata().durationSeconds());
            assertEquals(12,arena.pickups().size());
            assertEquals(2,arena.launchPads().size());
            assertEquals(1,arena.drops().size());
            assertEquals(1,arena.secrets().size());
            assertEquals(1,arena.bosses().size());
            assertEquals(1,arena.ramps().stream().filter(r->Math.abs(r.endY()-r.startY())==8).count());
            assertTrue(arena.surfaces().stream().anyMatch(s->s.level()==1));
            for(var pad:arena.launchPads()) {
                assertEquals(60,pad.target().vector().subtract(pad.source().vector()).setY(0).length(),.001);
                assertEquals(8,pad.target().y()-pad.source().y());
            }
            assertEquals(arena.nodes().size(),new NavGraph(arena).reachable(arena.nodes().getFirst().id()).size());
        }
        var yard=registry.definition("dead-air-yard");
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

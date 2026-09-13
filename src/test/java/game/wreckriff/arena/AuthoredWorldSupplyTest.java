package game.wreckriff.arena;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Revision-three supply belongs to road-connected districts rather than an eight-item cycle. */
class AuthoredWorldSupplyTest {
    private static final List<ArenaDefinition.PickupType> AMMO=List.of(
        ArenaDefinition.PickupType.HOMING_AMMO,ArenaDefinition.PickupType.POWER_AMMO,
        ArenaDefinition.PickupType.NAPALM_AMMO,ArenaDefinition.PickupType.BALLISTIC_AMMO,
        ArenaDefinition.PickupType.CANNON_AMMO,ArenaDefinition.PickupType.MINE_AMMO);

    @Test void ammunitionDistributionAndEveryDistrictCoverageMatchTheApprovedRules() {
        var registry=ArenaRegistry.load();int[][] expected={{18,18,12,8,10,6},{24,24,16,10,14,8},{30,30,20,12,18,10}};
        for(int map=0;map<expected.length;map++) {
            var arena=registry.definition(registry.campaignIds().get(map));
            for(int type=0;type<AMMO.size();type++) {
                var kind=AMMO.get(type);
                assertEquals(expected[map][type],arena.pickups().stream().filter(p->p.type()==kind).count(),arena.id()+" "+kind);
                for(var district:arena.districts())assertTrue(arena.pickups().stream()
                    .anyMatch(p->p.type()==kind&&inside(p.position(),district.boundary())),arena.id()+" / "+district.id()+" lacks "+kind);
            }
            for(var pickup:arena.pickups())if(AMMO.contains(pickup.type()))assertEquals(3000,pickup.respawnTicks());
        }
        var yard=registry.definition("dead-air-yard");
        for(var kind:AMMO)assertEquals(4,yard.pickups().stream().filter(p->p.type()==kind).count(),kind.toString());
        assertEquals(1440,yard.pickups().stream().filter(p->p.id().equals("homing-south")).findFirst().orElseThrow().respawnTicks());
    }

    @Test void everyStartHasTwoDistinctOffensiveSuppliesAwayFromItsSpawn() {
        var registry=ArenaRegistry.load();Set<ArenaDefinition.PickupType> offensive=Set.of(AMMO.get(0),AMMO.get(1),AMMO.get(4));
        for(String id:registry.campaignIds()) {
            var arena=registry.definition(id);
            for(var spawn:arena.spawns()) {
                var start=spawn.position().vector();
                var nearby=arena.pickups().stream().filter(p->offensive.contains(p.type()))
                    .filter(p->Math.abs(p.position().y()-spawn.position().y())<.1f)
                    .filter(p->p.position().vector().distance(start)<=180).toList();
                assertTrue(nearby.size()>=2,id+" spawn "+spawn.id()+" needs two local offensive choices");
                for(var pickup:arena.pickups())assertTrue(pickup.position().vector().distance(start)>20,id+" pickup directly under spawn "+spawn.id());
            }
        }
    }

    @Test void roadsHaveUniqueExplicitGeometryAndDistrictsHaveAuthoredOutlines() {
        for(String id:ArenaRegistry.load().campaignIds()) {
            var arena=ArenaRegistry.load().definition(id);assertEquals(3,arena.layoutRevision());
            assertEquals(arena.roads().size(),arena.roads().stream().map(ArenaDefinition.Road::id).distinct().count());
            assertFalse(arena.roads().isEmpty());
            assertTrue(arena.boxes().stream().noneMatch(b->b.id().contains("-block-")),"Anonymous filler grids must not re-enter the authored layouts");
            for(var district:arena.districts())assertTrue(district.boundary().size()>=4);
        }
    }

    private static boolean inside(ArenaDefinition.Vec3 p,List<ArenaDefinition.Vec3> boundary) {
        boolean inside=false;
        for(int i=0,j=boundary.size()-1;i<boundary.size();j=i++) {
            var a=boundary.get(i);var b=boundary.get(j);
            if((a.z()>p.z())!=(b.z()>p.z())&&p.x()<(b.x()-a.x())*(p.z()-a.z())/(b.z()-a.z())+a.x())inside=!inside;
        }
        return inside;
    }
}

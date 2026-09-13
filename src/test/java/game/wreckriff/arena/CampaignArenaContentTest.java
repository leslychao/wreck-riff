package game.wreckriff.arena;

import com.jme3.math.Vector3f;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Behaviour and geometric topology of the three complete campaign locations. */
class CampaignArenaContentTest {
    @Test void eachMapHasIndependentDistrictsAndAccessibleSurfaceBoundSpawnsAndSupply() {
        var registry=ArenaRegistry.load();
        for(String id:registry.campaignIds()) {
            var arena=registry.definition(id);var graph=new NavGraph(arena);
            for(var district:arena.districts()) {
                assertTrue(district.patrolNodeIds().size()>=2);
                long exits=arena.edges().stream().filter(e->e.type()!=ArenaDefinition.Transition.OPENABLE)
                        .filter(e->district.patrolNodeIds().contains(e.from())!=district.patrolNodeIds().contains(e.to())).count();
                assertTrue(exits>=2,id+"/"+district.id()+" requires independent approaches");
            }
            for(var spawn:arena.spawns())assertTrue(arena.surfaceAt(spawn.position().vector(),0,.01f).isPresent(),id+" spawn "+spawn.id());
            Set<ArenaDefinition.PickupType> types=EnumSet.noneOf(ArenaDefinition.PickupType.class);
            for(var pickup:arena.pickups()) {
                assertTrue(arena.surfaceAt(pickup.position().vector(),0,.01f).isPresent(),id+" pickup "+pickup.id());types.add(pickup.type());
                assertFalse(graph.path(graph.nearest(arena.spawns().getFirst().position().vector()),graph.nearest(pickup.position().vector()),false,2,10).isEmpty());
            }
            assertEquals(EnumSet.allOf(ArenaDefinition.PickupType.class),types);
            assertTrue(arena.nodes().size()>=100);assertTrue(arena.boxes().stream().filter(b->b.id().endsWith("-roof")).count()>=3);
        }
    }
    @Test void pitTunnelAndLakeHaveGenuineOpeningsInsteadOfInvisibleFlatFloors() {
        var registry=ArenaRegistry.load();var construction=registry.definition("construction_17");
        assertTrue(construction.surfaceAt(new Vector3f(465,0,530),0,.01f).isEmpty());
        assertTrue(construction.surfaceAt(new Vector3f(465,-14,530),0,.01f).isPresent());
        assertEquals(-7,construction.surfaceHeight("pit-west-slope",322.5f,530),.001f);
        assertTrue(construction.surfaceNormal("pit-west-slope",322.5f,530).x>0);
        var neon=registry.definition("neon_zero");
        assertTrue(neon.surfaceAt(new Vector3f(1190,-12,680),0,.01f).isPresent());
        assertTrue(neon.surfaceAt(new Vector3f(1190,0,680),0,.01f).isEmpty());
        assertTrue(neon.surfaceAt(new Vector3f(1530,8,240),0,.01f).isPresent());
        assertTrue(neon.surfaceAt(new Vector3f(1530,16,440),0,.01f).isPresent());
        var carnival=registry.definition("euphoria_park");
        assertTrue(carnival.surfaceAt(new Vector3f(570,0,730),0,.01f).isEmpty());
        assertTrue(carnival.surfaceAt(new Vector3f(750,3,650),0,.01f).isPresent());
        assertEquals(3,carnival.roads().stream().filter(m->m.id().endsWith("lake-bridge")).count());
        assertTrue(carnival.bounds().recoveryY()>-18,"Lake bed falls below recovery, avoiding trapped underwater cars");
        assertEquals(List.of("island-south-launch","backstage-launch"),carnival.bosses().getFirst().launchPadIds());
    }
    @Test void triangleSeamsDoNotSplitSurfaceSupportAndRotatedBoxesUseTheirRealFootprint() {
        var mesh=new ArenaDefinition.TriangleSurface("slope",List.of(new ArenaDefinition.Vec3(0,0,0),new ArenaDefinition.Vec3(10,2,0),
                new ArenaDefinition.Vec3(10,2,10),new ArenaDefinition.Vec3(0,0,10)),List.of(0,3,2,0,2,1),"concrete",true);
        assertTrue(mesh.containsXZ(5,5,2));assertEquals(1,mesh.heightAt(5,5),.0001f);assertTrue(mesh.normalAt(5,5).x<0);
        assertFalse(mesh.containsXZ(.2f,5,1));assertTrue(Float.isNaN(mesh.heightAt(-1,5)));
        var box=new ArenaDefinition.BoxPart("turned",new ArenaDefinition.Vec3(0,0,0),new ArenaDefinition.Vec3(12,2,2),"steel",true,90);
        assertTrue(box.containsXZ(0,5,0));assertFalse(box.containsXZ(5,0,0));
    }
    @Test void allBossesRemainBoundToTheirExistingCombatProfilesAndLocalEntrances() {
        var registry=ArenaRegistry.load();String[] ids={"boss_foreman","boss_prefect","boss_emcee"};float[] hp={4000,3400,4000};
        for(int i=0;i<ids.length;i++) {
            var arena=registry.definition(registry.campaignIds().get(i));var boss=arena.bosses().getFirst();
            assertEquals(ids[i],boss.profileId());assertEquals(hp[i],boss.maximumHp());
            for(var entrance:boss.entrances())assertTrue(arena.surfaceAt(entrance.position().vector(),0,.01f).isPresent());
        }
        assertEquals(2,registry.definition("neon_zero").barriers().size());
        assertThrows(IllegalArgumentException.class,()->registry.definition("ash_necropolis"));
        assertThrows(IllegalArgumentException.class,()->registry.definition("doomsday_arena"));
    }
}

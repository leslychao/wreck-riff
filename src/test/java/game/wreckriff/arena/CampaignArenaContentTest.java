package game.wreckriff.arena;

import com.jme3.math.Vector3f;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Authored release geometry checks, independent of the authoring generator. */
class CampaignArenaContentTest {
    @Test void allTenLaunchAnchorsAndFiveRampAnchorsMatchTheSpecification() {
        var registry=ArenaRegistry.load();
        float[][][] anchors={{{30,120,90,120},{218,174,158,174},{132,38,132,104}},
                {{28,128,88,128},{252,200,192,200},{140,42,140,108}},
                {{10,144,70,144},{250,192,190,192},{184,58,184,124}},
                {{28,134,88,134},{232,194,172,194},{132,46,132,112}},
                {{14,136,74,136},{226,204,166,204},{120,50,120,116}}};
        for(int i=0;i<anchors.length;i++) {
            var arena=registry.definition(registry.campaignIds().get(i));
            for(int j=0;j<2;j++) {
                var pad=arena.launchPads().get(j);var p=anchors[i][j];
                assertEquals(new Vector3f(p[0],0,p[1]),pad.source().vector());
                assertEquals(new Vector3f(p[2],8,p[3]),pad.target().vector());
                assertEquals(12,pad.compressionTicks());assertEquals(120,pad.rearmTicks());
                assertEquals(2.1f,pad.flightSeconds());assertEquals(55,pad.maximumEntryAngle());
                assertEquals(12,pad.length());assertEquals(10,pad.width());
                assertTrue(arena.surfaceAt(pad.source().vector(),0,.01f).isPresent());
                assertTrue(arena.surfaceAt(pad.target().vector(),0,.01f).isPresent());
            }
            var ramp=arena.ramps().stream().filter(r->r.id().equals("upper-ramp")).findFirst().orElseThrow();
            var p=anchors[i][2];
            assertEquals(p[0],(ramp.minX()+ramp.maxX())/2);assertEquals(p[1],ramp.minZ());
            assertEquals(p[3],ramp.maxZ());assertEquals(24,ramp.maxX()-ramp.minX());
            assertEquals(0,ramp.startY());assertEquals(8,ramp.endY());
        }
    }

    @Test void pitAndFinalHoleArePhysicalOpeningsAndCarnivalHasTwoSeparateBridges() {
        var registry=ArenaRegistry.load();var construction=registry.definition("construction_17");
        assertTrue(construction.surfaceAt(new Vector3f(72,0,66),0,.01f).isEmpty(),"No invisible flat floor over the pit");
        assertEquals("pit-floor",construction.surfaceAt(new Vector3f(72,-3,66),0,.01f).orElseThrow().id());
        assertEquals(2,construction.ramps().stream().filter(r->r.id().startsWith("pit-")).count());
        var finalArena=registry.definition("doomsday_arena");
        assertTrue(finalArena.surfaceAt(new Vector3f(120,8,170),0,.01f).isEmpty(),"The gallery is a ring, not a filled slab");
        assertTrue(finalArena.surfaceAt(new Vector3f(120,0,170),0,.01f).isPresent());
        for(var p:List.of(new Vector3f(74,8,170),new Vector3f(166,8,170),new Vector3f(120,8,136),new Vector3f(120,8,204)))
            assertTrue(finalArena.surfaceAt(p,0,.01f).isPresent());
        var carnival=registry.definition("euphoria_park");
        assertEquals("south-gallery",carnival.surfaceAt(new Vector3f(130,8,142),0,.01f).orElseThrow().id());
        assertEquals("north-gallery",carnival.surfaceAt(new Vector3f(130,8,194),0,.01f).orElseThrow().id());
        assertTrue(carnival.surfaceAt(new Vector3f(130,8,168),0,.01f).isEmpty());
        assertEquals(List.of("launch-a","launch-b"),carnival.bosses().getFirst().launchPadIds());
    }

    @Test void socketBindingsAndRespawnsKeepTheExistingArsenal() {
        var registry=ArenaRegistry.load();
        var types=List.of(ArenaDefinition.PickupType.HOMING_AMMO,ArenaDefinition.PickupType.POWER_AMMO,
                ArenaDefinition.PickupType.MINE_AMMO,ArenaDefinition.PickupType.NAPALM_AMMO,
                ArenaDefinition.PickupType.BALLISTIC_AMMO,ArenaDefinition.PickupType.CANNON_AMMO,
                ArenaDefinition.PickupType.HOMING_AMMO,ArenaDefinition.PickupType.BALLISTIC_AMMO,
                ArenaDefinition.PickupType.REPAIR,ArenaDefinition.PickupType.REPAIR,
                ArenaDefinition.PickupType.TURBO_CELL,ArenaDefinition.PickupType.POWER_AMMO);
        int[] seconds={25,25,25,25,25,25,25,45,40,40,30,60};
        for(String id:registry.campaignIds()) {
            var arena=registry.definition(id);
            assertEquals(types,arena.pickups().stream().map(ArenaDefinition.Pickup::type).toList());
            for(int i=0;i<seconds.length;i++)assertEquals(seconds[i]*120,arena.pickups().get(i).respawnTicks(),id);
            assertEquals("S1",arena.secrets().getFirst().pickupId());
            assertEquals(0,arena.metadata().recoveryCost());
        }
    }

    @Test void finalMobilityPickupClearsTheActualRampAndAllBossContractsAreBound() {
        var registry=ArenaRegistry.load();var finalArena=registry.definition("doomsday_arena");
        var socket=finalArena.pickups().stream().filter(p->p.id().equals("M1")).findFirst().orElseThrow();
        assertEquals(new Vector3f(138,0,92),socket.position().vector(),"Approved 6m correction of the obstructed original socket");
        var ramp=finalArena.ramps().getFirst();
        assertTrue(socket.position().x()-ramp.maxX()>5);
        float[] hp={4000,3400,4000,4600,5600};
        String[] bossIds={"boss_foreman","boss_prefect","boss_emcee","boss_ash_shepherd","boss_director"};
        for(int i=0;i<hp.length;i++) {
            var boss=registry.definition(registry.campaignIds().get(i)).bosses().getFirst();
            assertEquals(bossIds[i],boss.id());assertEquals(bossIds[i],boss.profileId());assertEquals(hp[i],boss.maximumHp());
            assertEquals(2,boss.entrances().size());assertEquals(2,boss.quotes().size());
        }
        var neon=registry.definition("neon_zero");
        assertEquals(2,neon.barriers().size());
        for(var barrier:neon.barriers()) {
            assertEquals(180,barrier.warningTicks());assertTrue(barrier.offTicks()>=1680);
            assertFalse(neon.boxes().stream().filter(b->b.id().equals(barrier.geometryId())).findFirst().orElseThrow().collision());
        }
    }
}

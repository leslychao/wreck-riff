package game.wreckriff.diagnostics;

import game.wreckriff.config.ProgressStore;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SoakScheduleTest {
    private final List<String> arenas=List.of("dead-air-yard","construction_17","neon_zero","euphoria_park","ash_necropolis","doomsday_arena");
    private void loaded(SoakSchedule schedule,SoakSchedule.Selection selection) {
        schedule.loaded(selection,UUID.randomUUID(),selection.arenaId(),selection.mode(),selection.profileId());
    }
    @Test void allArenasAndBossModesUseIndependentReconstructionAndTwoActualRetriesPerVisit() {
        var schedule=new SoakSchedule(arenas,"dead-air-yard");var selection=schedule.first();
        assertThrows(IllegalStateException.class,schedule::next);
        for(int load=0;load<36;load++) {
            if(load>0)selection=schedule.next();
            assertEquals(load%3!=0,selection.retry());
            loaded(schedule,selection);
        }
        assertEquals(24,schedule.retries());assertEquals(11,schedule.evidence().get("mapChanges"));
        assertTrue(schedule.resourcesWarmed());assertEquals(6,((List<?>)schedule.evidence().get("arenaIds")).size());
        assertFalse(schedule.coverageComplete());
        schedule.frame(new FrameSample(1,1,"construction_17",FrameSample.Phase.ARENA_COMBAT,1800,7,50,3,4,12,1,false,0));
        assertEquals(0,schedule.measuredSeconds());
        schedule.frame(new FrameSample(2,2,"construction_17",FrameSample.Phase.ARENA_COMBAT,1800,7,50,3,4,12,1,true,0));
        assertTrue(schedule.coverageComplete());
    }
    @Test void retryRequestsDoNotCountUntilAFreshRequestedRuntimeHasActuallyLoaded() {
        var schedule=new SoakSchedule(arenas,"neon_zero");var first=schedule.first();UUID session=UUID.randomUUID();
        schedule.loaded(first,session,first.arenaId(),first.mode(),first.profileId());
        var retry=schedule.next();assertTrue(retry.retry());assertEquals(0,schedule.retries());
        assertThrows(IllegalStateException.class,()->schedule.loaded(retry,session,first.arenaId(),first.mode(),first.profileId()));
        assertThrows(IllegalStateException.class,()->schedule.loaded(retry,UUID.randomUUID(),"construction_17",first.mode(),first.profileId()));
        assertEquals(0,schedule.retries());loaded(schedule,retry);assertEquals(1,schedule.retries());
    }
    @Test void firstArenaIsRespectedAndLegacyNeverInventsABossDuel() {
        var schedule=new SoakSchedule(arenas,"construction_17");var selection=schedule.first();
        assertEquals("construction_17",selection.arenaId());
        for(int i=0;i<60;i++) {
            if(i>0)selection=schedule.next();
            if(selection.arenaId().equals("dead-air-yard"))assertEquals(ProgressStore.Mode.LEGACY,selection.mode());
            loaded(schedule,selection);
        }
    }
}

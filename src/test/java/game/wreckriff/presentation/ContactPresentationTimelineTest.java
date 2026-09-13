package game.wreckriff.presentation;

import com.jme3.math.Vector3f;
import game.wreckriff.simulation.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContactPresentationTimelineTest {
    private final UUID session = UUID.randomUUID();
    private GameEvent event(GameEvent.Type type,long id,float loss) {
        return new GameEvent(type,id,1,0,new Vector3f(0,0,18),"machine-gun",loss,
                Vector3f.ZERO,Vector3f.UNIT_Z).inSession(session);
    }
    @Test void contactSoundAndDamageShareOneDeadlineWhileHealthRemainsAuthoritative() {
        var timeline=new ContactPresentationTimeline(session);
        var hit=event(GameEvent.Type.IMPACT,1,0);
        var damage=event(GameEvent.Type.DAMAGE,1,7).withHealthChange(new HealthChange(100,93));
        assertTrue(timeline.accept(List.of(hit,damage),2).isEmpty());
        assertEquals(100,timeline.visibleHp(1,93,100));
        assertTrue(timeline.advanceTo(2.099).isEmpty());
        assertEquals(List.of(hit,damage),timeline.advanceTo(2.101));
        assertEquals(93,timeline.visibleHp(1,93,100));
    }
    @Test void repairIsAnOrderedRevisionBarrierAndLaterDamageSurvives() {
        var timeline=new ContactPresentationTimeline(session);
        var first=event(GameEvent.Type.DAMAGE,1,7);
        var repair=event(GameEvent.Type.REPAIRED,2,7).withHealthChange(new HealthChange(93,100));
        var second=event(GameEvent.Type.DAMAGE,3,5);
        assertEquals(List.of(repair),timeline.accept(List.of(first,repair,second),0));
        assertEquals(100,timeline.visibleHp(1,95,100));
        assertEquals(List.of(second),timeline.advanceTo(.101));
    }
    @Test void deathImmediatelyExposesWreckAndCancelsDeferredDeformation() {
        var timeline=new ContactPresentationTimeline(session);
        var damage=event(GameEvent.Type.DAMAGE,1,100);
        var death=event(GameEvent.Type.DESTROYED,1,0);
        assertEquals(List.of(death),timeline.accept(List.of(event(GameEvent.Type.IMPACT,1,0),damage,death),0));
        assertEquals(0,timeline.visibleHp(1,0,100));
        assertTrue(timeline.advanceTo(1).isEmpty());
    }
    @Test void pausedClockDoesNotAdvanceAndDuplicateOrForeignEventsCannotReplay() {
        var timeline=new ContactPresentationTimeline(session);
        var hit=event(GameEvent.Type.IMPACT,1,0);
        timeline.accept(List.of(hit,hit),1);
        assertTrue(timeline.advanceTo(1).isEmpty());
        assertTrue(timeline.advanceTo(1).isEmpty());
        assertEquals(List.of(hit),timeline.advanceTo(1.101));
        assertTrue(timeline.accept(List.of(hit),2).isEmpty());
        assertTrue(timeline.accept(List.of(new GameEvent(GameEvent.Type.IMPACT,2,1,0,
                Vector3f.ZERO,"power",0).inSession(UUID.randomUUID())),2).isEmpty());
        timeline.close();assertEquals(0,timeline.pendingCount());
    }
    @Test void projectilesAndContactlessDamageDoNotAcquireBulletDelay() {
        var timeline=new ContactPresentationTimeline(session);
        var hit=new GameEvent(GameEvent.Type.DAMAGE,3,1,0,Vector3f.ZERO,"napalm",2).inSession(session);
        assertEquals(List.of(hit),timeline.accept(List.of(hit),0));
    }
    @Test void capacityIsBoundedWithoutShowingOverflowHealthLossBeforeContact() {
        var timeline=new ContactPresentationTimeline(session);
        for(int i=0;i<3000;i++)timeline.accept(List.of(event(GameEvent.Type.DAMAGE,i,1)),0);
        assertTrue(timeline.pendingCount()<=ContactPresentationTimeline.MAX_PENDING);
        assertEquals(4000,timeline.visibleHp(1,1000,4000));
        timeline.advanceTo(.101);
        assertEquals(1000,timeline.visibleHp(1,1000,4000));
    }
}

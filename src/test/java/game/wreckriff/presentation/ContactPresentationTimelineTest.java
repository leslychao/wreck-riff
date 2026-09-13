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
        timeline.accept(List.of(hit,damage),2);
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
        timeline.accept(List.of(first,repair,second),0);
        assertEquals(List.of(repair),timeline.advanceTo(0));
        assertEquals(100,timeline.visibleHp(1,95,100));
        assertEquals(List.of(second),timeline.advanceTo(.101));
    }
    @Test void deathImmediatelyExposesWreckAndCancelsDeferredDeformation() {
        var timeline=new ContactPresentationTimeline(session);
        var damage=event(GameEvent.Type.DAMAGE,1,100);
        var death=event(GameEvent.Type.DESTROYED,1,0);
        timeline.accept(List.of(event(GameEvent.Type.IMPACT,1,0),damage,death),0);
        assertEquals(List.of(death),timeline.advanceTo(0));
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
        timeline.accept(List.of(hit),2);
        timeline.accept(List.of(new GameEvent(GameEvent.Type.IMPACT,2,1,0,
                Vector3f.ZERO,"power",0).inSession(UUID.randomUUID())),2);
        assertTrue(timeline.advanceTo(2).isEmpty());
        timeline.close();assertEquals(0,timeline.pendingCount());
    }
    @Test void projectilesAndContactlessDamageDoNotAcquireBulletDelay() {
        var timeline=new ContactPresentationTimeline(session);
        var hit=new GameEvent(GameEvent.Type.DAMAGE,3,1,0,Vector3f.ZERO,"napalm",2).inSession(session);
        timeline.accept(List.of(hit),0);
        assertEquals(1,timeline.pendingCount());
        assertEquals(List.of(hit),timeline.advanceTo(0));
    }
    @Test void completedFixedTickTimestampOwnsTheDeadlineRatherThanTheTimeOfBatchDrain() {
        var timeline=new ContactPresentationTimeline(session);
        var hit=event(GameEvent.Type.IMPACT,1,0).atTick(120,0);
        timeline.accept(List.of(hit),1.0+1.0/120);
        assertTrue(timeline.advanceTo(1.099).isEmpty());
        assertEquals(List.of(hit),timeline.advanceTo(1.101));
    }
    @Test void capacityIsBoundedWithoutShowingOverflowHealthLossBeforeContact() {
        var timeline=new ContactPresentationTimeline(session);
        for(int i=0;i<3000;i++)timeline.accept(List.of(event(GameEvent.Type.DAMAGE,i,1)),0);
        assertTrue(timeline.pendingCount()<=ContactPresentationTimeline.MAX_PENDING);
        assertEquals(4000,timeline.visibleHp(1,1000,4000));
        timeline.advanceTo(.101);
        assertEquals(1000,timeline.visibleHp(1,1000,4000));
    }
    @Test void immediateAndTravellingContactsShareChronologicalRenderDelivery() {
        var timeline=new ContactPresentationTimeline(session);
        var shot=new GameEvent(GameEvent.Type.SHOT,1,0,0,Vector3f.ZERO,"power",0).inSession(session).atTick(0,0);
        var bullet=event(GameEvent.Type.IMPACT,2,0).atTick(0,1);
        var cannon=new GameEvent(GameEvent.Type.IMPACT,3,1,0,Vector3f.ZERO,"cannon",0).inSession(session).atTick(18,0);
        timeline.accept(List.of(shot,bullet),1.0/120);timeline.accept(List.of(cannon),19.0/120);
        assertEquals(List.of(shot,bullet,cannon),timeline.advanceTo(.16));
        assertTrue(timeline.advanceTo(.16).isEmpty());
    }
    @Test void completedTickIsNotPresentedBeforeItsInterpolatedRenderTime() {
        var timeline=new ContactPresentationTimeline(session);
        var hit=new GameEvent(GameEvent.Type.DAMAGE,1,1,0,Vector3f.ZERO,"power",7).inSession(session).atTick(120,0);
        timeline.accept(List.of(hit),121.0/120);
        assertTrue(timeline.advanceTo(.999).isEmpty());assertEquals(100,timeline.visibleHp(1,93,100));
        assertEquals(List.of(hit),timeline.advanceTo(1));assertEquals(93,timeline.visibleHp(1,93,100));
    }
    @Test void deathRetainsEarlierAcceptedShotAndRepairButCancelsPendingContacts() {
        var timeline=new ContactPresentationTimeline(session);
        var shot=new GameEvent(GameEvent.Type.SHOT,1,1,1,Vector3f.ZERO,"power",0).inSession(session);
        var repair=event(GameEvent.Type.REPAIRED,2,7).withHealthChange(new HealthChange(93,100));
        var death=event(GameEvent.Type.DESTROYED,4,0);
        timeline.accept(List.of(shot,repair,event(GameEvent.Type.IMPACT,3,0),event(GameEvent.Type.DAMAGE,3,100),death),0);
        assertEquals(List.of(shot,repair,death),timeline.advanceTo(0));assertTrue(timeline.advanceTo(1).isEmpty());
    }
    @Test void fullDetailQueuePreservesEveryParticipantsRepairAndDeath() {
        var timeline=new ContactPresentationTimeline(session);
        for(int i=0;i<3000;i++)timeline.accept(List.of(event(GameEvent.Type.IMPACT,i,0)),0);
        var critical=new ArrayList<GameEvent>();
        for(int id=0;id<32;id++) {
            critical.add(new GameEvent(GameEvent.Type.REPAIRED,4000+id,id,id,Vector3f.ZERO,"repair",10)
                    .withHealthChange(new HealthChange(90,100)).inSession(session));
            critical.add(new GameEvent(GameEvent.Type.DESTROYED,5000+id,id,id,Vector3f.ZERO,"power",0).inSession(session));
        }
        timeline.accept(critical,0);assertTrue(timeline.pendingCount()<=ContactPresentationTimeline.MAX_PENDING);
        assertEquals(critical,timeline.advanceTo(0));assertTrue(timeline.suppressedDetails()>0);
    }
    @Test void repeatedRepairsRemainDistinctAndOrderedWhenTheyBorrowTheDetailBudget() {
        var timeline=new ContactPresentationTimeline(session);var repairs=new ArrayList<GameEvent>();
        for(int i=0;i<100;i++)repairs.add(new GameEvent(GameEvent.Type.REPAIRED,4000+i,2,2,Vector3f.ZERO,"repair",1)
                .withHealthChange(new HealthChange(50+i,51+i)).inSession(session));
        timeline.accept(repairs,0);
        for(int i=0;i<3000;i++)timeline.accept(List.of(event(GameEvent.Type.IMPACT,i,0)),0);
        assertTrue(timeline.pendingCount()<=ContactPresentationTimeline.MAX_PENDING);assertEquals(repairs,timeline.advanceTo(0));
    }
    @Test void closingAfterTerminalDrainReleasesAllPendingDetailsHealthDebtAndDedupe() {
        var timeline=new ContactPresentationTimeline(session);
        for(int i=0;i<3000;i++)timeline.accept(List.of(event(GameEvent.Type.DAMAGE,i,1)),0);
        timeline.close();assertEquals(0,timeline.pendingCount());assertEquals(100,timeline.visibleHp(1,100,4000));
        timeline.accept(List.of(event(GameEvent.Type.REPAIRED,5000,1)),1);assertTrue(timeline.advanceTo(10).isEmpty());
    }
    @Test void excessiveCriticalInputFailsExplicitlyWithoutDroppingPreviouslyAcceptedRepairs() {
        var timeline=new ContactPresentationTimeline(session);var repairs=new ArrayList<GameEvent>();
        for(int i=0;i<ContactPresentationTimeline.MAX_PENDING;i++)repairs.add(event(GameEvent.Type.REPAIRED,i,1));
        timeline.accept(repairs,0);
        var rejected=event(GameEvent.Type.REPAIRED,2000,1);
        assertThrows(IllegalStateException.class,()->timeline.accept(List.of(rejected),0));
        assertEquals(repairs,timeline.advanceTo(0));
        timeline.accept(List.of(rejected),0);assertEquals(List.of(rejected),timeline.advanceTo(0));
    }
    @Test void earlierBulletCannonAndRepairDrainInOneChronologicalRenderBatch() {
        var timeline=new ContactPresentationTimeline(session);
        var bullet=event(GameEvent.Type.IMPACT,1,0).atTick(0,0);
        var cannon=new GameEvent(GameEvent.Type.IMPACT,2,1,0,Vector3f.ZERO,"cannon",0).inSession(session).atTick(15,0);
        var repair=event(GameEvent.Type.REPAIRED,3,10).withHealthChange(new HealthChange(90,100)).atTick(18,0);
        timeline.accept(List.of(bullet),1.0/120);timeline.accept(List.of(cannon,repair),19.0/120);
        assertEquals(List.of(bullet,cannon,repair),timeline.advanceTo(.16));
    }
    @Test void partialRepairRetainsAlreadyDueDamageHistoryAndCancelsOnlyFutureDamage() {
        var timeline=new ContactPresentationTimeline(session);
        var earlier=event(GameEvent.Type.DAMAGE,1,7).atTick(0,0);
        var later=event(GameEvent.Type.DAMAGE,2,7).atTick(12,0);
        var cannon=new GameEvent(GameEvent.Type.DAMAGE,3,1,0,Vector3f.ZERO,"cannon",25).inSession(session).atTick(15,0);
        var repair=event(GameEvent.Type.REPAIRED,4,10).withHealthChange(new HealthChange(61,71)).atTick(18,0);
        timeline.accept(List.of(earlier,later,cannon,repair),19.0/120);
        assertEquals(List.of(earlier,cannon,repair),timeline.advanceTo(.16));
        assertTrue(timeline.advanceTo(.3).isEmpty());
    }
    @Test void sameTickImmediateDamageRetainsOrdinalAroundRepairAndBeforeDeath() {
        var timeline=new ContactPresentationTimeline(session);
        var first=new GameEvent(GameEvent.Type.DAMAGE,1,1,0,Vector3f.ZERO,"cannon",25).inSession(session).atTick(120,0);
        var repair=event(GameEvent.Type.REPAIRED,2,10).withHealthChange(new HealthChange(75,85)).atTick(120,1);
        var last=new GameEvent(GameEvent.Type.DAMAGE,3,1,0,Vector3f.ZERO,"power",85).inSession(session).atTick(120,2);
        var death=event(GameEvent.Type.DESTROYED,3,0).atTick(120,3);
        timeline.accept(List.of(first,repair,last,death),121.0/120);
        assertEquals(List.of(first,repair,last,death),timeline.advanceTo(1));assertTrue(timeline.advanceTo(2).isEmpty());
    }
}

package game.wreckriff.ai;

import com.jme3.math.Vector3f;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BossTacticsTest {
    @Test void allFiveTelegraphsChargeAndRecoverForTheirFullSpecifiedDurationsInEveryMode() {
        for(String id:List.of("boss_foreman","boss_prefect","boss_emcee","boss_ash_shepherd","boss_director"))
            for(int mode=1;mode<=3;mode++) {
                var policy=new BossTactics(id);policy.advance(400,mode);
                policy.beginRam(400,new Vector3f(),new Vector3f(0,0,40));
                assertEquals(BossTactics.Phase.TELEGRAPH,policy.phase);
                policy.advance(400+policy.timing.telegraph()-1,mode);assertEquals(BossTactics.Phase.TELEGRAPH,policy.phase);
                long charging=400+policy.timing.telegraph();policy.advance(charging,mode);assertEquals(BossTactics.Phase.CHARGE,policy.phase);
                policy.advance(charging+policy.timing.charge()-1,mode);assertEquals(BossTactics.Phase.CHARGE,policy.phase);
                long recovering=charging+policy.timing.charge();policy.advance(recovering,mode);
                assertEquals(BossTactics.Phase.RECOVERY,policy.phase);assertTrue(policy.completionPending);
                policy.advance(recovering+policy.timing.recovery()-1,mode);assertEquals(BossTactics.Phase.RECOVERY,policy.phase);
                policy.advance(recovering+policy.timing.recovery(),mode);assertEquals(BossTactics.Phase.CRUISE,policy.phase);
            }
    }
    @Test void modeEscalationNeverRewindsAndNeverShortensAnAlreadyAdvertisedTelegraph() {
        var policy=new BossTactics("boss_foreman");policy.beginRam(400,new Vector3f(),new Vector3f(0,0,40));
        long deadline=policy.untilTick;policy.advance(450,3);policy.advance(480,1);
        assertEquals(3,policy.mode);assertEquals(deadline,policy.untilTick);
        assertEquals(BossTactics.Phase.TELEGRAPH,policy.phase);
    }
    @Test void committedChargeKeepsTheObservedDirectionAndCannotReadNewHiddenCoordinates() {
        var policy=new BossTactics("boss_emcee");Vector3f seen=new Vector3f(0,0,40);
        policy.beginRam(400,new Vector3f(),seen);seen.x=100;
        policy.advance(520,2);policy.observed(null);
        assertEquals(new Vector3f(0,0,1),policy.chargeDirection);assertEquals(0,policy.point().x());
    }
    @Test void protocolAndRamRateLimitsRemainBoundedAndPrefectRamsOnlyInLastMode() {
        var prefect=new BossTactics("boss_prefect");
        assertFalse(prefect.canRam(1000,1));assertFalse(prefect.canRam(1000,2));assertTrue(prefect.canRam(1000,3));
        assertEquals(1680,prefect.protocolCooldown());assertEquals(1920,new BossTactics("boss_foreman").protocolCooldown());
    }
    @Test void prefectStopsHoldingItsLongDistanceInTheLastHealthMode() {
        var policy=new BossTactics("boss_prefect");assertEquals(45,policy.standOff());
        policy.advance(0,2);assertEquals(45,policy.standOff());
        policy.advance(0,3);assertEquals(18,policy.standOff());
    }
    @Test void emceeChangesRouteOnlyAfterTwoPhysicallyObservedCircuitsWithoutClosingDistance() {
        var policy=new BossTactics("boss_emcee");var center=new Vector3f();
        for(int sample=0;sample<=72;sample++) {
            float angle=(float)(sample*Math.PI/18);var position=new Vector3f(40*(float)Math.cos(angle),8,40*(float)Math.sin(angle));
            var target=new BotObservation.Opponent(0,position.mult(1.8f),Vector3f.ZERO,sample*12);
            assertEquals(sample==72,policy.observedCircuit(position,center,1,target),"sample "+sample);
        }
    }
    @Test void backtrackingClosingDistanceAndChangingFloorCannotProduceTwoFailedCircuits() {
        var policy=new BossTactics("boss_emcee");var center=new Vector3f();
        for(int sample=0;sample<100;sample++) {
            float angle=(sample%2==0?0:.2f);var point=new Vector3f(40*(float)Math.cos(angle),8,40*(float)Math.sin(angle));
            assertFalse(policy.observedCircuit(point,center,1,new BotObservation.Opponent(0,point.mult(1.8f),Vector3f.ZERO,sample)));
        }
        for(int sample=0;sample<=72;sample++) {
            float angle=(float)(sample*Math.PI/18);var point=new Vector3f(40*(float)Math.cos(angle),8,40*(float)Math.sin(angle));
            float range=sample<36?40:20;
            var target=point.add(point.clone().setY(0).normalizeLocal().multLocal(range));
            assertFalse(policy.observedCircuit(point,center,sample==54?0:1,new BotObservation.Opponent(0,target,Vector3f.ZERO,sample)));
        }
    }
}

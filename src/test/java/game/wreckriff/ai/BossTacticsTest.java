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
}

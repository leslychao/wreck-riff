package game.wreckriff.simulation;

import game.wreckriff.config.MatchRules;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.combat.AbilityId;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SimulationLoopTest {
    @Test void fixedTicksDoNotDependOnRenderFrameRate() {
        for(int fps:new int[]{30,60,144}) {
            SimulationLoop loop=new SimulationLoop(MatchRules.load()); int[] steps={0};
            for(int i=0;i<fps*10;i++) loop.advance(1.0/fps,true,()->steps[0]++);
            assertEquals(1200,steps[0],"render FPS "+fps);
        }
    }
    @Test void pauseAndLongFramesCannotCreateCatchupStorm() {
        SimulationLoop loop=new SimulationLoop(MatchRules.load()); int[] ticks={0};
        loop.advance(0.004,true,()->ticks[0]++);
        loop.advance(10,false,()->ticks[0]++); assertEquals(0,ticks[0]);
        loop.advance(1,true,()->ticks[0]++); assertEquals(12,ticks[0]);
        assertTrue(loop.droppedSimulationTime()>=0.9);
    }
    @Test void oneShotEdgesDoNotSurviveConsumptionButHeldControlsDo() {
        var command=new VehicleCommand(1,0,1,true,true,true,true,game.wreckriff.combat.WeaponType.POWER,1,true,true,AbilityId.FREEZE);
        var next=command.withoutEdges();
        assertNull(next.directWeapon()); assertEquals(0,next.weaponDelta());
        assertEquals(AbilityId.NONE,next.ability());
        assertTrue(next.machineGun()); assertTrue(next.recover());
        assertThrows(IllegalArgumentException.class,()->new VehicleCommand(Float.NaN,0,0,false,false,false,false,null,0,false,false,AbilityId.NONE));
    }
    @Test void lastTickVictoryAndSimultaneousDestructionHaveDefinedPriority() {
        MatchSession session=new MatchSession(42,1); session.tick=119;
        for(int i=1;i<5;i++) session.vehicle(i).hp=0;
        MatchRuntime.finishTick(session); assertEquals(MatchSession.Outcome.VICTORY,session.outcome);
        MatchSession draw=new MatchSession(42,1); draw.vehicles.forEach(v->v.hp=0);
        MatchRuntime.finishTick(draw); assertEquals(MatchSession.Outcome.DRAW,draw.outcome);
    }
}

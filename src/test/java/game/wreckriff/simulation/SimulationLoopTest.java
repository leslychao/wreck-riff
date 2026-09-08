package game.wreckriff.simulation;

import game.wreckriff.config.MatchRules;
import game.wreckriff.input.VehicleCommand;
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
        var command=new VehicleCommand(1,0,1,true,true,true,true,true,1,true,true);
        var next=command.withoutEdges();
        assertFalse(next.special()); assertEquals(0,next.weaponDelta());
        assertTrue(next.machineGun()); assertTrue(next.recover());
        assertThrows(IllegalArgumentException.class,()->new VehicleCommand(Float.NaN,0,0,false,false,false,false,false,0,false,false));
    }
    @Test void lastTickVictoryAndSimultaneousDestructionHaveDefinedPriority() {
        MatchSession session=new MatchSession(42,1); session.tick=119;
        for(int i=1;i<5;i++) session.vehicle(i).hp=0;
        session.finishTick(); assertEquals(MatchSession.Outcome.VICTORY,session.outcome);
        MatchSession draw=new MatchSession(42,1); draw.vehicles.forEach(v->v.hp=0);
        draw.finishTick(); assertEquals(MatchSession.Outcome.DRAW,draw.outcome);
    }
}

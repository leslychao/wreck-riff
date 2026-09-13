package game.wreckriff.app;

import game.wreckriff.config.MatchRules;
import game.wreckriff.simulation.SimulationLoop;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GameApplicationTimingTest {
    @Test void completedLoadingFrameIsNotDebtForTheNewMatchButFollowingCombatFramesAre() {
        var flow=new ScreenFlow();flow.loading();int[] ticks={0};
        SimulationLoop[] current={new SimulationLoop(MatchRules.load())};
        var loading=new MatchLoading(List.of(),()->{},()->{},()->{
            current[0]=new SimulationLoop(MatchRules.load());flow.running();
        });
        for(int i=0;i<30;i++)loading.advance();
        assertEquals(ScreenFlow.Screen.LOADING,flow.screen());assertEquals(0,ticks[0]);
        loading.frameRendered(true);
        SimulationLoop precedingLoadingLoop=current[0];loading.advance();
        assertEquals(ScreenFlow.Screen.RUNNING,flow.screen());
        assertEquals(0,GameApplication.advanceRunningFrame(current[0],precedingLoadingLoop,1,()->ticks[0]++));
        assertEquals(0,ticks[0]);assertEquals(0,current[0].droppedSimulationTime());
        assertEquals(6,GameApplication.advanceRunningFrame(current[0],current[0],.05,()->ticks[0]++));
        assertEquals(6,ticks[0]);assertEquals(0,current[0].droppedSimulationTime());
        assertEquals(12,GameApplication.advanceRunningFrame(current[0],current[0],.2,()->ticks[0]++));
        assertEquals(18,ticks[0]);assertEquals(.1,current[0].droppedSimulationTime(),1e-9,
                "A genuinely slow running frame must remain visible in the catch-up/drop metrics");
    }
}

package game.wreckriff.app;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ScreenFlowTest {
    @Test void controlsAndConfirmationReturnThroughTheFullPauseSettingsStack() {
        ScreenFlow flow=new ScreenFlow();flow.running();flow.pause();flow.open(ScreenFlow.Screen.SETTINGS);
        flow.open(ScreenFlow.Screen.CONTROLS);flow.back();assertEquals(ScreenFlow.Screen.SETTINGS,flow.screen());
        flow.open(ScreenFlow.Screen.CONFIRM);flow.back();assertEquals(ScreenFlow.Screen.SETTINGS,flow.screen());
        flow.back();assertEquals(ScreenFlow.Screen.PAUSED,flow.screen());flow.resume();assertEquals(ScreenFlow.Screen.RUNNING,flow.screen());
    }
    @Test void arenaAndStatisticsScreensReturnToMenuAndPreserveNestedOverlayParent() {
        ScreenFlow flow=new ScreenFlow();flow.menu();flow.maps();
        flow.open(ScreenFlow.Screen.CONFIRM);flow.back();
        assertEquals(ScreenFlow.Screen.MAPS,flow.screen());
        flow.menu();flow.statistics();flow.open(ScreenFlow.Screen.SETTINGS);flow.back();
        assertEquals(ScreenFlow.Screen.STATISTICS,flow.screen());
    }
    @Test void loadingEntersPlayDirectlyAndPauseOverlaysReturnToTheSameMatch() {
        ScreenFlow flow=new ScreenFlow();List<ScreenFlow.Screen> states=new ArrayList<>();
        flow.onChanged(()->states.add(flow.screen()));
        flow.menu();flow.loading();flow.running();
        flow.pause();flow.open(ScreenFlow.Screen.SETTINGS);flow.back();flow.resume();
        assertEquals(List.of(ScreenFlow.Screen.MENU,ScreenFlow.Screen.LOADING,ScreenFlow.Screen.RUNNING,
                ScreenFlow.Screen.PAUSED,ScreenFlow.Screen.SETTINGS,ScreenFlow.Screen.PAUSED,ScreenFlow.Screen.RUNNING),states);
    }
}

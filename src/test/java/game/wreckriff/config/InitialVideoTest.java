package game.wreckriff.config;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class InitialVideoTest {
    @Test void fullHdIsSelectedInsteadOfUpscalingToFourK() {
        assertEquals(new InitialVideo.Mode(1920,1080),InitialVideo.choose(List.of(
                new InitialVideo.Mode(3840,2160),new InitialVideo.Mode(1280,720),new InitialVideo.Mode(1920,1080))).orElseThrow());
    }
    @Test void smallerDisplayUsesItsSupportedModeAndNeverInventsFullHd() {
        assertEquals(new InitialVideo.Mode(1366,768),InitialVideo.choose(List.of(
                new InitialVideo.Mode(1280,720),new InitialVideo.Mode(1366,768))).orElseThrow());
        assertTrue(InitialVideo.choose(List.of()).isEmpty());
    }
}

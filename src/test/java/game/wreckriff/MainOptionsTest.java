package game.wreckriff;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MainOptionsTest {
    @Test void everyDiagnosticSwitchRequiresExplicitDevMode() {
        for(String flag:new String[]{"--seed=42","--no-audio","--ai-player","--smoke-seconds=45","--config-dir=."}) {
            assertThrows(IllegalArgumentException.class,()->Main.Options.parse(new String[]{flag}),flag);
            assertTrue(Main.Options.parse(new String[]{flag,"--dev"}).dev());
        }
    }
    @Test void seedLimitsAndSmokeBoundariesAreParsedWithoutLosingPrecision() {
        assertEquals(Long.MIN_VALUE,Main.Options.parse(new String[]{"--dev","--seed="+Long.MIN_VALUE}).seed());
        assertEquals(Long.MAX_VALUE,Main.Options.parse(new String[]{"--dev","--seed="+Long.MAX_VALUE}).seed());
        assertEquals(1,Main.Options.parse(new String[]{"--dev","--smoke-seconds=1"}).smokeSeconds());
        assertEquals(3600,Main.Options.parse(new String[]{"--dev","--smoke-seconds=3600"}).smokeSeconds());
        for(String value:new String[]{"0","3601","-1","garbage",""})
            assertThrows(IllegalArgumentException.class,()->Main.Options.parse(new String[]{"--dev","--smoke-seconds="+value}));
    }
    @Test void unknownArgumentsFailRatherThanSilentlyChangingLaunchBehavior() {
        assertThrows(IllegalArgumentException.class,()->Main.Options.parse(new String[]{"--dev","--headless"}));
        assertThrows(IllegalArgumentException.class,()->Main.Options.parse(new String[]{"--dev","--seed=9223372036854775808"}));
        assertFalse(Main.Options.parse(new String[]{}).fixedSeed());
    }
}

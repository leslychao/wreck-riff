package game.wreckriff;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MainOptionsTest {
    @Test void everyDiagnosticSwitchRequiresExplicitDevMode() {
        for(String flag:new String[]{"--seed=42","--no-audio","--ai-player","--smoke-seconds=45","--benchmark-seconds=60","--config-dir=.","--showcase"}) {
            assertThrows(IllegalArgumentException.class,()->Main.Options.parse(new String[]{flag}),flag);
            assertTrue(Main.Options.parse(new String[]{flag,"--dev"}).dev());
        }
    }
    @Test void benchmarkDurationHasExplicitBoundsAndPreservesDiagnosticOptions() {
        var options=Main.Options.parse(new String[]{"--benchmark-seconds=1","--seed=42","--ai-player","--no-audio","--dev"});
        assertEquals(1,options.benchmarkSeconds());assertEquals(0,options.smokeSeconds());
        assertEquals(42L,options.seed());assertTrue(options.fixedSeed());
        assertTrue(options.aiPlayer());assertTrue(options.noAudio());assertTrue(options.automated());
        assertEquals(3600,Main.Options.parse(new String[]{"--dev","--benchmark-seconds=3600"}).benchmarkSeconds());
        for(String value:new String[]{"0","3601","-1","garbage","","2147483648"})
            assertThrows(IllegalArgumentException.class,()->Main.Options.parse(new String[]{"--dev","--benchmark-seconds="+value}),value);
    }
    @Test void smokeAndBenchmarkCannotCompeteForOneDiagnosticRun() {
        assertThrows(IllegalArgumentException.class,()->Main.Options.parse(new String[]{"--dev","--showcase","--smoke-seconds=30"}));
        assertThrows(IllegalArgumentException.class,()->Main.Options.parse(new String[]{"--dev","--showcase","--benchmark-seconds=30"}));
        assertThrows(IllegalArgumentException.class,()->Main.Options.parse(new String[]{"--dev","--smoke-seconds=30","--benchmark-seconds=60"}));
        assertThrows(IllegalArgumentException.class,()->Main.Options.parse(new String[]{"--dev","--benchmark-seconds=60","--smoke-seconds=30"}));
    }
    @Test void onlyBoundedSmokeAndBenchmarkModesAreAutomated() {
        assertFalse(Main.Options.parse(new String[]{}).automated());
        assertFalse(Main.Options.parse(new String[]{"--dev","--seed=42","--ai-player","--no-audio","--config-dir=."}).automated());
        assertTrue(Main.Options.parse(new String[]{"--dev","--smoke-seconds=1"}).automated());
        assertTrue(Main.Options.parse(new String[]{"--dev","--benchmark-seconds=1"}).automated());
        assertTrue(Main.Options.parse(new String[]{"--dev","--showcase"}).automated());
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

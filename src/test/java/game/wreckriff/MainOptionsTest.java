package game.wreckriff;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MainOptionsTest {
    @Test void automaticScreenshotsAreRestrictedToTheSmokeTour() {
        assertTrue(Main.Options.parse(new String[]{"--dev","--smoke-seconds=600"}).cameraTour());
        for(String mode:new String[]{"--benchmark-seconds=600","--soak-seconds=1800","--showcase","--art-showcase","--vehicle-showcase"})
            assertFalse(Main.Options.parse(new String[]{"--dev",mode}).cameraTour(),mode);
        assertFalse(Main.Options.parse(new String[]{}).cameraTour());
        assertFalse(Main.Options.parse(new String[]{"--dev"}).cameraTour());
    }
    @Test void soakHasExplicitDurationAndCannotCompeteWithAnotherDiagnostic() {
        assertThrows(IllegalArgumentException.class,()->Main.Options.parse(new String[]{"--soak-seconds=1800"}));
        var soak=Main.Options.parse(new String[]{"--dev","--soak-seconds=1800"});
        assertEquals(1800,soak.soakSeconds());assertTrue(soak.automated());assertFalse(soak.profile());
        for(String bad:new String[]{"0","-1","3601","garbage"})assertThrows(IllegalArgumentException.class,
                ()->Main.Options.parse(new String[]{"--dev","--soak-seconds="+bad}));
        for(String other:new String[]{"--smoke-seconds=1","--benchmark-seconds=1","--showcase","--art-showcase","--vehicle-showcase"})
            assertThrows(IllegalArgumentException.class,()->Main.Options.parse(new String[]{"--dev","--soak-seconds=1800",other}));
    }
    @Test void everyDiagnosticSwitchRequiresExplicitDevMode() {
        for(String flag:new String[]{"--seed=42","--no-audio","--ai-player","--smoke-seconds=45","--benchmark-seconds=60","--config-dir=.","--showcase",
                "--art-showcase","--arena=construction_17","--resolution=720p","--no-glow","--profile"}) {
            assertThrows(IllegalArgumentException.class,()->Main.Options.parse(new String[]{flag}),flag);
            assertTrue(Main.Options.parse(new String[]{flag,"--dev"}).dev());
        }
    }
    @Test void detailedProfilingIsExplicitAndDoesNotBecomeABenchmarkDefault() {
        assertFalse(Main.Options.parse(new String[]{"--dev","--benchmark-seconds=600"}).profile());
        assertTrue(Main.Options.parse(new String[]{"--dev","--benchmark-seconds=60","--profile"}).profile());
        assertFalse(Main.Options.parse(new String[]{"--dev","--profile"}).automated());
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
        assertTrue(Main.Options.parse(new String[]{"--dev","--art-showcase"}).automated());
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
    @Test void artShowcaseSelectsOneArenaAndExplicitResolutionWithoutChangingBenchmarkDefaults() {
        var art=Main.Options.parse(new String[]{"--dev","--art-showcase","--arena=neon_zero","--resolution=1080p","--no-glow"});
        assertTrue(art.artShowcase());assertFalse(art.showcase());assertEquals("neon_zero",art.arenaId());
        assertEquals(1080,art.resolutionHeight());assertTrue(art.noGlow());assertEquals(0,art.benchmarkSeconds());
        var ordinary=Main.Options.parse(new String[]{});
        assertEquals("dead-air-yard",ordinary.arenaId());assertEquals(0,ordinary.resolutionHeight());assertFalse(ordinary.noGlow());
        assertEquals(720,Main.Options.parse(new String[]{"--dev","--resolution=720p"}).resolutionHeight());
        for(String other:new String[]{"--showcase","--smoke-seconds=40","--benchmark-seconds=60"})
            assertThrows(IllegalArgumentException.class,()->Main.Options.parse(new String[]{"--dev","--art-showcase",other}));
    }
    @Test void invalidArenaPathsAndUnsupportedResolutionsFailBeforeStartingGraphics() {
        for(String identity:new String[]{"","../arena","C:/arena","neon zero","NEON_ZERO"})
            assertThrows(IllegalArgumentException.class,()->Main.Options.parse(new String[]{"--dev","--arena="+identity}));
        for(String size:new String[]{"","480p","900p","2160p","1080"})
            assertThrows(IllegalArgumentException.class,()->Main.Options.parse(new String[]{"--dev","--resolution="+size}));
        assertThrows(IllegalArgumentException.class,()->Main.Options.parse(new String[]{"--dev","--showcase","--arena=construction_17"}));
    }
    @Test void vehicleShowcaseIsAnExclusiveDevOnlyModeUsingTheRosterArena() {
        assertThrows(IllegalArgumentException.class,()->Main.Options.parse(new String[]{"--vehicle-showcase"}));
        var options=Main.Options.parse(new String[]{"--dev","--vehicle-showcase","--resolution=720p"});
        assertTrue(options.vehicleShowcase());assertTrue(options.automated());assertEquals("dead-air-yard",options.arenaId());
        for(String incompatible:new String[]{"--showcase","--art-showcase","--benchmark-seconds=1","--smoke-seconds=1","--arena=construction_17"})
            assertThrows(IllegalArgumentException.class,()->Main.Options.parse(new String[]{"--dev","--vehicle-showcase",incompatible}));
    }
}

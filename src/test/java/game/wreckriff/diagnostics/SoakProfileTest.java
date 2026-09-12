package game.wreckriff.diagnostics;

import game.wreckriff.config.ProgressStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class SoakProfileTest {
    @TempDir Path directory;
    @Test void FixtureUnlocksCoverageWithoutInventingMeasuredWinsAndRealAttemptsUseTheCanonicalWriter() throws Exception {
        SoakProfile.prepare(directory);
        assertThrows(FileAlreadyExistsException.class,()->SoakProfile.prepare(directory));
        try(var progress=new ProgressStore(directory)) {
            assertEquals("",progress.warning());assertEquals(0,progress.snapshot().stats().completedMatches());
            assertTrue(progress.snapshot().records().isEmpty());
            for(String arena:ProgressStore.CAMPAIGN_ARENAS) {
                var attempt=progress.beginAttempt(arena,ProgressStore.Mode.BOSS_DUEL,false);
                assertTrue(progress.diagnostics().pendingSnapshotCount()<=1);
                assertTrue(progress.record(new ProgressStore.Result(attempt,ProgressStore.Outcome.DEFEAT,12,0,120,false)));
            }
            assertTrue(progress.flush(Duration.ofSeconds(5)));var writer=progress.diagnostics();
            assertEquals(writer.revision(),writer.persistedRevision());assertEquals(5,progress.snapshot().stats().losses());
        }
        try(var reloaded=new ProgressStore(directory)) {assertEquals(5,reloaded.snapshot().stats().completedMatches());}
    }
}

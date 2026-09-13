package game.wreckriff.diagnostics;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class LaunchReportTest {
    @TempDir Path directory;
    @Test void newCampaignAndLoadedCheckpointKeepDistinctResumeEvidence() throws Exception {
        var report=new LaunchReport(directory,false);report.started(1280,720,true,true);
        report.sessionStarted("construction_17","CAMPAIGN","rivet",false);
        report.sessionStarted("construction_17","CAMPAIGN","rivet",true);report.closed(true,true);
        var starts=JsonParser.parseString(Files.readString(directory.resolve("launch-summary.json"))).getAsJsonObject().getAsJsonArray("sessionStarts");
        assertFalse(starts.get(0).getAsJsonObject().get("resumedCheckpoint").getAsBoolean());
        assertTrue(starts.get(1).getAsJsonObject().get("resumedCheckpoint").getAsBoolean());
    }
    @Test void normalLaunchRecordsRealLifecycleWithoutGrantingAcceptance() throws Exception {
        var report=new LaunchReport(directory,true);report.started(1280,720,true,true);
        var starting=JsonParser.parseString(Files.readString(directory.resolve("launch-summary.json"))).getAsJsonObject();
        assertEquals("STARTED",starting.get("status").getAsString());assertFalse(starting.get("dev").getAsBoolean());
        report.frame(true,.02);report.frame(false,2);
        report.sessionStarted("construction_17","CAMPAIGN","spark",true);report.closed(true,true);
        var closed=JsonParser.parseString(Files.readString(directory.resolve("launch-summary.json"))).getAsJsonObject();
        assertEquals("CLOSED",closed.get("status").getAsString());assertEquals(1,closed.get("renderedFrames").getAsLong());
        assertEquals(2,closed.get("undrawableSeconds").getAsDouble());assertTrue(closed.get("initialCheckpointPresent").getAsBoolean());
        assertTrue(closed.getAsJsonArray("sessionStarts").get(0).getAsJsonObject().get("resumedCheckpoint").getAsBoolean());
        assertFalse(closed.has("FEEL_APPROVED"));assertFalse(closed.has("MVP_ACCEPTED"));
        assertFalse(Files.exists(directory.resolve("launch-summary.json.tmp")));
    }
    @Test void failedShutdownOrUnflushedProgressCannotLookLikeACleanClose() throws Exception {
        for(boolean clean:new boolean[]{false,true}) {
            var report=new LaunchReport(directory,false);report.started(1280,720,true,true);report.closed(clean,!clean);
            var result=JsonParser.parseString(Files.readString(directory.resolve("launch-summary.json"))).getAsJsonObject();
            assertEquals("FAILED",result.get("status").getAsString());
        }
    }
    @Test void errorSurvivesShutdownAndObservationsStayBounded() throws Exception {
        var report=new LaunchReport(directory,false);report.started(1280,720,true,true);
        for(int i=0;i<100;i++){report.error("failure "+i);report.sessionStarted("neon_zero","ARENA","rivet",false);}
        report.closed(true,true);
        var result=JsonParser.parseString(Files.readString(directory.resolve("launch-summary.json"))).getAsJsonObject();
        assertEquals("FAILED",result.get("status").getAsString());assertEquals(32,result.getAsJsonArray("errors").size());
        assertEquals(64,result.getAsJsonArray("sessionStarts").size());assertEquals(100,result.get("sessionStartCount").getAsInt());
    }
}

package game.wreckriff.diagnostics;

import game.wreckriff.arena.ArenaRegistry;
import game.wreckriff.config.SettingsStore;
import game.wreckriff.config.VehicleDefinition;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class UiReviewTest {
    @TempDir Path directory;
    private static final UiReview.Case PAGE=new UiReview.Case("main","main","new","fresh",List.of(new UiReview.Command(UiReview.Kind.ACTIVATE,"new")),"");
    private final class Host implements UiReview.Host {
        int commands,captures;int width=1280;String page="main";
        public void command(UiReview.Command command){commands++;}
        public UiReview.Observation observe(){return new UiReview.Observation("MENU",page,"new",0,width,720,1,true);}
        public String capture(String id){captures++;return "review-"+id+"-";}
    }
    @Test void twoDrawnFramesAndCompletedPngAreRequiredBeforeCaptureEvidence() throws Exception {
        var host=new Host();var review=new UiReview(directory,1280,720,1,List.of(PAGE));
        review.advance(host,0);assertEquals(1,host.commands);
        review.advance(host,.1);assertEquals(0,host.captures);
        review.drawnFrame();review.advance(host,.2);assertEquals(0,host.captures);
        review.drawnFrame();review.advance(host,.3);assertEquals(1,host.captures);assertFalse(review.complete());
        review.drawnFrame();review.advance(host,.4);assertFalse(review.complete());
        png("review-main-1.png",1280,720);review.advance(host,.5);
        assertTrue(review.complete());assertFalse(review.failed());assertEquals(2,review.evidence().getFirst().settledDrawFrames());
        assertEquals("CAPTURED",review.evidence().getFirst().status());
        String manifest=Files.readString(directory.resolve("ui-review-manifest.json"));
        assertTrue(manifest.contains("CAPTURES_COMPLETE_HUMAN_REVIEW_PENDING"));assertTrue(manifest.contains("NOT_GRANTED"));
    }
    @Test void unsupportedFramebufferFailsWithoutCaptureOrPass() throws Exception {
        var host=new Host();host.width=1279;var review=new UiReview(directory,1280,720,1,List.of(PAGE));
        review.advance(host,0);review.drawnFrame();review.drawnFrame();review.advance(host,1);
        assertTrue(review.failed());assertEquals(0,host.captures);assertEquals("FAIL",review.evidence().getFirst().status());
    }
    @Test void unexpectedNavigationDoesNotBecomeVisualEvidence() throws Exception {
        var host=new Host();host.page="error";var review=new UiReview(directory,1280,720,1,List.of(PAGE));
        review.advance(host,0);review.drawnFrame();review.drawnFrame();review.advance(host,1);
        assertTrue(review.failed());assertTrue(review.evidence().getFirst().reason().contains("Unexpected page/focus"));
    }
    @Test void captureDimensionsAndTimeoutFailClosed() throws Exception {
        png("bad.png",640,480);assertThrows(java.io.IOException.class,()->UiReview.verifyPng(directory.resolve("captures/bad.png"),1280,720));
        var host=new Host();var review=new UiReview(directory,1280,720,1,List.of(PAGE));
        review.advance(host,0);review.drawnFrame();review.drawnFrame();review.advance(host,1);review.drawnFrame();review.advance(host,21);
        assertTrue(review.failed());assertTrue(review.evidence().getFirst().reason().contains("20 seconds"));
    }
    @Test void pendingHardwareNeverRunsCommandsOrClaimsCapture() throws Exception {
        var host=new Host();var pending=new UiReview.Case("hardware","","","",List.of(),"Physical controller required");
        var review=new UiReview(directory,1280,720,1,List.of(pending));review.advance(host,0);
        assertTrue(review.complete());assertFalse(review.failed());assertEquals(0,host.commands);assertEquals(0,host.captures);
        assertEquals("PENDING",review.evidence().getFirst().status());
    }
    @Test void timedVideoRollbackWaitsForTheRealDeadlineAndRenderedFrames() throws Exception {
        var host=new Host();host.page="video-confirm";
        var timed=new UiReview.Case("timeout","main","new","actual video timeout",List.of(),"",11);
        var review=new UiReview(directory,1280,720,1,List.of(timed));review.advance(host,0);
        review.drawnFrame();review.drawnFrame();review.advance(host,10);assertFalse(review.failed());assertEquals(0,host.captures);
        host.page="main";review.advance(host,11);assertEquals(1,host.captures);
    }
    @Test void catalogueIsBoundedUniqueAndKeepsFixturesSeparateFromActions() {
        var cases=UiReview.catalogue();assertTrue(cases.size()<=UiReview.MAXIMUM_CASES);assertEquals(cases.size(),cases.stream().map(UiReview.Case::id).distinct().count());
        assertEquals(18,cases.stream().filter(c->c.id().startsWith("hud-")).count());
        assertEquals(6,cases.stream().filter(c->c.id().startsWith("fresh-maps")).count());
        assertEquals(6,cases.stream().filter(c->c.id().startsWith("unlocked-maps")).count());
        assertTrue(cases.stream().filter(c->c.id().endsWith("statistics-bottom")).allMatch(c->c.commands().getLast().value().endsWith("doomsday_arena")));
        assertTrue(cases.stream().anyMatch(c->c.id().equals("video-reverted")&&c.commands().getFirst().kind()==UiReview.Kind.ACTIVATE));
        assertTrue(cases.stream().anyMatch(c->c.id().equals("unlocked-main")&&c.fixture().contains("zero measured wins")));
    }
    @Test void diagnosticCompletionCannotTurnUiCapturesIntoGenericPass() {
        var diagnostic=new DiagnosticEvidence(false,240);diagnostic.put("mode","ui-review");
        assertEquals("FAIL",diagnostic.completionStatus(true));
        diagnostic.put("uiReviewCaptureStatus","CAPTURES_COMPLETE_HUMAN_REVIEW_PENDING");
        assertEquals("CAPTURES_COMPLETE_HUMAN_REVIEW_PENDING",diagnostic.completionStatus(true));
        diagnostic.error("Render failure");assertEquals("FAIL",diagnostic.completionStatus(true));
    }
    @Test void keyboardScrollTargetComesFromTheActualRemappableActionCatalogue() {
        var keys=SettingsStore.defaultKeys();
        assertFalse(keys.containsKey("Pause"),"ESC is fixed; it is not a remappable keyboard row");
        var bottom=UiReview.catalogue().stream().filter(c->c.id().equals("controls-keyboard-bottom")).findFirst().orElseThrow();
        assertEquals("binding:"+keys.lastEntry().getKey(),bottom.commands().getFirst().value());
        for(var command:UiReview.catalogue().stream().flatMap(c->c.commands().stream()).toList())
            if(command.value().startsWith("binding:"))assertTrue(keys.containsKey(command.value().substring(8)),command.value());
    }
    @Test void dynamicArenaRecordAndVehicleTargetsReferenceTheRealRepositoryCatalogues() {
        var arenas=ArenaRegistry.load();
        for(var command:UiReview.catalogue().stream().flatMap(c->c.commands().stream()).toList()) {
            String target=command.value();
            if(target.startsWith("arena:")) {
                String id=target.substring(6).replace(":boss","");var arena=arenas.definition(id);
                if(target.endsWith(":boss"))assertFalse(arena.bosses().isEmpty(),target);
            } else if(target.startsWith("record:"))assertNotNull(arenas.definition(target.substring(7)));
            else if(target.startsWith("vehicle:"))assertNotNull(VehicleDefinition.forId(target.substring(8)));
        }
    }
    private void png(String name,int width,int height)throws Exception {
        Files.createDirectories(directory.resolve("captures"));var data=ByteBuffer.allocate(33);
        data.putLong(0x89504e470d0a1a0aL).putInt(13).putInt(0x49484452).putInt(width).putInt(height);
        Files.write(directory.resolve("captures").resolve(name),data.array());
    }
}

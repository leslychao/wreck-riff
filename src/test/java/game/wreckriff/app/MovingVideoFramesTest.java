package game.wreckriff.app;

import com.jme3.app.Application;
import com.jme3.app.state.AbstractAppState;
import com.jme3.app.state.AppStateManager;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MovingVideoFramesTest {
    @Test void detachedAfterNthPostFrameIsCleanedBeforeAnotherFrameCanBeCaptured() {
        var manager=new AppStateManager(new com.jme3.app.LegacyApplication());var recorder=new Recorder();var frames=new GameApplication.MovingVideoFrames(3);
        manager.attach(recorder);
        assertFalse(frames.frameRendered(recorder.isInitialized()));assertEquals(0,frames.count());
        int encoded=0;
        // Pinned SimpleApplication: AppStateManager.update -> RenderManager.render/postFrame -> simpleRender.
        for(int frame=0;frame<4;frame++) {
            manager.update(1f/30);
            if(recorder.processorAttached)encoded++;
            if(frames.frameRendered(recorder.isInitialized()))manager.detach(recorder);
        }
        assertEquals(3,encoded);assertEquals(3,frames.count());assertEquals(1,recorder.cleanups);
        assertFalse(recorder.processorAttached);assertFalse(frames.frameRendered(true));assertEquals(3,frames.count());
    }
    @Test void frameCompletionDoesNotDependOnSlowEncodingWallClock() {
        var frames=new GameApplication.MovingVideoFrames(900);
        // At ten encoded frames per real second, sixty wall-clock seconds are still only 600 frames.
        for(int frame=0;frame<899;frame++)assertFalse(frames.frameRendered(true));
        assertTrue(frames.frameRendered(true));assertEquals(900,frames.count());
        assertThrows(IllegalArgumentException.class,()->new GameApplication.MovingVideoFrames(0));
    }
    private static final class Recorder extends AbstractAppState {
        boolean processorAttached;int cleanups;
        @Override public void initialize(AppStateManager manager,Application app){super.initialize(manager,app);processorAttached=true;}
        @Override public void cleanup(){processorAttached=false;cleanups++;super.cleanup();}
    }
}

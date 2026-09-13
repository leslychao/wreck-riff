package game.wreckriff.app;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import static org.junit.jupiter.api.Assertions.*;

class MatchLoadingTest {
    @Test void physicsWarmupCannotPublishReadyBeforeThePreparedSceneHasBeenRendered() {
        int[] ready={0};var loading=new MatchLoading(List.of(),()->{},()->{},()->ready[0]++);
        for(int frame=0;frame<30;frame++)loading.advance();
        assertEquals(0,ready[0],"Completing physics preparation is not a completed drawable scene frame");
        assertFalse(loading.complete());
        loading.frameRendered(false);loading.advance();assertEquals(0,ready[0]);
        loading.frameRendered(true);assertEquals(0,ready[0],"Publishing waits until the rendered loading frame has ended");
        loading.advance();assertEquals(1,ready[0]);assertTrue(loading.complete());
    }
    @Test void yieldsBetweenStagesAndBoundsWarmupWithoutRepeatingWork() {
        List<String> calls=new ArrayList<>();int[] steps={0};
        var loading=new MatchLoading(List.of(new MatchLoading.Stage("world",()->calls.add("world")),
                new MatchLoading.Stage("vehicles",()->calls.add("vehicles"))),()->steps[0]++,()->calls.add("frame"),()->calls.add("ready"));
        loading.advance();assertEquals(List.of("world"),calls);assertEquals(0,steps[0]);
        loading.advance();assertEquals(List.of("world","vehicles"),calls);assertEquals(0,steps[0]);
        int previous=0;
        while(!loading.awaitingFrame()) {loading.advance();assertTrue(steps[0]-previous<=12);previous=steps[0];}
        for(int i=0;i<5;i++)loading.advance();
        assertEquals(360,steps[0]);assertEquals(List.of("world","vehicles","frame"),calls);
        loading.frameRendered(true);loading.advance();
        assertEquals(List.of("world","vehicles","frame","ready"),calls);assertEquals(1,loading.fraction());
        loading.advance();assertEquals(360,steps[0]);assertEquals(1,Collections.frequency(calls,"ready"));
    }
    @Test void failureCannotRunLaterStagesOrPublishReady() {
        List<String> calls=new ArrayList<>();
        var loading=new MatchLoading(List.of(new MatchLoading.Stage("bad",()->{throw new IllegalStateException("bad asset");}),
                new MatchLoading.Stage("later",()->calls.add("later"))),()->calls.add("warmup"),()->calls.add("frame"),()->calls.add("ready"));
        assertThrows(IllegalStateException.class,loading::advance);
        assertThrows(IllegalStateException.class,loading::advance);
        assertTrue(calls.isEmpty());assertFalse(loading.complete());
    }
    @Test void aRenderBeforePreparationAndAFramePreparationFailureCannotPublishReady() {
        int[] prepared={0},ready={0};
        var loading=new MatchLoading(List.of(),()->{},()->prepared[0]++,()->ready[0]++);
        loading.frameRendered(true);
        for(int i=0;i<31;i++)loading.advance();
        assertEquals(1,prepared[0]);assertEquals(0,ready[0]);assertFalse(loading.complete());
        var broken=new MatchLoading(List.of(),()->{},()->{throw new IllegalStateException("scene preparation failed");},()->ready[0]++);
        for(int i=0;i<29;i++)broken.advance();
        assertThrows(IllegalStateException.class,broken::advance);broken.frameRendered(true);
        assertThrows(IllegalStateException.class,broken::advance);assertEquals(0,ready[0]);assertFalse(broken.awaitingFrame());
    }
    @Test void textureStepsYieldOneAtATimeBeforeEnvironmentConstruction() {
        int[] decoded={0},built={0};var stages=new ArrayList<MatchLoading.Stage>();
        for(int i=0;i<16;i++)stages.add(new MatchLoading.Stage("texture",()->decoded[0]++));
        stages.add(new MatchLoading.Stage("environment",()->{assertEquals(16,decoded[0]);built[0]++;}));
        try(var loading=new MatchLoading(stages,()->{},()->{},()->{})) {
            for(int i=1;i<=16;i++){loading.advance();assertEquals(i,decoded[0]);assertEquals(0,built[0]);}
            loading.advance();assertEquals(1,built[0]);
        }
    }
    @Test void completedStagesDoNotRetainTheirCapturedResources() throws Exception {
        var fixture=retentionFixture(false);fixture.loading.advance();
        assertCollected(fixture.completed);assertNotNull(fixture.pending.get());assertNotNull(fixture.callbacks.get());
        fixture.loading.advance();
        for(int i=0;i<30;i++)fixture.loading.advance();
        fixture.loading.frameRendered(true);fixture.loading.advance();
        assertCollected(fixture.pending);assertCollected(fixture.callbacks);
        Reference.reachabilityFence(fixture.loading);
    }
    @Test void failureAndCancellationReleasePendingStagesAndLifecycleCallbacks() throws Exception {
        var failed=retentionFixture(true);assertThrows(IllegalStateException.class,failed.loading::advance);
        assertCollected(failed.completed);assertCollected(failed.pending);assertCollected(failed.callbacks);
        var cancelled=retentionFixture(false);cancelled.loading.close();
        assertCollected(cancelled.completed);assertCollected(cancelled.pending);assertCollected(cancelled.callbacks);
        assertThrows(IllegalStateException.class,cancelled.loading::advance);
        Reference.reachabilityFence(failed.loading);Reference.reachabilityFence(cancelled.loading);
    }
    private record RetentionFixture(MatchLoading loading,WeakReference<Object> completed,WeakReference<Object> pending,WeakReference<Object> callbacks) { }
    private static RetentionFixture retentionFixture(boolean fail) {
        Object completed=new Object(),pending=new Object(),callbacks=new Object();
        var loading=new MatchLoading(List.of(new MatchLoading.Stage("first",()->{
            completed.hashCode();if(fail)throw new IllegalStateException("decode failed");
        }),new MatchLoading.Stage("second",pending::hashCode)),callbacks::hashCode,callbacks::hashCode,callbacks::hashCode);
        return new RetentionFixture(loading,new WeakReference<>(completed),new WeakReference<>(pending),new WeakReference<>(callbacks));
    }
    private static void assertCollected(WeakReference<?> reference) throws Exception {
        for(int i=0;i<40&&reference.get()!=null;i++){System.gc();Thread.sleep(10);}
        assertNull(reference.get(),"The live loading object must not retain a completed, failed or cancelled callback");
    }
}

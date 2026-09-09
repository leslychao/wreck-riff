package game.wreckriff.app;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MatchLoadingTest {
    @Test void yieldsBetweenStagesAndBoundsWarmupWithoutRepeatingWork() {
        List<String> calls=new ArrayList<>();int[] steps={0};
        var loading=new MatchLoading(List.of(new MatchLoading.Stage("world",()->calls.add("world")),
                new MatchLoading.Stage("vehicles",()->calls.add("vehicles"))),()->steps[0]++,()->calls.add("ready"));
        loading.advance();assertEquals(List.of("world"),calls);assertEquals(0,steps[0]);
        loading.advance();assertEquals(List.of("world","vehicles"),calls);assertEquals(0,steps[0]);
        int previous=0;
        while(!loading.complete()) {loading.advance();assertTrue(steps[0]-previous<=12);previous=steps[0];}
        assertEquals(360,steps[0]);assertEquals(List.of("world","vehicles","ready"),calls);
        loading.advance();assertEquals(360,steps[0]);assertEquals(1,Collections.frequency(calls,"ready"));
    }
    @Test void failureCannotRunLaterStagesOrPublishReady() {
        List<String> calls=new ArrayList<>();
        var loading=new MatchLoading(List.of(new MatchLoading.Stage("bad",()->{throw new IllegalStateException("bad asset");}),
                new MatchLoading.Stage("later",()->calls.add("later"))),()->calls.add("warmup"),()->calls.add("ready"));
        assertThrows(IllegalStateException.class,loading::advance);
        assertThrows(IllegalStateException.class,loading::advance);
        assertTrue(calls.isEmpty());assertFalse(loading.complete());
    }
}

package game.wreckriff.app;

import java.util.*;

/** Render-thread preparation; each frame executes one stage or at most twelve native warmup steps. */
final class MatchLoading {
    record Stage(String title,Runnable action) {
        Stage {Objects.requireNonNull(title);Objects.requireNonNull(action);}
    }
    private final List<Stage> stages;
    private final Runnable physicsStep,ready;
    private int stage,warmup;
    private boolean complete,failed;
    MatchLoading(List<Stage> stages,Runnable physicsStep,Runnable ready) {
        this.stages=List.copyOf(stages);this.physicsStep=Objects.requireNonNull(physicsStep);this.ready=Objects.requireNonNull(ready);
    }
    String title() {return stage<stages.size()?stages.get(stage).title():"Подготовка подвески";}
    float fraction() {return (stage+warmup/360f)/(stages.size()+1);}
    boolean complete() {return complete;}
    void advance() {
        if(failed)throw new IllegalStateException("Loading has failed");
        if(complete)return;
        try {
            if(stage<stages.size()) {stages.get(stage++).action().run();return;}
            for(int i=0;i<12&&warmup<360;i++,warmup++)physicsStep.run();
            if(warmup==360) {ready.run();complete=true;}
        } catch(RuntimeException|Error failure) {failed=true;throw failure;}
    }
}

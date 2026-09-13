package game.wreckriff.app;

import java.util.*;

/** Render-thread preparation; simulation starts only after the prepared scene has a drawable frame. */
final class MatchLoading implements AutoCloseable {
    record Stage(String title,Runnable action) {
        Stage {Objects.requireNonNull(title);Objects.requireNonNull(action);}
    }
    private final List<Stage> stages;
    private Runnable physicsStep,prepareFrame,ready;
    private int stage,warmup;
    private boolean prepared,rendered,complete,failed,closed;
    MatchLoading(List<Stage> stages,Runnable physicsStep,Runnable prepareFrame,Runnable ready) {
        this.stages=new ArrayList<>(List.copyOf(stages));this.physicsStep=Objects.requireNonNull(physicsStep);
        this.prepareFrame=Objects.requireNonNull(prepareFrame);this.ready=Objects.requireNonNull(ready);
    }
    String title() {return stage<stages.size()?stages.get(stage).title():prepared?"Подготовка изображения":"Подготовка подвески";}
    float fraction() {return (stage+warmup/360f+(rendered?1:0))/(stages.size()+2);}
    boolean complete() {return complete;}
    boolean awaitingFrame() {return prepared&&!complete&&!failed&&!closed;}
    void frameRendered(boolean drawable) {if(awaitingFrame()&&drawable)rendered=true;}
    void advance() {
        if(failed)throw new IllegalStateException("Loading has failed");
        if(closed)throw new IllegalStateException("Loading is closed");
        if(complete)return;
        try {
            if(prepared) {if(rendered){ready.run();complete=true;releaseCallbacks();}return;}
            if(stage<stages.size()) {Stage next=stages.set(stage++,null);next.action().run();return;}
            for(int i=0;i<12&&warmup<360;i++,warmup++)physicsStep.run();
            if(warmup==360) {physicsStep=null;prepared=true;Runnable prepare=prepareFrame;prepareFrame=null;prepare.run();}
        } catch(RuntimeException|Error failure) {failed=true;releaseCallbacks();throw failure;}
    }
    private void releaseCallbacks() {Collections.fill(stages,null);physicsStep=null;prepareFrame=null;ready=null;}
    @Override public void close() {closed=true;releaseCallbacks();}
}

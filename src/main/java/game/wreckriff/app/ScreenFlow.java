package game.wreckriff.app;

/** Sole screen-state owner; overlays remember the screen they return to. */
public final class ScreenFlow {
    public enum Screen { BOOT,MENU,MAPS,VEHICLES,STATISTICS,LOADING,RUNNING,PAUSED,RESULTS,SETTINGS,CONTROLS,CREDITS,CONFIRM,ERROR }
    private Screen screen=Screen.BOOT;
    private String pauseReason="";
    private final java.util.Deque<Screen> parents=new java.util.ArrayDeque<>();
    private Runnable changed=()->{};
    public void onChanged(Runnable callback) { changed=callback; }
    public Screen screen() { return screen; }
    public String pauseReason() {return pauseReason;}
    private void root(Screen next) {parents.clear();pauseReason="";go(next);}
    public void menu() {root(Screen.MENU);}
    public void maps() {root(Screen.MAPS);}
    public void statistics() {root(Screen.STATISTICS);}
    public void loading() {root(Screen.LOADING);}
    public void running() {root(Screen.RUNNING);}
    public void results() {root(Screen.RESULTS);}
    public void error() {root(Screen.ERROR);}
    public void pause() {pause("");}
    public void pause(String reason) {
        if(screen==Screen.RUNNING) {pauseReason=java.util.Objects.requireNonNull(reason);go(Screen.PAUSED);}
    }
    public void resume() { if(screen==Screen.PAUSED) go(Screen.RUNNING); }
    public void open(Screen overlay) {
        if(overlay!=Screen.SETTINGS && overlay!=Screen.CONTROLS && overlay!=Screen.CREDITS && overlay!=Screen.CONFIRM && overlay!=Screen.VEHICLES) throw new IllegalArgumentException("Not an overlay");
        parents.push(screen);go(overlay);
    }
    public void back() {go(parents.isEmpty()?Screen.MENU:parents.pop());}
    private void go(Screen next) {if(next==Screen.RUNNING||next==Screen.MENU)pauseReason="";screen=next;changed.run();}
}

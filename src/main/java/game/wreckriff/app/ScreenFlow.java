package game.wreckriff.app;

/** Sole screen-state owner; overlays remember the screen they return to. */
public final class ScreenFlow {
    public enum Screen { BOOT,MENU,MAPS,STATISTICS,LOADING,RUNNING,PAUSED,RESULTS,SETTINGS,CONTROLS,CREDITS,CONFIRM,ERROR }
    private Screen screen=Screen.BOOT;
    private Screen returnScreen=Screen.MENU;
    private Runnable changed=()->{};
    public void onChanged(Runnable callback) { changed=callback; }
    public Screen screen() { return screen; }
    public void menu() { go(Screen.MENU); }
    public void maps() { go(Screen.MAPS); }
    public void statistics() { go(Screen.STATISTICS); }
    public void loading() { go(Screen.LOADING); }
    public void running() { go(Screen.RUNNING); }
    public void results() { go(Screen.RESULTS); }
    public void error() { go(Screen.ERROR); }
    public void pause() {
        if(screen==Screen.RUNNING) go(Screen.PAUSED);
    }
    public void resume() { if(screen==Screen.PAUSED) go(Screen.RUNNING); }
    public void open(Screen overlay) {
        if(overlay!=Screen.SETTINGS && overlay!=Screen.CONTROLS && overlay!=Screen.CREDITS && overlay!=Screen.CONFIRM) throw new IllegalArgumentException("Not an overlay");
        returnScreen=screen; go(overlay);
    }
    public void back() { go(returnScreen); }
    private void go(Screen next) { screen=next; changed.run(); }
}

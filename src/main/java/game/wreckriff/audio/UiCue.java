package game.wreckriff.audio;

/** Feedback for a completed menu interaction; gameplay notifications use their own cues. */
public enum UiCue {
    NAVIGATE("ui-nav"), CHANGE("ui-change"), CONFIRM("ui-confirm"), BACK("ui-back"), VEHICLE("ui-vehicle");

    private final String asset;
    UiCue(String asset) { this.asset=asset; }
    public String asset() { return asset; }
}

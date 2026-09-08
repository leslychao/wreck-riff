package game.wreckriff.config;

public record MatchRules(int durationSeconds, int countdownSeconds, double maxFrameSeconds,
        int maxSteps, float radarRange, int maxLogFiles, int logFileBytes) {
    public MatchRules {
        if (durationSeconds<=0 || countdownSeconds<0 || maxFrameSeconds<=0 || maxSteps<1
                || radarRange<=0 || maxLogFiles<1 || logFileBytes<1024) throw new IllegalArgumentException("Invalid match rules");
    }
    public static MatchRules load() { return Configs.load("match",MatchRules.class); }
}

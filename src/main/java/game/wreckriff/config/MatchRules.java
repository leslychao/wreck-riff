package game.wreckriff.config;

public record MatchRules(int durationSeconds, double maxFrameSeconds,
        int maxSteps, float radarRange, int maxLogFiles, int logFileBytes) {
    public MatchRules {
        if (durationSeconds<=0 || maxFrameSeconds<=0 || maxSteps<1
                || maxFrameSeconds>0.1 || maxSteps>12 || maxLogFiles>5 || logFileBytes>2097152
                || radarRange<=0 || maxLogFiles<1 || logFileBytes<1024) throw new IllegalArgumentException("Invalid match rules");
    }
    public static MatchRules load() { return Configs.load("match",MatchRules.class); }
}

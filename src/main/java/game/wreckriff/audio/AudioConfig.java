package game.wreckriff.audio;

import game.wreckriff.config.Configs;
import java.util.*;

public record AudioConfig(String musicAsset, float musicGain, float engineGain, float weaponsGain,
        float threatsGain, float interfaceGain, float masterHeadroom, int sourceLimit,
        float referenceDistance, float maximumDistance, float enginePitchMin, float enginePitchMax,
        float engineSmoothingSeconds, float musicDuckGain, float musicDuckSeconds, List<String> effects) {
    public static AudioConfig load() { return Configs.load("audio", AudioConfig.class); }
    /** Numeric suffixes are recorded takes of one cue, selected without immediate repetition. */
    public Map<String,List<String>> cueBanks() {
        Map<String,List<String>> banks = new LinkedHashMap<>();
        for (String effect : effects) banks.computeIfAbsent(effect.replaceFirst("-[1-9][0-9]*$", ""),
                ignored -> new ArrayList<>()).add(effect);
        banks.replaceAll((key, values) -> List.copyOf(values));
        return Collections.unmodifiableMap(banks);
    }
    public AudioConfig {
        if (musicAsset == null || !musicAsset.startsWith("audio/") || !musicAsset.endsWith(".wav")
                || musicAsset.contains("..")) throw new IllegalArgumentException("Invalid music asset path");
        for (float gain : new float[]{musicGain,engineGain,weaponsGain,threatsGain,interfaceGain,masterHeadroom,musicDuckGain})
            if (!Float.isFinite(gain) || gain < 0 || gain > 1) throw new IllegalArgumentException("Audio gain must be 0..1");
        if (sourceLimit < 20 || sourceLimit > 32 || referenceDistance <= 0 || maximumDistance <= referenceDistance
                || enginePitchMin < .5 || enginePitchMax > 2 || enginePitchMin > enginePitchMax
                || engineSmoothingSeconds <= 0 || musicDuckSeconds <= 0)
            throw new IllegalArgumentException("Invalid audio limits");
        effects = List.copyOf(effects);
        if (new HashSet<>(effects).size() != effects.size() || effects.size() < 14)
            throw new IllegalArgumentException("Distinct audio effects required");
        for (String effect : effects) if (!effect.matches("[a-z][a-z0-9-]*"))
            throw new IllegalArgumentException("Invalid effect asset id");
    }
}

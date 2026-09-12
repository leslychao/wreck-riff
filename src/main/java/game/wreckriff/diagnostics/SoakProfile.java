package game.wreckriff.diagnostics;

import com.google.gson.GsonBuilder;
import game.wreckriff.config.ProgressStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Explicit unlock fixture in a fresh isolated diagnostic profile; it supplies no measured results. */
public final class SoakProfile {
    private SoakProfile() {}
    public static void prepare(Path directory) throws IOException {
        var all=Set.copyOf(ProgressStore.CAMPAIGN_ARENAS);
        var campaign=new ProgressStore.Campaign(null,all,all,null);
        var initial=new ProgressStore.Snapshot(ProgressStore.SCHEMA_VERSION,0,0,
                new ProgressStore.Stats(0,0,0,0,0,0),campaign,null,Map.of());
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("stats.json"),new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(initial),
                StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
    }
}

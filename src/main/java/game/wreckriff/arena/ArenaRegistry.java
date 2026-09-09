package game.wreckriff.arena;

import game.wreckriff.config.Configs;
import java.util.*;

/** Ordered local resource catalogue; identifiers never become file-system paths. */
public final class ArenaRegistry {
    public record Entry(String id,String resourceKey,boolean campaign) {
        public Entry {
            if(id==null||!id.matches("[a-z][a-z0-9_-]*")||resourceKey==null||!resourceKey.matches("[a-z][a-z0-9-]*"))
                throw new IllegalArgumentException("Invalid arena registry entry");
        }
    }
    public record Catalogue(int schemaVersion,List<Entry> entries) {
        public Catalogue {
            if(schemaVersion!=1)throw new IllegalArgumentException("Invalid arena registry schema");
            entries=List.copyOf(entries);
            if(entries.isEmpty()||entries.stream().map(Entry::id).distinct().count()!=entries.size()
                    ||entries.stream().map(Entry::resourceKey).distinct().count()!=entries.size())
                throw new IllegalArgumentException("Empty/duplicate arena registry");
        }
    }
    private final List<Entry> entries;
    private final Map<String,ArenaDefinition> definitions;
    public ArenaRegistry(Catalogue catalogue) {
        entries=catalogue.entries();Map<String,ArenaDefinition> loaded=new LinkedHashMap<>();
        for(var entry:entries) {
            var arena=Configs.load(entry.resourceKey(),ArenaDefinition.class);
            if(!arena.id().equals(entry.id()))throw new IllegalArgumentException("Arena registry identity mismatch: "+entry.id());
            if(entry.campaign()&&(arena.launchPads().size()!=2||arena.drops().size()!=1||arena.secrets().size()!=1
                    ||arena.pickups().size()!=12||arena.bosses().size()!=1||arena.metadata().durationSeconds()!=0))
                throw new IllegalArgumentException("Incomplete campaign arena: "+entry.id());
            new NavGraph(arena);loaded.put(entry.id(),arena);
        }
        definitions=Collections.unmodifiableMap(loaded);
    }
    public static ArenaRegistry load() { return new ArenaRegistry(Configs.load("arenas",Catalogue.class)); }
    public List<Entry> entries() { return entries; }
    public List<String> campaignIds() { return entries.stream().filter(Entry::campaign).map(Entry::id).toList(); }
    public ArenaDefinition definition(String id) {
        var result=definitions.get(id);if(result==null)throw new IllegalArgumentException("Unknown arena: "+id);return result;
    }
}

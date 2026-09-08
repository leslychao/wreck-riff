package game.wreckriff.arena;

import com.jme3.math.Vector3f;
import game.wreckriff.config.Configs;
import java.util.*;

/** Authoritative arena geometry, surface coordinates and navigation data. */
public record ArenaDefinition(int schemaVersion, String id, Bounds bounds,
        List<BoxPart> boxes, List<Ramp> ramps, List<Spawn> spawns,
        List<Pickup> pickups, Hazard hazard, List<NavNode> nodes, List<NavEdge> edges) {
    public record Vec3(float x, float y, float z) {
        public Vec3 {
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))
                throw new IllegalArgumentException("Non-finite arena coordinate");
        }
        public Vector3f vector() { return new Vector3f(x,y,z); }
    }
    public record Bounds(float minX, float maxX, float minZ, float maxZ, float recoveryY) {
        public boolean contains(Vector3f point) {
            return point.x >= minX && point.x <= maxX && point.z >= minZ && point.z <= maxZ;
        }
    }
    public record BoxPart(String id, Vec3 center, Vec3 size, String material, boolean collision) {}
    public record Ramp(String id, float minX, float maxX, float minZ, float maxZ,
                       float startY, float endY, String material) {}
    /** Position is on the surface, not the center of a vehicle body. */
    public record Spawn(int id, Vec3 position, float yawDegrees) {}
    public enum PickupType { REPAIR, HOMING_AMMO, POWER_AMMO, TURBO_CELL }
    public record Pickup(String id, PickupType type, Vec3 position, int respawnTicks) {}
    public record Hazard(float minX, float maxX, float minZ, float maxZ,
                         float minY, float maxY, int offTicks, int warningTicks,
                         int activeTicks, int damageIntervalTicks, float damage) {
        public boolean contains(Vector3f point) {
            return point.x >= minX && point.x <= maxX && point.z >= minZ && point.z <= maxZ
                    && point.y >= minY && point.y <= maxY;
        }
        public int periodTicks() { return offTicks + warningTicks + activeTicks; }
    }
    public record NavNode(int id, Vec3 position) {}
    public record NavEdge(int from, int to, float width) {}

    public ArenaDefinition {
        if (schemaVersion != 1 || id == null || id.isBlank()) throw new IllegalArgumentException("Invalid arena identity");
        Objects.requireNonNull(bounds); Objects.requireNonNull(hazard);
        boxes=List.copyOf(boxes); ramps=List.copyOf(ramps); spawns=List.copyOf(spawns);
        pickups=List.copyOf(pickups); nodes=List.copyOf(nodes); edges=List.copyOf(edges);
        if (!(bounds.minX < bounds.maxX && bounds.minZ < bounds.maxZ)) throw new IllegalArgumentException("Invalid bounds");
        Set<String> objectIds=new HashSet<>();
        for (BoxPart box:boxes) {
            if (!objectIds.add(box.id) || box.size.x<=0 || box.size.y<=0 || box.size.z<=0)
                throw new IllegalArgumentException("Invalid/duplicate arena box " + box.id);
        }
        for (Ramp ramp:ramps) if (!objectIds.add(ramp.id) || ramp.minX>=ramp.maxX || ramp.minZ>=ramp.maxZ)
            throw new IllegalArgumentException("Invalid/duplicate ramp " + ramp.id);
        if (spawns.size()!=5) throw new IllegalArgumentException("Arena needs exactly five spawns");
        Set<Integer> spawnIds=new HashSet<>();
        for (Spawn spawn:spawns) if (!spawnIds.add(spawn.id) || !bounds.contains(spawn.position.vector()))
            throw new IllegalArgumentException("Invalid spawn " + spawn.id);
        Set<String> pickupIds=new HashSet<>();
        for (Pickup pickup:pickups) if (!pickupIds.add(pickup.id) || pickup.respawnTicks<=0
                || !bounds.contains(pickup.position.vector())) throw new IllegalArgumentException("Invalid pickup " + pickup.id);
        if (hazard.offTicks<=0 || hazard.warningTicks<=0 || hazard.activeTicks<=0 || hazard.damageIntervalTicks<=0
                || hazard.damage<=0 || hazard.minX>=hazard.maxX || hazard.minZ>=hazard.maxZ || hazard.minY>=hazard.maxY)
            throw new IllegalArgumentException("Invalid hazard");
        if (nodes.isEmpty() || nodes.size()>48) throw new IllegalArgumentException("Navigation needs 1..48 nodes");
        Set<Integer> nodeIds=new HashSet<>();
        for (NavNode node:nodes) if (!nodeIds.add(node.id) || !bounds.contains(node.position.vector()))
            throw new IllegalArgumentException("Invalid navigation node " + node.id);
        Set<String> edgeIds=new HashSet<>();
        for (NavEdge edge:edges) {
            String key=Math.min(edge.from,edge.to)+":"+Math.max(edge.from,edge.to);
            if (edge.from==edge.to || edge.width<3 || !nodeIds.contains(edge.from) || !nodeIds.contains(edge.to)
                    || !edgeIds.add(key)) throw new IllegalArgumentException("Invalid navigation edge " + key);
        }
    }
    public static ArenaDefinition load() { return Configs.load("arena",ArenaDefinition.class); }
    public List<Spawn> shuffledSpawns(long seed) {
        List<Spawn> result=new ArrayList<>(spawns);
        Collections.shuffle(result,new Random(seed));
        return List.copyOf(result);
    }
}

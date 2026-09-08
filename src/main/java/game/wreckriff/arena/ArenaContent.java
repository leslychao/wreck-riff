package game.wreckriff.arena;

import com.jme3.bullet.collision.shapes.CollisionShape;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import java.util.List;

/** Native shapes are created here, but the single PhysicsWorld owns rigid bodies. */
public record ArenaContent(Node visual, List<StaticBody> bodies,
        List<ArenaDefinition.Spawn> spawns, List<ArenaDefinition.Pickup> pickups, NavGraph graph) {
    public record StaticBody(String id, CollisionShape shape, Vector3f position, Quaternion rotation) {}
    public ArenaContent {
        bodies=List.copyOf(bodies); spawns=List.copyOf(spawns); pickups=List.copyOf(pickups);
    }
}

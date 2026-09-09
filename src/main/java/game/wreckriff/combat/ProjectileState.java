package game.wreckriff.combat;

import com.jme3.math.Vector3f;

/** Session-owned logical rocket. Presentation gets defensive pose copies. */
public final class ProjectileState {
    private final long id;
    private final int ownerId;
    private final String kind;
    final Vector3f position;
    final Vector3f previousPosition;
    final Vector3f direction;
    final Vector3f velocity=new Vector3f();
    int remainingTicks;
    int targetId;
    int occludedTicks;
    boolean exploded;

    ProjectileState(long id, int ownerId, String kind, Vector3f position, Vector3f direction,
            int remainingTicks, int targetId) {
        this.id = id;
        this.ownerId = ownerId;
        this.kind = kind;
        this.position = position.clone();
        this.previousPosition = position.clone();
        this.direction = direction.normalize();
        this.remainingTicks = remainingTicks;
        this.targetId = targetId;
    }

    public long id() { return id; }
    public int ownerId() { return ownerId; }
    public String kind() { return kind; }
    public Vector3f position() { return position.clone(); }
    public Vector3f previousPosition() { return previousPosition.clone(); }
    public Vector3f direction() { return direction.clone(); }
    public int remainingTicks() { return remainingTicks; }
    public int targetId() { return targetId; }
}

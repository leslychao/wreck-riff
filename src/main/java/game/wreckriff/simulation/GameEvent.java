package game.wreckriff.simulation;

import com.jme3.math.Vector3f;

public record GameEvent(Type type, long eventId, int subjectId, int sourceId,
        Vector3f position, String kind, float value) {
    public enum Type { SHOT, EXPLOSION, DAMAGE, DESTROYED, PICKUP, MATCH_FINISHED, PULSE, EMPTY,
        FREEZE, STUN, SHIELD, CONTROL_ENDED, MINE_PLACED, FIRE_STARTED, FIRE_ENDED }
    public GameEvent { position = position == null ? new Vector3f() : position.clone(); }
}

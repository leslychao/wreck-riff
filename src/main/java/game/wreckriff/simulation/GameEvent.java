package game.wreckriff.simulation;

import com.jme3.math.Vector3f;

public record GameEvent(Type type, long eventId, int subjectId, int sourceId,
        Vector3f position, String kind, float value, Vector3f origin, Vector3f normal) {
    public enum Type { SHOT, IMPACT, EXPLOSION, RAM, DAMAGE, DESTROYED, PICKUP, MATCH_FINISHED, EMPTY,
        FREEZE, SHIELD, SHIELD_HIT, SHIELD_ENDED, CONTROL_ENDED, MINE_PLACED, FIRE_STARTED, FIRE_ENDED }
    public GameEvent {
        position = copy(position); origin = copy(origin); normal = copy(normal);
    }
    public GameEvent(Type type,long eventId,int subjectId,int sourceId,Vector3f position,String kind,float value) {
        this(type,eventId,subjectId,sourceId,position,kind,value,Vector3f.ZERO,Vector3f.ZERO);
    }
    @Override public Vector3f position() { return position.clone(); }
    @Override public Vector3f origin() { return origin.clone(); }
    @Override public Vector3f normal() { return normal.clone(); }
    /** Cosmetic bullet travel shared by the tracer, contact flare and contact sound. Damage stays hitscan. */
    public float cosmeticImpactDelaySeconds() {
        return "machine-gun".equalsIgnoreCase(kind)
                && (type==Type.SHOT || type==Type.IMPACT || type==Type.SHIELD_HIT)
                ? origin.distance(position)/180f : 0;
    }
    private static Vector3f copy(Vector3f value) { return value==null?new Vector3f():value.clone(); }
}

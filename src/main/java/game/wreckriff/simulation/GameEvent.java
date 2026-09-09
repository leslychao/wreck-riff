package game.wreckriff.simulation;

import com.jme3.math.Vector3f;
import java.util.UUID;
import java.util.Objects;

public record GameEvent(Type type, long eventId, int subjectId, int sourceId,
        Vector3f position, String kind, float value, Vector3f origin, Vector3f normal,UUID sessionId,String objectId) {
    public enum Type { SHOT, IMPACT, EXPLOSION, RAM, DAMAGE, DESTROYED, PICKUP, MATCH_FINISHED, EMPTY,
        FREEZE, SHIELD, SHIELD_HIT, SHIELD_ENDED, CONTROL_ENDED, MINE_PLACED, FIRE_STARTED, FIRE_ENDED,
        LAUNCH_COMPRESS, LAUNCHED, LANDED, LAUNCH_REJECTED,
        SPECIAL_STARTED, SPECIAL_HIT, SPECIAL_ENDED, GRAB_STARTED, BOMB_PLACED }
    public GameEvent {
        position = copy(position); origin = copy(origin); normal = copy(normal);
    }
    public GameEvent(Type type,long eventId,int subjectId,int sourceId,Vector3f position,String kind,float value,
                     Vector3f origin,Vector3f normal,UUID sessionId) {
        this(type,eventId,subjectId,sourceId,position,kind,value,origin,normal,sessionId,null);
    }
    public GameEvent(Type type,long eventId,int subjectId,int sourceId,Vector3f position,String kind,float value) {
        this(type,eventId,subjectId,sourceId,position,kind,value,Vector3f.ZERO,Vector3f.ZERO,null);
    }
    public GameEvent(Type type,long eventId,int subjectId,int sourceId,Vector3f position,String kind,float value,Vector3f origin,Vector3f normal) {
        this(type,eventId,subjectId,sourceId,position,kind,value,origin,normal,null);
    }
    public GameEvent inSession(UUID id) {
        Objects.requireNonNull(id);
        if(sessionId!=null&&!sessionId.equals(id))throw new IllegalArgumentException("Event belongs to another session");
        return sessionId!=null?this:new GameEvent(type,eventId,subjectId,sourceId,position,kind,value,origin,normal,id,objectId);
    }
    public GameEvent forObject(String id) {
        if(id==null||id.isBlank())throw new IllegalArgumentException("Missing object id");
        return new GameEvent(type,eventId,subjectId,sourceId,position,kind,value,origin,normal,sessionId,id);
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

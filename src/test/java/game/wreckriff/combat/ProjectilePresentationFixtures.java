package game.wreckriff.combat;

import com.jme3.math.Vector3f;

/** Creates authoritative snapshot inputs without exposing test mutation APIs in production. */
public final class ProjectilePresentationFixtures {
    private ProjectilePresentationFixtures() { }
    public static ProjectileState state(long id,String kind,Vector3f position,Vector3f direction) {
        return new ProjectileState(id,0,kind,position,direction,120,-1);
    }
}

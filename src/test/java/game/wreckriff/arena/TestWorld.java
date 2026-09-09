package game.wreckriff.arena;

import com.jme3.math.*;
import game.wreckriff.simulation.WorldQuery;
import java.util.*;

/** Geometry boundary double for rule tests only; native collision coverage is physicsTest. */
public final class TestWorld implements WorldQuery {
    public final Vector3f[] positions=new Vector3f[5];
    public final Vector3f[] velocities=new Vector3f[5];
    public final Quaternion[] rotations=new Quaternion[5];
    public final Set<Integer> hidden=new HashSet<>();
    public boolean wall,blockSweeps;
    public TestWorld() {
        for (int id=0;id<5;id++) { positions[id]=new Vector3f(1000+id*100,.8f,1000); velocities[id]=new Vector3f(); rotations[id]=new Quaternion(); }
    }
    public Vector3f position(int id) { return positions[id].clone(); }
    public Vector3f velocity(int id) { return velocities[id].clone(); }
    public Quaternion rotation(int id) { return rotations[id].clone(); }
    public float mass(int id) { return 1100; }
    public boolean grounded(int id) { return true; }
    public Hit ray(Vector3f from,Vector3f to,int ignored) {
        if(from.y>0 && to.y<=0 && Math.abs(from.x-to.x)<.001f && Math.abs(from.z-to.z)<.001f) {
            float fraction=from.y/(from.y-to.y);
            return new Hit(-1,from.clone().interpolateLocal(to,fraction),Vector3f.UNIT_Y.clone(),fraction);
        }
        for (int id:hidden) if (positions[id].distanceSquared(to)<.01f)
            return new Hit(-1,from.clone().interpolateLocal(to,.5f),new Vector3f(0,0,-1),.5f);
        return wall?new Hit(-1,from.clone().interpolateLocal(to,.5f),new Vector3f(0,0,-1),.5f):null;
    }
    public Hit sweep(Vector3f from,Vector3f to,float radius,int ignored) {
        return blockSweeps?new Hit(-1,from.add(0,0,1),new Vector3f(0,0,-1),.1f):null;
    }
    public boolean visible(Vector3f from,Vector3f to,int target) { return !wall && !hidden.contains(target); }
    public float distanceToHull(int id,Vector3f point) { return Math.max(0,positions[id].distance(point)-1); }
    public void impulse(int id,Vector3f impulse) { velocities[id].addLocal(impulse); }
}

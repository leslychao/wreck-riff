package game.wreckriff.presentation;

import com.jme3.math.Vector3f;
import game.wreckriff.simulation.WorldQuery;
import java.util.*;

/** A burst shares six sampled static boundaries; particles never query or own physics. */
final class VfxSurfaceEnvelope {
    private static final class Boundary {
        final Vector3f point,normal;final String objectId;final long revision;
        Boundary(Vector3f point,Vector3f normal,String objectId,long revision){this.point=point;this.normal=normal;this.objectId=objectId;this.revision=revision;}
        float distance(Vector3f p){return (p.x-point.x)*normal.x+(p.y-point.y)*normal.y+(p.z-point.z)*normal.z;}
    }
    record Capture(VfxSurfaceEnvelope envelope,int queries) {}
    private static final Vector3f[] AXES={Vector3f.UNIT_X,Vector3f.UNIT_X.negate(),Vector3f.UNIT_Y,Vector3f.UNIT_Y.negate(),Vector3f.UNIT_Z,Vector3f.UNIT_Z.negate()};
    private final List<Boundary> boundaries;
    private final Vector3f anchor;
    private long validatedFrame=-1;
    private boolean invalidated;
    final boolean stationary;
    private VfxSurfaceEnvelope(List<Boundary> boundaries,Vector3f anchor,boolean stationary){this.boundaries=List.copyOf(boundaries);this.anchor=anchor;this.stationary=stationary;}
    static Capture capture(WorldQuery world,Vector3f centre,Vector3f contactNormal,float radius,int allowance) {
        var boundaries=new ArrayList<Boundary>(7);Vector3f origin=centre.clone();
        if(contactNormal!=null&&contactNormal.lengthSquared()>.1f) {
            var normal=contactNormal.normalize();origin.addLocal(normal.mult(.06f));
        }
        if(allowance<AXES.length)return new Capture(new VfxSurfaceEnvelope(boundaries,origin,true),0);
        for(var axis:AXES) {
            var hit=world.staticSweep(origin,origin.add(axis.mult(radius)),.025f);
            if(hit==null||hit.normal().lengthSquared()<.1f)continue;
            Vector3f point=hit.point(),normal=hit.normal().normalize();
            if(origin.subtract(point).dot(normal)<-.001f)normal.negateLocal();
            boundaries.add(new Boundary(point,normal,hit.objectId(),world.surfaceRevision(hit.objectId())));
        }
        return new Capture(new VfxSurfaceEnvelope(boundaries,origin,false),AXES.length);
    }
    void validate(WorldQuery world,long frame,Map<String,Long> revisions) {
        if(invalidated||validatedFrame==frame)return;validatedFrame=frame;
        for(var boundary:boundaries)if(boundary.objectId!=null&&boundary.revision!=0
                &&boundary.revision!=revisions.computeIfAbsent(boundary.objectId,world::surfaceRevision)) {
            invalidated=true;return;
        }
    }
    boolean invalidated(){return invalidated;}
    void constrain(Vector3f position,Vector3f velocity) {
        // A moved/removed surface invalidates the sampled free region. Stop at
        // the current point and let the caller retire particles without sweeps.
        if(invalidated){velocity.set(0,0,0);return;}
        if(stationary){position.set(anchor);velocity.set(0,0,0);return;}
        // Revisit acute/slanted corners: projecting against the next plane can
        // otherwise cross a previously satisfied boundary.
        for(int pass=0;pass<24;pass++) {
            boolean corrected=false;
            for(var boundary:boundaries) {
                float distance=boundary.distance(position);
                if(distance<.035f) {
                    float correction=.035f-distance;position.addLocal(boundary.normal.x*correction,boundary.normal.y*correction,boundary.normal.z*correction);
                    float inward=velocity.dot(boundary.normal);
                    if(inward<0)velocity.subtractLocal(boundary.normal.mult(inward));
                    corrected|=correction>.00001f;
                }
            }
            if(!corrected)return;
        }
        // Degenerate acute corners cannot consume unbounded CPU. The captured
        // origin lies on the known free side of every sampled surface.
        for(var boundary:boundaries)if(boundary.distance(position)<-.0001f){position.set(anchor);velocity.set(0,0,0);return;}
    }
}

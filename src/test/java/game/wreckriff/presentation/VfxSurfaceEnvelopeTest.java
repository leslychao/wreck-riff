package game.wreckriff.presentation;

import com.jme3.math.Vector3f;
import game.wreckriff.simulation.WorldQuery;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;

class VfxSurfaceEnvelopeTest {
    @Test void oneStaticCaptureContainsTheWholeBurstAtCornerAndCeiling() {
        int[] queries={0};WorldQuery world=(WorldQuery)Proxy.newProxyInstance(WorldQuery.class.getClassLoader(),new Class<?>[]{WorldQuery.class},(proxy,method,args)->{
            if(method.getName().equals("surfaceRevision"))return 0L;
            if(!method.getName().equals("staticSweep"))throw new AssertionError("Only static read queries allowed");queries[0]++;
            Vector3f a=(Vector3f)args[0],b=(Vector3f)args[1];
            if(b.x>1)return hit(a,b,(1-a.x)/(b.x-a.x),Vector3f.UNIT_X.negate());
            if(b.y>2)return hit(a,b,(2-a.y)/(b.y-a.y),Vector3f.UNIT_Y.negate());
            if(b.z>1.5f)return hit(a,b,(1.5f-a.z)/(b.z-a.z),Vector3f.UNIT_Z.negate());
            if(b.y<0)return hit(a,b,-a.y/(b.y-a.y),Vector3f.UNIT_Y);
            return null;
        });
        var capture=VfxSurfaceEnvelope.capture(world,new Vector3f(0,1,0),null,5,6);
        assertEquals(6,capture.queries());assertFalse(capture.envelope().stationary);
        for(int particle=0;particle<100;particle++) {
            Vector3f position=new Vector3f(0,1,0),velocity=new Vector3f(3+particle*.03f,4,5);
            for(int frame=0;frame<120;frame++) {
                position.addLocal(velocity.mult(.016f));capture.envelope().constrain(position,velocity);
                assertTrue(position.x<=.9651f&&position.y<=1.9651f&&position.z<=1.4651f&&position.y>=.0349f);
            }
        }
        assertEquals(6,queries[0],"Particle movement reuses planes without another physics query");
    }
    @Test void exhaustedBudgetRetiresMotionInsteadOfLaunchingUncheckedParticles() {
        var capture=VfxSurfaceEnvelope.capture(null,new Vector3f(0,1,0),Vector3f.UNIT_Y,5,5);
        assertEquals(0,capture.queries());assertTrue(capture.envelope().stationary);
        Vector3f position=new Vector3f(0,1,0),velocity=new Vector3f(7,8,9);
        capture.envelope().constrain(position,velocity);assertEquals(Vector3f.ZERO,velocity);
        assertEquals(new Vector3f(0,1.06f,0),position,"Unchecked spawn offsets must return to the known point");
    }
    @Test void slantedCornerRevisitsEarlierPlanesWithinBoundedProjection() {
        var slanted=new Vector3f(-1,0,1).normalizeLocal();
        WorldQuery world=(WorldQuery)Proxy.newProxyInstance(WorldQuery.class.getClassLoader(),new Class<?>[]{WorldQuery.class},(proxy,method,args)->{
            if(method.getName().equals("surfaceRevision"))return 0L;
            if(!method.getName().equals("staticSweep"))throw new AssertionError(method.getName());
            Vector3f a=(Vector3f)args[0],b=(Vector3f)args[1];
            if(b.x>a.x)return new WorldQuery.Hit(-1,Vector3f.ZERO,slanted,.2f);
            if(b.x<a.x)return new WorldQuery.Hit(-1,Vector3f.ZERO,Vector3f.UNIT_X,.2f);
            return null;
        });
        var envelope=VfxSurfaceEnvelope.capture(world,new Vector3f(.2f,1,1),null,5,6).envelope();
        Vector3f position=new Vector3f(-1,0,-1),velocity=new Vector3f(-4,0,-5);
        envelope.constrain(position,velocity);
        assertTrue(position.x>=.0348f,"The second plane must still be satisfied");
        assertTrue(position.dot(slanted)>=.0348f,"Projection at x=0 must not cross the slanted wall again");
        assertTrue(position.isValidVector(position));
    }
    @Test void changedSurfaceRetiresEnvelopeAndSharesRevisionLookupAcrossParticlesAndBursts() {
        long[] revision={12};int[] lookups={0},sweeps={0};
        WorldQuery world=(WorldQuery)Proxy.newProxyInstance(WorldQuery.class.getClassLoader(),new Class<?>[]{WorldQuery.class},(proxy,method,args)->{
            if(method.getName().equals("surfaceRevision")){lookups[0]++;return revision[0];}
            if(!method.getName().equals("staticSweep"))throw new AssertionError(method.getName());
            sweeps[0]++;Vector3f a=(Vector3f)args[0],b=(Vector3f)args[1];
            return b.y>a.y?new WorldQuery.Hit(-1,new Vector3f(0,2,0),Vector3f.UNIT_Y.negate(),.2f,"ceiling"):null;
        });
        var first=VfxSurfaceEnvelope.capture(world,new Vector3f(0,1,0),null,5,6).envelope();
        var second=VfxSurfaceEnvelope.capture(world,new Vector3f(1,1,0),null,5,6).envelope();
        lookups[0]=0;var cache=new HashMap<String,Long>();
        for(int particle=0;particle<100;particle++){first.validate(world,1,cache);second.validate(world,1,cache);}
        assertEquals(1,lookups[0]);assertFalse(first.invalidated());assertFalse(second.invalidated());
        revision[0]=13;cache.clear();first.validate(world,2,cache);second.validate(world,2,cache);
        assertEquals(2,lookups[0]);assertTrue(first.invalidated());assertTrue(second.invalidated());
        Vector3f position=new Vector3f(.4f,1.7f,.2f),before=position.clone(),velocity=new Vector3f(2,3,4);
        first.constrain(position,velocity);
        assertEquals(before,position,"Retirement must not teleport old smoke back to its burst origin");
        assertEquals(Vector3f.ZERO,velocity);
        assertEquals(12,sweeps[0],"Retirement uses stamps, never per-particle sweeps");
    }
    private static WorldQuery.Hit hit(Vector3f a,Vector3f b,float t,Vector3f normal){return new WorldQuery.Hit(-1,a.clone().interpolateLocal(b,t),normal,t);}
}

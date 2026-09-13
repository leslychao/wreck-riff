package game.wreckriff.presentation;

import com.jme3.math.Vector3f;
import game.wreckriff.simulation.WorldQuery;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

class VfxSurfaceEnvelopeTest {
    @Test void oneStaticCaptureContainsTheWholeBurstAtCornerAndCeiling() {
        int[] queries={0};WorldQuery world=(WorldQuery)Proxy.newProxyInstance(WorldQuery.class.getClassLoader(),new Class<?>[]{WorldQuery.class},(proxy,method,args)->{
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
    }
    private static WorldQuery.Hit hit(Vector3f a,Vector3f b,float t,Vector3f normal){return new WorldQuery.Hit(-1,a.clone().interpolateLocal(b,t),normal,t);}
}

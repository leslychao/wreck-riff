package game.wreckriff.simulation;

import com.jme3.math.*;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ContactContractTest {
    @Test void presentationProjectionCopiesWorldPoseWithoutChangingAuthoritativeContactOrTiming() {
        var local=new VehicleContact(new Vector3f(1,.2f,0),Vector3f.UNIT_X);
        var shot=new ShotEmission("weapon-muzzle",Vector3f.UNIT_Z,new Vector3f(10,0,0));
        var original=new GameEvent(GameEvent.Type.IMPACT,42,1,0,new Vector3f(2,3,4),"machine-gun",2,
                new Vector3f(5,6,7),Vector3f.UNIT_X,UUID.randomUUID(),"panel")
                .withContact(ContactSurface.GLASS,local).withEmission(shot).withHealthChange(new HealthChange(100,98)).atTick(100,4);
        Vector3f presentedPoint=new Vector3f(10,3,4),presentedNormal=Vector3f.UNIT_Z.clone();
        var presented=original.forPresentation(presentedPoint,presentedNormal);
        presentedPoint.zero();presentedNormal.zero();
        assertEquals(new Vector3f(2,3,4),original.position());assertEquals(Vector3f.UNIT_X,original.normal());
        assertEquals(new Vector3f(10,3,4),presented.position());assertEquals(Vector3f.UNIT_Z,presented.normal());
        assertEquals(original,presented.forPresentation(original.position(),original.normal()));
        assertSame(local,presented.vehicleContact());assertSame(shot,presented.emission());
        assertThrows(IllegalArgumentException.class,()->original.forPresentation(new Vector3f(Float.NaN,0,0),Vector3f.UNIT_Y));
    }
    @Test void snapshotsOwnTheirVectorsAndEventCopiesRetainAllContactMetadata() {
        Vector3f point=new Vector3f(1,2,3),normal=new Vector3f(0,0,-1),velocity=new Vector3f(4,0,5);
        var contact=new VehicleContact(point,normal);
        var emission=new ShotEmission("machine-gun-muzzle-1",Vector3f.UNIT_Z,velocity);
        var event=new GameEvent(GameEvent.Type.DAMAGE,7,1,0,point,"machine-gun",2,Vector3f.ZERO,normal)
                .withContact(ContactSurface.METAL,contact).withEmission(emission)
                .withHealthChange(new HealthChange(100,98)).atTick(14,3);
        UUID session=UUID.randomUUID();event=event.inSession(session).forObject("hull");
        point.set(9,9,9);normal.set(9,9,9);velocity.set(9,9,9);
        contact.localPoint().zero();emission.sourceVelocity().zero();event.position().zero();
        assertEquals(new Vector3f(1,2,3),event.position());
        assertEquals(new Vector3f(1,2,3),event.vehicleContact().localPoint());
        assertEquals(new Vector3f(0,0,-1),event.vehicleContact().localNormal());
        assertEquals(new Vector3f(4,0,5),event.emission().sourceVelocity());
        assertEquals(ContactSurface.METAL,event.surface());assertEquals(session,event.sessionId());
        assertEquals(new HealthChange(100,98),event.healthChange());
        assertEquals(14,event.simulationTick());assertEquals(3,event.ordinalWithinTick());
        var hit=new WorldQuery.Hit(1,event.position(),event.normal(),.5f,null,ContactSurface.METAL,contact);
        hit.point().zero();hit.normal().zero();assertEquals(event.position(),hit.point());assertEquals(event.normal(),hit.normal());
    }
    @Test void authoredMaterialNamesMapExplicitlyAndUnknownMaterialsRemainUnknown() {
        assertEquals(ContactSurface.CONCRETE,ContactSurface.fromMaterial("concrete"));
        assertEquals(ContactSurface.METAL,ContactSurface.fromMaterial("steel"));
        assertEquals(ContactSurface.ASPHALT,ContactSurface.fromMaterial("road-wet"));
        assertEquals(ContactSurface.UNKNOWN,ContactSurface.fromMaterial("unregistered"));
        assertThrows(IllegalArgumentException.class,()->new VehicleContact(new Vector3f(Float.NaN,0,0),Vector3f.UNIT_Y));
        assertThrows(IllegalArgumentException.class,()->new HealthChange(10,Float.NaN));
    }
}

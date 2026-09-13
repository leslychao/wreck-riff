package game.wreckriff.simulation;

import com.jme3.math.*;
import game.wreckriff.presentation.ContactPresentationTimeline;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ContactContractTest {
    @Test void deferredMovingContactKeepsItsOriginalDeadlineAndUsesThePresentedTargetPose() {
        UUID session=UUID.randomUUID();Vector3f point=new Vector3f(0,0,18);
        var contact=VehicleContact.atPose(point,Vector3f.UNIT_X,new Vector3f(-1,0,18),new Quaternion());
        var hit=new GameEvent(GameEvent.Type.IMPACT,41,1,0,point,"machine-gun",0,Vector3f.ZERO,Vector3f.UNIT_X)
                .withContact(ContactSurface.METAL,contact).inSession(session).atTick(120,0);
        var damage=new GameEvent(GameEvent.Type.DAMAGE,41,1,0,point,"machine-gun",7,Vector3f.ZERO,Vector3f.UNIT_X)
                .withContact(ContactSurface.METAL,contact).withHealthChange(new HealthChange(100,93)).inSession(session).atTick(120,1);
        try(var timeline=new ContactPresentationTimeline(session)) {
            timeline.accept(List.of(hit,damage),1+1.0/120);
            assertTrue(timeline.advanceTo(1.099).isEmpty());assertEquals(100,timeline.visibleHp(1,93,100));
            Quaternion renderedRotation=new Quaternion().fromAngleAxis(FastMath.HALF_PI,Vector3f.UNIT_Y);
            Vector3f renderedPosition=new Vector3f(5,0,18);
            var presented=timeline.advanceTo(1.101).stream().map(event->event.forPresentation(
                    renderedPosition.add(renderedRotation.mult(event.vehicleContact().localPoint())),
                    renderedRotation.mult(event.vehicleContact().localNormal()))).toList();
            assertEquals(2,presented.size());assertEquals(presented.getFirst().position(),presented.getLast().position());
            assertTrue(presented.getFirst().position().distance(new Vector3f(5,0,17))<.0001f);
            assertTrue(presented.getFirst().normal().distance(Vector3f.UNIT_Z.negate())<.0001f);
            assertEquals(point,hit.position());assertEquals(Vector3f.ZERO,presented.getFirst().origin());
            assertEquals(contact,presented.getFirst().vehicleContact());assertEquals(93,timeline.visibleHp(1,93,100));
        }
    }
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

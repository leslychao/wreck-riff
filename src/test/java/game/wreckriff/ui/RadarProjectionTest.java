package game.wreckriff.ui;

import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RadarProjectionTest {
    private RadarProjection.Target target(int id,float x,float z,int floor) {
        return new RadarProjection.Target(id,x,z,floor,true,false,false);
    }

    @Test void forwardIsUpAndDriversRightIsRightForActualPositiveZCarConvention() {
        var view=new RadarProjection.Observer(0,0,new RadarProjection.Heading(0,1),0);
        var front=RadarProjection.project(view,target(1,0,32.5f,0));
        var right=RadarProjection.project(view,target(2,-32.5f,0,0));
        assertEquals(.5,front.y(),1e-6); assertEquals(0,front.x(),1e-6);
        assertEquals(.5,right.x(),1e-6); assertEquals(0,right.y(),1e-6);
        var rotated=new RadarProjection.Observer(0,0,new RadarProjection.Heading(1,0),0);
        assertEquals(.5,RadarProjection.project(rotated,target(1,32.5f,0,0)).y(),1e-6);
        assertEquals(.5,RadarProjection.project(rotated,target(2,0,32.5f,0)).x(),1e-6);
    }

    @Test void pitchAndLocalRollDoNotMoveHorizontalHeadingAndVerticalUsesPreviousHeading() {
        float yaw=.7f;
        var plain=RadarProjection.Heading.fromRotation(new Quaternion().fromAngles(0,yaw,0),null);
        // Apply pitch and roll in the car's local frame, rather than changing its world yaw.
        Quaternion attitude=new Quaternion().fromAngleAxis(yaw,Vector3f.UNIT_Y)
                .mult(new Quaternion().fromAngleAxis(.6f,Vector3f.UNIT_X))
                .mult(new Quaternion().fromAngleAxis(.8f,Vector3f.UNIT_Z));
        var tilted=RadarProjection.Heading.fromRotation(attitude,plain);
        assertEquals(plain.x(),tilted.x(),1e-5); assertEquals(plain.z(),tilted.z(),1e-5);
        var vertical=RadarProjection.Heading.fromRotation(new Quaternion().fromAngles((float)Math.PI/2,0,0),plain);
        assertEquals(plain,vertical);
    }

    @Test void usesHorizontalRangeAndExplicitStableRoadLevel() {
        var view=new RadarProjection.Observer(100,-100,new RadarProjection.Heading(0,1),1);
        var atEdge=RadarProjection.project(view,target(1,100,-35,2));
        assertFalse(atEdge.far()); assertEquals(1,atEdge.y(),1e-6);
        assertEquals(RadarProjection.Floor.ABOVE,atEdge.floor());
        var far=RadarProjection.project(view,target(2,100,1000,0));
        assertTrue(far.far()); assertEquals(1,far.y(),1e-6);
        assertEquals(RadarProjection.Floor.BELOW,far.floor());
    }

    @Test void nearParticipantsStayDistinctWhileFarDirectionsGroupWithoutHidingBossOrLock() {
        var view=new RadarProjection.Observer(0,0,new RadarProjection.Heading(0,1),0);
        var markers=RadarProjection.project(view,List.of(target(4,0,100,0),target(19,0,120,0),
                target(2,0,15,0),new RadarProjection.Target(99,0,120,0,true,true,false),
                new RadarProjection.Target(123,0,120,0,true,false,true),
                new RadarProjection.Target(5,0,10,0,false,false,false)));
        assertEquals(4,markers.size());
        assertEquals(2,markers.stream().filter(m->m.count()==2).findFirst().orElseThrow().count());
        assertTrue(markers.stream().anyMatch(m->m.key().equals("participant:99") && m.boss()));
        assertTrue(markers.stream().anyMatch(m->m.key().equals("participant:123") && m.locked()));
        assertFalse(markers.stream().anyMatch(m->m.key().equals("participant:5")));
    }
}

package game.wreckriff.arena;

import com.jme3.math.Vector3f;
import game.wreckriff.config.*;
import java.util.*;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent AWT polygon intersections and actual boss routing validate authored headroom. */
class CampaignNavClearanceTest {
    @Test void eachRoadCorridorAdvertisesItsRealUndersideOrOpenSky() {
        var registry=ArenaRegistry.load();
        for(String id:registry.campaignIds()) {
            var arena=registry.definition(id);
            Map<Integer,Vector3f> nodes=new HashMap<>();arena.nodes().forEach(n->nodes.put(n.id(),n.position().vector()));
            int covered=0,open=0;
            for(var edge:arena.edges()) {
                if(edge.type()==ArenaDefinition.Transition.LAUNCH||edge.type()==ArenaDefinition.Transition.DROP)continue;
                float measured=measuredClearance(arena,edge,nodes);
                assertEquals(measured,edge.clearance(),.001f,id+" "+edge.id());
                if(measured<30)covered++;else open++;
            }
            assertTrue(covered>0,id+" must retain real covered routes");
            assertTrue(open>0,id+" must expose real open routes");
        }
    }
    @Test void thinRailOverhangIsDetectedEvenBetweenSamplingColumns() {
        var arena=ArenaRegistry.load().definition("construction_17");
        var edge=arena.edges().stream().filter(e->e.id().equals("road-north-east-north-mid")).findFirst().orElseThrow();
        var rail=arena.boxes().stream().filter(b->b.id().equals("parking-north-0")).findFirst().orElseThrow();
        assertEquals(8,rail.center().y()-rail.size().y()/2,.0001f);
        Map<Integer,Vector3f> nodes=new HashMap<>();arena.nodes().forEach(n->nodes.put(n.id(),n.position().vector()));
        assertEquals(8,measuredClearance(arena,edge,nodes),.0001f);
    }
    @Test void everyFullBossCanUseTheAuthoredRampFromItsEntranceWithLaunchesDisabled() {
        var registry=ArenaRegistry.load();var rules=VehicleRules.load();
        for(String id:registry.campaignIds()) {
            var arena=registry.definition(id);var boss=arena.bosses().getFirst();
            var profile=VehicleProfile.boss(boss.profileId(),rules);var bounds=profile.fullBounds();
            var graph=new NavGraph(arena);
            int start=graph.nearest(boss.entrances().getFirst().position().vector());
            var ramp=arena.ramps().stream().filter(r->r.id().equals("upper-ramp")).findFirst().orElseThrow();
            int goal=graph.nearest(new Vector3f((ramp.minX()+ramp.maxX())/2,8,ramp.maxZ()+12));
            var mobility=new NavGraph.Mobility(bounds.maxX()-bounds.minX(),profile.roadOffset()+bounds.maxY(),14,false,true,Set.of());
            var route=graph.route(start,goal,mobility,Set.of(),2,10);
            assertFalse(route.nodes().isEmpty(),id+" full "+profile.id()+" requires a physical upper route");
            assertTrue(route.traversals().stream().anyMatch(s->s.type()==ArenaDefinition.Transition.RAMP),id);
            assertTrue(route.traversals().stream().noneMatch(s->s.type()==ArenaDefinition.Transition.LAUNCH),id);
            for(var edge:arena.edges())if(edge.type()==ArenaDefinition.Transition.RAMP&&edge.objectId().equals("upper-ramp")) {
                assertEquals(30,edge.clearance(),id+" "+edge.id()+" is open sky");
                assertEquals(24,edge.width());
            }
        }
    }
    @Test void finalShortcutWidthAccountsForItsActualControlShield() {
        var arena=ArenaRegistry.load().definition("doomsday_arena");
        var edge=arena.edges().stream().filter(e->e.id().equals("openable-short-cut-before-short-cut-after")).findFirst().orElseThrow();
        var shield=arena.boxes().stream().filter(b->b.id().equals("control-shield-south-panel")).findFirst().orElseThrow();
        float centre=arena.nodes().stream().filter(n->n.id()==edge.from()).findFirst().orElseThrow().position().z();
        assertEquals(2*(shield.center().z()-shield.size().z()/2-centre),edge.width());
        assertEquals(6,edge.width());
    }
    private static float measuredClearance(ArenaDefinition arena,ArenaDefinition.NavEdge edge,Map<Integer,Vector3f> nodes) {
        Vector3f a=nodes.get(edge.from()),b=nodes.get(edge.to()),direction=b.subtract(a).setY(0);
        double length=direction.length(),half=edge.width()/2;
        Area corridor;
        if(length<.0001) {
            corridor=new Area(new Rectangle2D.Double(a.x-half,a.z-half,2*half,2*half));
        } else {
            double nx=-direction.z/length*half,nz=direction.x/length*half;
            var path=new Path2D.Double();path.moveTo(a.x+nx,a.z+nz);path.lineTo(a.x-nx,a.z-nz);
            path.lineTo(b.x-nx,b.z-nz);path.lineTo(b.x+nx,b.z+nz);path.closePath();corridor=new Area(path);
        }
        Set<String> ignored=new HashSet<>();
        if(edge.type()==ArenaDefinition.Transition.OPENABLE) {
            arena.destructibles().stream().filter(d->d.id().equals(edge.objectId())).forEach(d->ignored.add(d.geometryId()));
            arena.barriers().stream().filter(d->d.id().equals(edge.objectId())).forEach(d->ignored.add(d.geometryId()));
        }
        double measured=30;
        for(var box:arena.boxes()) {
            if(!box.collision()||ignored.contains(box.id()))continue;
            var c=box.center();var s=box.size();
            double[] height=overlapHeights(corridor,new Rectangle2D.Double(c.x()-s.x()/2,c.z()-s.z()/2,s.x(),s.z()),a,b);
            if(height==null||c.y()+s.y()/2<=height[0]+.05)continue;
            double gap=c.y()-s.y()/2-height[1];
            assertTrue(gap>0,arena.id()+" "+edge.id()+" crosses "+box.id());
            measured=Math.min(measured,gap);
        }
        for(var ramp:arena.ramps()) {
            double[] height=overlapHeights(corridor,new Rectangle2D.Double(ramp.minX(),ramp.minZ(),ramp.maxX()-ramp.minX(),ramp.maxZ()-ramp.minZ()),a,b);
            if(height!=null&&ramp.bottomY()>height[1])measured=Math.min(measured,ramp.bottomY()-height[1]);
        }
        return (float)measured;
    }
    private static double[] overlapHeights(Area corridor,Rectangle2D rectangle,Vector3f a,Vector3f b) {
        var overlap=(Area)corridor.clone();overlap.intersect(new Area(rectangle));if(overlap.isEmpty())return null;
        double minimum=Double.POSITIVE_INFINITY,maximum=Double.NEGATIVE_INFINITY;
        double dx=b.x-a.x,dz=b.z-a.z,squared=dx*dx+dz*dz;
        double[] point=new double[6];var iterator=overlap.getPathIterator(null);
        while(!iterator.isDone()) {
            int kind=iterator.currentSegment(point);
            if(kind!=PathIterator.SEG_CLOSE) {
                double t=squared<1e-8?0:Math.clamp(((point[0]-a.x)*dx+(point[1]-a.z)*dz)/squared,0,1);
                double y=a.y+(b.y-a.y)*t;minimum=Math.min(minimum,y);maximum=Math.max(maximum,y);
            }
            iterator.next();
        }
        return new double[]{minimum,maximum};
    }
}

package game.wreckriff.arena;

import com.jme3.math.Vector3f;
import game.wreckriff.config.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent geometry samples and actual boss routing validate the authored headroom. */
class CampaignNavClearanceTest {
    @Test void eachRoadCorridorAdvertisesItsRealUndersideOrOpenSky() {
        var registry=ArenaRegistry.load();
        for(String id:registry.campaignIds()) {
            var arena=registry.definition(id);
            Map<Integer,Vector3f> nodes=new HashMap<>();arena.nodes().forEach(n->nodes.put(n.id(),n.position().vector()));
            int covered=0,open=0;
            for(var edge:arena.edges()) {
                if(edge.type()==ArenaDefinition.Transition.LAUNCH||edge.type()==ArenaDefinition.Transition.DROP)continue;
                Vector3f a=nodes.get(edge.from()),b=nodes.get(edge.to()),direction=b.subtract(a).setY(0);
                float distance=direction.length();
                if(distance<.001f)direction.set(1,0,0);else direction.divideLocal(distance);
                Vector3f normal=new Vector3f(-direction.z,0,direction.x);
                Set<String> ignored=new HashSet<>();
                if(edge.type()==ArenaDefinition.Transition.OPENABLE) {
                    arena.destructibles().stream().filter(d->d.id().equals(edge.objectId())).forEach(d->ignored.add(d.geometryId()));
                    arena.barriers().stream().filter(d->d.id().equals(edge.objectId())).forEach(d->ignored.add(d.geometryId()));
                }
                float measured=30;
                int steps=Math.max(1,(int)Math.ceil(distance*2));
                for(int along=0;along<=steps;along++)for(int side=0;side<=32;side++) {
                    Vector3f p=new Vector3f().interpolateLocal(a,b,along/(float)steps).addLocal(normal.mult(edge.width()*(side/32f-.5f)));
                    for(var box:arena.boxes()) {
                        if(!box.collision()||ignored.contains(box.id()))continue;
                        var c=box.center();var s=box.size();
                        if(Math.abs(p.x-c.x())>=s.x()/2-.0001f||Math.abs(p.z-c.z())>=s.z()/2-.0001f)continue;
                        float bottom=c.y()-s.y()/2,top=c.y()+s.y()/2;
                        if(top<=p.y+.05f)continue;
                        assertTrue(bottom>p.y,id+" "+edge.id()+" crosses "+box.id()+" at "+p);
                        measured=Math.min(measured,bottom-p.y);
                    }
                    for(var ramp:arena.ramps())if(p.x>=ramp.minX()&&p.x<=ramp.maxX()&&p.z>=ramp.minZ()&&p.z<=ramp.maxZ()&&ramp.bottomY()>p.y)
                        measured=Math.min(measured,ramp.bottomY()-p.y);
                }
                assertEquals(measured,edge.clearance(),.001f,id+" "+edge.id());
                if(measured<30)covered++;else open++;
            }
            assertTrue(covered>0,id+" must retain real covered routes");
            assertTrue(open>0,id+" must expose real open routes");
        }
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
}

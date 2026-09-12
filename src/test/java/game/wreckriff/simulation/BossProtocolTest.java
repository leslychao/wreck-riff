package game.wreckriff.simulation;

import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import game.wreckriff.ai.AiRules;
import game.wreckriff.ai.BotController;
import game.wreckriff.arena.ArenaDefinition;
import game.wreckriff.arena.ArenaRegistry;
import game.wreckriff.combat.CombatRules;
import game.wreckriff.config.Configs;
import game.wreckriff.config.VehicleProfile;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BossProtocolTest {
    @Test void ashCanRequestTheLowerFireStripWhileBothCarsAreBelow() {
        var rig=new Rig(false,new Vector3f(84,1,65),new Vector3f(84,.5f,76),0,0);
        var commands=rig.commands(720);
        assertEquals(List.of("ritual-west"),commands.stream().map(BotController.BossCommand::targetId).toList());
        assertEquals(BotController.BossCommandKind.RITE,commands.getFirst().kind());
    }
    @Test void ashCannotRequestUpperFireOnlyBecauseTheOtherCarIsAbove() {
        var rig=new Rig(true,new Vector3f(162,1,154),new Vector3f(162,8.5f,164),0,1);
        assertTrue(rig.commands(720).isEmpty());
    }
    @Test void ashMayRequestTheUpperFireWhenBothCarsAreOnThatRoad() {
        var rig=new Rig(true,new Vector3f(162,9,154),new Vector3f(162,8.5f,164),1,1);
        assertEquals(List.of("ritual-upper"),rig.commands(720).stream().map(BotController.BossCommand::targetId).toList());
    }
    @Test void ashCannotPrepareUpperFireOnAnActiveLaunchOrImmediatelyAfterLanding() {
        var rig=new Rig(true,new Vector3f(162,9,154),new Vector3f(162,8.5f,164),1,1);
        rig.world.roads[0]=new RoadContext("floor",0,1,RoadContext.Motion.LAUNCH,"pad","mausoleum-court",1);
        assertTrue(rig.commands(720).isEmpty());
        rig.world.roads[0]=road(1);
        assertTrue(rig.commands(732).isEmpty(),"The first 0.7 s of landing are not an upper-fire approach");
        assertEquals(List.of("ritual-upper"),rig.commands(816).stream().map(BotController.BossCommand::targetId).toList());
    }
    @Test void directRampApproachMayPrepareUpperFireButDescendingTheSameRampMayNot() {
        for(float verticalSpeed:new float[]{-2,2}) {
            var rig=new Rig(true,new Vector3f(132,7,101),new Vector3f(132,8.25f,110),0,0);
            rig.world.roads[0]=new RoadContext("upper-ramp",0,1,RoadContext.Motion.RAMP,"upper-ramp","mausoleum-court",1);
            rig.world.velocities[0]=new Vector3f(0,verticalSpeed,16);
            assertEquals(verticalSpeed>0,!rig.commands(720).isEmpty());
        }
    }
    @Test void presentationCanReadAnUnregisteredBossWithoutGuessingItsParticipantId() {
        var rig=new Rig(false,new Vector3f(84,1,65),new Vector3f(84,.5f,76),0,0);
        assertTrue(rig.bots.bossAction(99).isEmpty());assertTrue(rig.bots.bossAction(0).isEmpty());
        assertTrue(rig.bots.bossAction(rig.bossId).isPresent());
    }

    private static RoadContext road(int level){return new RoadContext(level==0?"floor":"mausoleum-court",level,1,RoadContext.Motion.ROAD,"","",level);}
    private static final class Rig {
        final MatchSession session;final int bossId;final Query world;final BotController bots;
        Rig(boolean onlyUpper,Vector3f boss,Vector3f player,int bossLevel,int playerLevel) {
            var base=ArenaRegistry.load().definition("ash_necropolis");
            var arena=onlyUpper?new ArenaDefinition(base.schemaVersion(),base.id(),base.metadata(),base.bounds(),base.boxes(),base.ramps(),base.spawns(),
                    base.pickups(),base.hazards().stream().filter(h->h.id().equals("ritual-upper")).toList(),base.nodes(),base.edges(),base.surfaces(),
                    base.launchPads(),base.drops(),base.destructibles(),base.secrets(),base.barriers(),base.bosses()):base;
            session=new MatchSession(91,arena,MatchSession.Mode.BOSS_DUEL,Configs.load("combat",CombatRules.class));
            bossId=session.registerBoss(arena.bosses().getFirst()).id;
            session.phase=MatchSession.Phase.BOSS_COMBAT;session.bossMode=2;
            world=new Query(boss,player,bossLevel,playerLevel);
            bots=new BotController(session,arena,AiRules.load(),List::of);bots.observeHazards(List::of);
        }
        List<BotController.BossCommand> commands(long tick){session.tick=tick;bots.commands(world);return bots.drainBossCommands();}
    }
    /** Explicit observations for the policy contract; native driving is covered separately. */
    private static final class Query implements WorldQuery {
        final Vector3f[] positions,velocities={new Vector3f(),new Vector3f()};final RoadContext[] roads;
        Query(Vector3f boss,Vector3f player,int bossLevel,int playerLevel){positions=new Vector3f[]{player,boss};roads=new RoadContext[]{road(playerLevel),road(bossLevel)};}
        public Vector3f position(int id){return positions[id].clone();}
        public Vector3f velocity(int id){return velocities[id].clone();}
        public Quaternion rotation(int id){var to=positions[1-id].subtract(positions[id]);return new Quaternion().fromAngleAxis((float)Math.atan2(to.x,to.z),Vector3f.UNIT_Y);}
        public boolean grounded(int id){return !roads[id].flying();}
        public float mass(int id){return profile(id).mass();}
        public VehicleProfile profile(int id){return id==0?VehicleProfile.rivet():VehicleProfile.boss("boss_ash_shepherd",game.wreckriff.config.VehicleRules.load());}
        public RoadContext roadContext(int id){return roads[id];}
        public Hit ray(Vector3f from,Vector3f to,int ignored){return null;}
        public Hit sweep(Vector3f from,Vector3f to,float radius,int ignored,float start,float end){return null;}
        public Hit staticSweep(Vector3f from,Vector3f to,float radius){return null;}
        public Support support(Vector3f from,float depth){return new Support(0,new Vector3f(from.x,from.y>9?8:0,from.z),Vector3f.UNIT_Y);}
        public boolean visible(Vector3f from,Vector3f to,int target){return true;}
        public float distanceToHull(int id,Vector3f point){return positions[id].distance(point);}
        public void impulse(int id,Vector3f linear,Vector3f torque,float maximum){}
        public Vector3f closestHullPoint(int id,Vector3f from){return positions[id].clone();}
    }
}

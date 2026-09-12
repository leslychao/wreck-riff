package game.wreckriff.simulation;

import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import game.wreckriff.ai.AiRules;
import game.wreckriff.ai.BotController;
import game.wreckriff.arena.ArenaDefinition;
import game.wreckriff.arena.ArenaRegistry;
import game.wreckriff.combat.CombatRules;
import game.wreckriff.config.Configs;
import game.wreckriff.config.VehicleProfile;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.vehicle.VehicleController;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** A real shared driver, chassis and Bullet contact; only the straight test road is synthetic. */
class NativeBossRamTest {
    @ParameterizedTest @ValueSource(strings={"construction_17","doomsday_arena"})
    void advertisedRamReachesItsActualTargetHullInsteadOfTreatingItAsAnObstacle(String arenaId) {
        try(var rig=new Rig(arenaId,false)) {
            var warning=rig.beginWarning();
            long firstContact=-1;float impactSpeed=0;
            for(int step=0;step<600;step++) {
                rig.tick();
                for(var ram:rig.world.rams())if(ram.first()==rig.bossId||ram.second()==rig.bossId) {
                    firstContact=rig.session.tick-1;impactSpeed=Math.max(impactSpeed,ram.closingSpeed());
                    assertEquals("CHARGE",rig.bots.bossAction(rig.bossId).orElseThrow().phase(),
                            "The native hit must be the advertised attack, not later ordinary pursuit");
                }
                if(firstContact>=0)break;
            }
            assertTrue(firstContact>=warning.untilTick(),"No native contact after full warning: "+rig.diagnostic());
            assertTrue(impactSpeed>8,"A ram must reach the chassis at actual closing speed: "+impactSpeed);
            assertEquals(0,rig.world.teleportGeneration(rig.bossId));
            assertTrue(rig.events.stream().noneMatch(e->e.kind()==BotController.BossCommandKind.RAM_MISSED),
                    "Contact at "+firstContact+" after miss "+rig.events+" "+rig.diagnostic());
        }
    }

    @ParameterizedTest @ValueSource(strings={"construction_17","doomsday_arena"})
    void aWallAppearingAfterWarningStopsTheChargeBeforeNativeCollision(String arenaId) {
        try(var rig=new Rig(arenaId,false)) {
            var warning=rig.beginWarning();
            while(rig.session.tick<warning.untilTick())rig.tick();
            var position=rig.world.position(rig.bossId);
            float wallZ=position.z+rig.world.profile(rig.bossId).length()/2+6;
            rig.world.addStatic("new-wall",new BoxCollisionShape(new Vector3f(8,5,.5f)),new Vector3f(position.x,5,wallZ),new Quaternion());
            for(int step=0;step<240&&!rig.bots.bossAction(rig.bossId).orElseThrow().phase().equals("RECOVERY");step++) {
                rig.tick();
                assertTrue(rig.world.position(rig.bossId).z+rig.world.profile(rig.bossId).length()/2<wallZ-.5f,
                        "Charge reached the wall before its forward corridor guard reacted");
            }
            assertEquals("RECOVERY",rig.bots.bossAction(rig.bossId).orElseThrow().phase());
            assertTrue(rig.world.rams().isEmpty());
            rig.tick();
            assertEquals(1,rig.events.stream().filter(e->e.kind()==BotController.BossCommandKind.RAM_MISSED).count());
            assertTrue(rig.world.position(rig.bossId).z+rig.world.profile(rig.bossId).length()/2<wallZ-.5f);
        }
    }

    @ParameterizedTest @ValueSource(strings={"construction_17","doomsday_arena"})
    void driverCanLeaveTheAdvertisedLineBeforeChargeAndBossRecoversAfterARealMiss(String arenaId) {
        try(var rig=new Rig(arenaId)) {
            var warning=rig.beginWarning();
            Vector3f advertised=warning.targetPoint().vector();
            // The player drives sideways using its own wheels throughout warning and charge.
            rig.playerCommand=new VehicleCommand(1,0,0,false,false,false,false,null,0,false,false,
                    game.wreckriff.combat.AbilityId.NONE);
            boolean charged=false,recovered=false;
            for(int step=0;step<650&&!recovered;step++) {
                rig.tick();var action=rig.bots.bossAction(rig.bossId).orElseThrow();
                if(action.phase().equals("CHARGE")) {
                    charged=true;
                    assertEquals(advertised,action.targetPoint().vector(),"Committed aim followed later player movement");
                }
                assertTrue(rig.world.rams().isEmpty(),"Player could not evade the advertised straight ram");
                recovered=action.phase().equals("RECOVERY");
            }
            assertTrue(charged&&recovered,"Charge never completed its real miss: "+rig.diagnostic());
            rig.tick();
            assertEquals(1,rig.events.stream().filter(e->e.kind()==BotController.BossCommandKind.RAM_MISSED).count());
            assertTrue(Math.abs(rig.world.position(0).x-advertised.x)>12,"Player must have physically left the corridor");
            assertEquals(0,rig.world.teleportGeneration(0));assertEquals(0,rig.world.teleportGeneration(rig.bossId));
        }
    }

    @ParameterizedTest @ValueSource(strings={"construction_17","doomsday_arena"})
    void visibleTargetBehindALowSolidBlockDoesNotPermitTheAdvertisedRam(String arenaId) {
        try(var rig=new Rig(arenaId)) {
            rig.world.addStatic("ram-block",new BoxCollisionShape(new Vector3f(7,.45f,1)),new Vector3f(110,.45f,72),new Quaternion());
            rig.session.tick=360;
            rig.bots.commands(rig.world);
            assertNotNull(rig.bots.observation(rig.bossId).visible(0),"Fixture must keep the player visible above the low blocker: "+rig.diagnostic()
                    +" player="+rig.world.position(0)+" los="+rig.world.ray(rig.world.position(rig.bossId).add(0,.6f,0),rig.world.position(0),rig.bossId));
            assertEquals("CRUISE",rig.bots.bossAction(rig.bossId).orElseThrow().phase());
        }
    }

    private static final class Rig implements AutoCloseable {
        final VehicleRules rules=VehicleRules.load();
        final PhysicsWorld world=new PhysicsWorld(rules);
        final MatchSession session;
        final int bossId;
        final BotController bots;
        final VehicleController bossDriver,playerDriver;
        final List<BotController.BossCommand> events=new ArrayList<>();
        VehicleCommand playerCommand=VehicleCommand.NONE;
        Rig(String arenaId) {this(arenaId,true);}
        Rig(String arenaId,boolean playerSideways) {
            var arena=straightRoad(ArenaRegistry.load().definition(arenaId));
            session=new MatchSession(47,arena,MatchSession.Mode.BOSS_DUEL,Configs.load("combat",CombatRules.class));
            bossId=session.registerBoss(arena.bosses().getFirst()).id;session.phase=MatchSession.Phase.BOSS_COMBAT;
            var profile=VehicleProfile.boss(session.vehicle(bossId).profileId,rules);
            world.addStatic("ram-road",new BoxCollisionShape(new Vector3f(300,.5f,300)),new Vector3f(150,-.5f,150),new Quaternion());
            world.configureArena(arena);
            world.addVehicle(bossId,new Vector3f(110,profile.roadOffset()+.3f,60),new Quaternion(),profile);
            world.addVehicle(0,new Vector3f(110,VehicleProfile.rivet(rules).roadOffset()+.3f,85.1f),
                    new Quaternion().fromAngleAxis(playerSideways?(float)Math.PI/2:0,Vector3f.UNIT_Y));
            for(int tick=0;tick<360;tick++)world.step();
            bossDriver=new VehicleController(world,session.vehicle(bossId),rules,arena.bounds(),0);
            playerDriver=new VehicleController(world,session.vehicle(0),rules,arena.bounds(),0);
            bots=new BotController(session,arena,AiRules.load(),List::of);
        }
        BotController.BossActionView beginWarning() {
            // The boss has reached the straight at ordinary driving speed before it signals.
            world.vehicle(bossId).setLinearVelocity(new Vector3f(0,0,12));
            session.tick=360;tick();
            var action=bots.bossAction(bossId).orElseThrow();
            assertEquals("TELEGRAPH",action.phase(),"Ready boss refused a supported visible corridor: "+diagnostic());
            return action;
        }
        void tick() {
            var command=bots.commands(world).get(bossId).withoutAttacks();
            assertFalse(command.recover());
            var phase=bots.bossAction(bossId).orElseThrow().phase();
            if(phase.equals("TELEGRAPH")||phase.equals("CHARGE"))
                assertNotEquals(BotController.State.RECOVER,bots.state(bossId),"Intentional warning was treated as being stuck");
            bossDriver.drive(command);playerDriver.drive(playerCommand);world.step();
            for(var ram:world.rams())if(ram.first()==bossId||ram.second()==bossId)bots.confirmRamContact(bossId,session.tick);
            events.addAll(bots.drainBossCommands());session.tick++;
        }
        String diagnostic(){return session.vehicle(bossId).profileId+" "+world.position(bossId)+" / "+bots.bossAction(bossId)+" / "+bots.navigation(bossId)
                +" / visible="+bots.observation(bossId).visible()+" forward="+world.forward(bossId)+" road="+world.roadContext(bossId)
                +" sweep="+world.sweep(world.position(bossId).add(0,1.2f,0),world.position(0).add(0,1.2f,0),world.profile(bossId).width()/2,bossId);}
        @Override public void close(){world.close();}
    }

    private static ArenaDefinition straightRoad(ArenaDefinition base) {
        var floor=new ArenaDefinition.BoxPart("ram-road",new ArenaDefinition.Vec3(150,-.5f,150),new ArenaDefinition.Vec3(600,1,600),"concrete",true);
        var nodes=List.of(new ArenaDefinition.NavNode(0,new ArenaDefinition.Vec3(110,0,60),"road-ground"),
                new ArenaDefinition.NavNode(1,new ArenaDefinition.Vec3(110,0,120),"road-ground"));
        return new ArenaDefinition(base.schemaVersion(),base.id(),base.metadata(),base.bounds(),List.of(floor),List.of(),base.spawns(),
                List.of(),List.of(),nodes,List.of(new ArenaDefinition.NavEdge("ram-lane",0,1,25,20,ArenaDefinition.Transition.ROAD,"",true)),
                List.of(new ArenaDefinition.Surface("road-ground","ram-road",0,1)),List.of(),List.of(),List.of(),List.of(),List.of(),base.bosses());
    }
}

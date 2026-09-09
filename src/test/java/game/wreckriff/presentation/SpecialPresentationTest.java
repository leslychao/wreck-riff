package game.wreckriff.presentation;

import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.VertexBuffer;
import game.wreckriff.combat.CombatRules;
import game.wreckriff.combat.CombatSystem.SpecialBombView;
import game.wreckriff.config.Configs;
import game.wreckriff.config.VehicleProfile;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.simulation.GameEvent;
import game.wreckriff.simulation.MatchSession;
import game.wreckriff.simulation.VehicleState;
import game.wreckriff.simulation.WorldQuery;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpecialPresentationTest {
    @Test void warmupFollowsInterpolatedModelAndNeverPretendsToBeTheImpact() {
        Node scene=new Node(),model=new Node();World world=new World();MatchSession session=session();
        model.setLocalTranslation(18,4,-7);model.setLocalRotation(new Quaternion().fromAngleAxis((float)Math.PI/2,Vector3f.UNIT_Y));
        try(var presentation=new SpecialPresentation(PresentationTestAssets.shared(),scene,world)) {
            presentation.bindModels(Map.of(0,model));
            VehicleState rivet=session.vehicle(0);rivet.specialPhase=VehicleState.SpecialPhase.PULSE_WINDUP;rivet.specialTicks=36;
            presentation.update(session,List.of(),.75f);
            Mesh signals=signals(scene);assertTrue(signals.getVertexCount()>0);
            var points=signals.getFloatBuffer(VertexBuffer.Type.Position);
            for(int vertex=0;vertex<signals.getVertexCount();vertex++) {
                Vector3f point=point(points,vertex);
                assertTrue(point.x>20.2f&&point.x<21.0f,"Charging grille stays on the rotated front intake");
                assertTrue(point.z>-8.0f&&point.z<-6.0f,"Bound render pose, not the authoritative origin, owns the emitter position");
            }
            rivet.clearSpecial();presentation.update(session,List.of(),0);
            assertEquals(0,signals.getVertexCount(),"A state ending cannot invent a successful pulse hit");
        }
    }

    @Test void pulseIsDeduplicatedAgesOnlyWithSimulationAndMissStopsAtTheRecordedWorldObstruction() {
        Node scene=new Node();World world=new World();MatchSession session=session();
        Vector3f from=new Vector3f(0,1,2),to=new Vector3f(0,1,14);
        world.obstruction=new WorldQuery.Hit(-1,new Vector3f(0,1,6),Vector3f.UNIT_Z.negate(),1f/3);
        GameEvent pulse=new GameEvent(GameEvent.Type.SPECIAL_HIT,14,-1,0,to,"pulse",60,from,Vector3f.UNIT_Z).inSession(session.sessionId);
        try(var presentation=new SpecialPresentation(PresentationTestAssets.shared(),scene,world)) {
            presentation.accept(List.of(pulse,pulse));presentation.update(session,List.of(),0);
            Mesh mesh=signals(scene);assertTrue(mesh.getVertexCount()>0);assertEquals(24,mesh.getVertexCount(),"One miss beam has no target-impact ring");
            var positions=mesh.getFloatBuffer(VertexBuffer.Type.Position);
            for(int vertex=0;vertex<mesh.getVertexCount();vertex++)assertTrue(positions.get(vertex*3+2)<=6.0001f,"A range-only miss must not cross a wall");
            float[] beforePoints=floats(mesh,VertexBuffer.Type.Position),beforeColors=floats(mesh,VertexBuffer.Type.Color);
            for(int frame=0;frame<100;frame++)presentation.update(session,List.of(),frame%2==0?0:.99f);
            assertArrayEquals(beforePoints,floats(mesh,VertexBuffer.Type.Position));assertArrayEquals(beforeColors,floats(mesh,VertexBuffer.Type.Color));
            session.tick=12;presentation.update(session,List.of(),0);
            assertTrue(floats(mesh,VertexBuffer.Type.Color)[3]<beforeColors[3],"Only authoritative ticks fade the pulse");
            session.tick=24;presentation.update(session,List.of(),0);assertEquals(0,mesh.getVertexCount());
            presentation.accept(List.of(pulse));presentation.update(session,List.of(),0);
            assertEquals(0,mesh.getVertexCount(),"Replayed delivery after expiry must not restart a pulse");
        }
    }

    @Test void grinderSearchHasNoSparksAndContactNeedsBothLiveTargetAndRealNativeContact() {
        Node scene=new Node();World world=new World();MatchSession session=session();
        VehicleState grinder=session.vehicle(1);grinder.specialPhase=VehicleState.SpecialPhase.GRINDER_SEARCH;grinder.specialTicks=240;
        try(var presentation=new SpecialPresentation(PresentationTestAssets.shared(),scene,world)) {
            presentation.update(session,List.of(),0);Mesh mesh=signals(scene);int armedVertices=mesh.getVertexCount();assertTrue(armedVertices>0);
            grinder.specialPhase=VehicleState.SpecialPhase.GRINDER_CONTACT;grinder.specialTargetId=0;
            presentation.accept(List.of(new GameEvent(GameEvent.Type.GRAB_STARTED,50,0,1,world.position(0),"grinder",1.5f)));
            presentation.update(session,List.of(),0);assertEquals(armedVertices,mesh.getVertexCount(),"A grab event or phase alone cannot fabricate sparks");
            world.touching=true;presentation.update(session,List.of(),0);assertTrue(mesh.getVertexCount()>armedVertices);
            float[] points=floats(mesh,VertexBuffer.Type.Position),colors=floats(mesh,VertexBuffer.Type.Color);
            for(int frame=0;frame<80;frame++)presentation.update(session,List.of(),0);
            assertArrayEquals(points,floats(mesh,VertexBuffer.Type.Position));assertArrayEquals(colors,floats(mesh,VertexBuffer.Type.Color));
            world.touching=false;presentation.update(session,List.of(),0);assertEquals(armedVertices,mesh.getVertexCount(),"Losing real contact stops sparks immediately");
            world.touching=true;session.vehicle(0).hp=0;presentation.update(session,List.of(),0);assertEquals(armedVertices,mesh.getVertexCount());
            grinder.hp=0;presentation.update(session,List.of(),0);assertEquals(0,mesh.getVertexCount(),"A destroyed truck has no lingering running machinery signal");
        }
    }

    @Test void dashJetsPointOppositeTheCapturedDirectionAndStopWhenDashEnds() {
        Node scene=new Node();World world=new World();MatchSession session=session();
        VehicleState spark=session.vehicle(2);spark.specialPhase=VehicleState.SpecialPhase.DASH;spark.specialTicks=42;spark.dashDirection.set(Vector3f.UNIT_X);
        try(var presentation=new SpecialPresentation(PresentationTestAssets.shared(),scene,world)) {
            presentation.update(session,List.of(),0);Mesh mesh=signals(scene);assertTrue(mesh.getVertexCount()>0);
            var positions=mesh.getFloatBuffer(VertexBuffer.Type.Position);float carX=world.position(2).x;
            for(int vertex=0;vertex<mesh.getVertexCount();vertex++)assertTrue(positions.get(vertex*3)<carX-.8f,"Rightward dodge exhaust is on the left flank");
            assertEquals(Vector3f.UNIT_X,spark.dashDirection,"Presentation must not negate or normalize authoritative dash state in place");
            world.dashActive=false;presentation.update(session,List.of(),0);
            assertEquals(0,mesh.getVertexCount(),"A wall can stop the native dash before the special timer expires");
            world.dashActive=true;
            spark.clearSpecial();presentation.update(session,List.of(),0);assertEquals(0,mesh.getVertexCount());
        }
    }

    @Test void existingRollersTurnOppositelyDuringTheSpecialAndStopOnPauseCancellationAndDeath() {
        Node scene=new Node(),model=new Node("grinder"),left=new Node("grinder-roller-left"),right=new Node("grinder-roller-right");
        model.attachChild(left);model.attachChild(right);scene.attachChild(model);
        World world=new World();MatchSession session=session();VehicleState grinder=session.vehicle(1);
        grinder.specialPhase=VehicleState.SpecialPhase.GRINDER_WINDUP;grinder.specialTicks=72;
        try(var presentation=new SpecialPresentation(PresentationTestAssets.shared(),scene,world)) {
            presentation.bindModels(Map.of(1,model));presentation.update(session,List.of(),0);
            grinder.specialTicks=36;session.tick=36;presentation.update(session,List.of(),0);
            Quaternion turning=left.getLocalRotation().clone();assertNotEquals(Quaternion.IDENTITY,turning);
            assertEquals(-turning.getX(),right.getLocalRotation().getX(),.0001f,"Existing shafts counter-rotate without duplicating model geometry");
            assertEquals(turning.getW(),right.getLocalRotation().getW(),.0001f);
            for(int frame=0;frame<100;frame++)presentation.update(session,List.of(),0);
            assertEquals(turning,left.getLocalRotation());assertEquals(2,model.getQuantity());
            grinder.clearSpecial();session.tick=37;presentation.update(session,List.of(),0);assertEquals(turning,left.getLocalRotation());
            grinder.specialPhase=VehicleState.SpecialPhase.GRINDER_SEARCH;session.tick=38;presentation.update(session,List.of(),0);
            Quaternion resumed=left.getLocalRotation().clone();assertNotEquals(turning,resumed);
            grinder.hp=0;session.tick=1000;presentation.update(session,List.of(),0);assertEquals(resumed,left.getLocalRotation());
        }
        assertEquals(Quaternion.IDENTITY,left.getLocalRotation());assertEquals(Quaternion.IDENTITY,right.getLocalRotation());assertEquals(2,model.getQuantity());
    }

    @Test void bombUsesAuthoritativeSupportAndFuseAndRemainsVisibleAfterItsOwnerDies() {
        Node scene=new Node();World world=new World();MatchSession session=session();
        Vector3f support=new Vector3f(5,8,-4),normal=new Vector3f(0,1,1).normalizeLocal();
        SpecialBombView bomb=new SpecialBombView(91,2,support,normal,84,4);
        try(var presentation=new SpecialPresentation(PresentationTestAssets.shared(),scene,world)) {
            presentation.update(session,List.of(bomb),0);Mesh body=bodies(scene),signals=signals(scene);
            assertTrue(body.getVertexCount()>0);var positions=body.getFloatBuffer(VertexBuffer.Type.Position);
            for(int vertex=0;vertex<body.getVertexCount();vertex++) {
                Vector3f offset=point(positions,vertex).subtract(support);
                assertTrue(offset.dot(normal)>=.034f&&offset.dot(normal)<=.276f,"Bomb canister follows the recorded banked support, never a new ray or road floor");
            }
            float[] points=floats(signals,VertexBuffer.Type.Position),colors=floats(signals,VertexBuffer.Type.Color);
            for(int frame=0;frame<100;frame++)presentation.update(session,List.of(bomb),.9f);
            assertArrayEquals(points,floats(signals,VertexBuffer.Type.Position));assertArrayEquals(colors,floats(signals,VertexBuffer.Type.Color));
            int initialVertices=signals.getVertexCount();session.tick=42;
            presentation.update(session,List.of(new SpecialBombView(91,2,support,normal,42,4)),0);
            assertTrue(signals.getVertexCount()<initialVertices,"Authoritative remaining fuse, not render time, drains the clock segments");
            session.vehicle(2).hp=0;presentation.update(session,List.of(bomb),0);assertTrue(body.getVertexCount()>0,"A live placed bomb cannot become an invisible threat when its owner dies");
            presentation.setFlashIntensity(0);presentation.update(session,List.of(bomb),0);assertTrue(signals.getVertexCount()>0,"Minimum flash preserves a readable threat marker");
            presentation.update(session,List.of(),0);assertEquals(0,body.getVertexCount());assertEquals(0,signals.getVertexCount());
        }
    }

    @Test void worstCaseEffectsHaveTwoBoundedDrawsAndCloseRemovesEverythingWithoutTouchingModels() {
        Node scene=new Node(),model=new Node("owned-elsewhere");scene.attachChild(model);World world=new World();MatchSession session=session();
        var events=new ArrayList<GameEvent>();var bombs=new ArrayList<SpecialBombView>();
        for(int i=0;i<200;i++) {
            events.add(new GameEvent(GameEvent.Type.SPECIAL_HIT,i,1,0,new Vector3f(0,1,8),"pulse",60,new Vector3f(0,1,2),Vector3f.UNIT_Z));
            bombs.add(new SpecialBombView(i,2,new Vector3f(i,0,0),Vector3f.UNIT_Y,84,4));
        }
        SpecialPresentation presentation=new SpecialPresentation(PresentationTestAssets.shared(),scene,world);presentation.bindModels(Map.of(0,model));
        presentation.accept(events);presentation.update(session,bombs,0);
        Node effects=(Node)scene.getChild("special-presentation");assertEquals(2,effects.getQuantity());
        assertEquals(SpecialPresentation.BOMB_LIMIT*72,bodies(scene).getVertexCount(),"Every admitted bomb has a complete canister, with a strict bomb limit");
        assertEquals(SpecialPresentation.PULSE_LIMIT*120+SpecialPresentation.BOMB_LIMIT*504,signals(scene).getVertexCount(),
                "Capacity preserves every admitted bomb marker even with the full pulse backlog");
        assertTrue(signals(scene).getVertexCount()<=32768);assertEquals(0,signals(scene).getVertexCount()%3);
        assertEquals(0,scene.getLocalLightList().size(),"Specials cannot allocate lights or create an audio owner");
        presentation.close();presentation.close();assertEquals(1,scene.getQuantity());assertSame(model,scene.getChild(0));assertEquals(0,effects.getQuantity());
        presentation.accept(events);presentation.update(session,bombs,0);assertEquals(1,scene.getQuantity(),"Closed presentation cannot reattach effects");
    }

    @Test void ownerDeathAndSessionEndCleanPersonalEffectsAndOldSessionEventsAreIgnored() {
        Node scene=new Node();World world=new World();MatchSession session=session();
        GameEvent pulse=new GameEvent(GameEvent.Type.SPECIAL_HIT,19,1,0,new Vector3f(0,1,8),"pulse",60,new Vector3f(0,1,2),Vector3f.UNIT_Z).inSession(session.sessionId);
        try(var presentation=new SpecialPresentation(PresentationTestAssets.shared(),scene,world)) {
            presentation.update(session,List.of(),0);presentation.accept(List.of(pulse));presentation.update(session,List.of(),0);assertTrue(signals(scene).getVertexCount()>0);
            session.vehicle(0).hp=0;presentation.update(session,List.of(),0);assertEquals(0,signals(scene).getVertexCount());
            MatchSession next=session();presentation.update(next,List.of(),0);presentation.accept(List.of(pulse));presentation.update(next,List.of(),0);assertEquals(0,signals(scene).getVertexCount());
            next.vehicle(2).specialPhase=VehicleState.SpecialPhase.DASH;next.vehicle(2).dashDirection.set(Vector3f.UNIT_X);
            var bombs=List.of(new SpecialBombView(11,2,Vector3f.ZERO,Vector3f.UNIT_Y,42,4));
            presentation.update(next,bombs,0);assertTrue(signals(scene).getVertexCount()>0);
            next.outcome=MatchSession.Outcome.VICTORY;presentation.update(next,bombs,0);assertEquals(0,signals(scene).getVertexCount());assertEquals(0,bodies(scene).getVertexCount());
        }
    }

    private static MatchSession session() {return MatchSession.balanced(42,List.of("rivet","grinder","spark"),Configs.load("combat",CombatRules.class));}
    private static Mesh signals(Node scene) {return ((Geometry)((Node)scene.getChild("special-presentation")).getChild("special-signals")).getMesh();}
    private static Mesh bodies(Node scene) {return ((Geometry)((Node)scene.getChild("special-presentation")).getChild("special-bomb-models")).getMesh();}
    private static Vector3f point(FloatBuffer positions,int vertex) {return new Vector3f(positions.get(vertex*3),positions.get(vertex*3+1),positions.get(vertex*3+2));}
    private static float[] floats(Mesh mesh,VertexBuffer.Type type) {
        var data=mesh.getFloatBuffer(type).duplicate().rewind();float[] result=new float[data.remaining()];data.get(result);return result;
    }
    private static final class World implements WorldQuery {
        private final VehicleRules rules=Configs.load("vehicle",VehicleRules.class);
        boolean touching,dashActive=true;
        Hit obstruction;
        public Vector3f position(int id) {return new Vector3f(id*7,1,0);}
        public Vector3f velocity(int id) {return new Vector3f();}
        public Quaternion rotation(int id) {return new Quaternion();}
        public boolean grounded(int id) {return true;}
        public float mass(int id) {return profile(id).mass();}
        public VehicleProfile profile(int id) {return VehicleProfile.player(List.of("rivet","grinder","spark").get(id),rules);}
        public Hit ray(Vector3f from,Vector3f to,int ignored) {return obstruction;}
        public Hit sweep(Vector3f from,Vector3f to,float radius,int ignored,float start,float end) {throw new AssertionError("Presentation cannot move a projectile");}
        public Hit staticSweep(Vector3f from,Vector3f to,float radius) {throw new AssertionError("Bombs must use their authoritative support");}
        public boolean visible(Vector3f from,Vector3f to,int target) {return true;}
        public float distanceToHull(int id,Vector3f point) {return position(id).distance(point);}
        public Vector3f closestHullPoint(int id,Vector3f from) {return from.add(0,0,.02f);}
        public boolean touchingVehicles(int first,int second) {return touching;}
        public boolean dashActive(int id) {return dashActive;}
        public void impulse(int id,Vector3f linear,Vector3f torque,float cap) {throw new AssertionError("Presentation cannot mutate physics");}
    }
}

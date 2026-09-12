package game.wreckriff.presentation;

import com.jme3.math.*;
import com.jme3.scene.*;
import game.wreckriff.ai.BotController.BossActionView;
import game.wreckriff.arena.ArenaDefinition;
import game.wreckriff.simulation.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BossActionPresentationTest {
    @Test void actualTelegraphStartsOnceAndCancelsItsOwnStartOnCharge() {
        MatchSession session=session();World world=new World();List<BossActionPresentation.Signal> signals=new ArrayList<>();
        var action=new AtomicReference<>(view("CRUISE",0,0));
        try(var presentation=new BossActionPresentation(session,()->Optional.of(action.get()),world,signals::add)) {
            session.tick=120;action.set(view("TELEGRAPH",120,240));presentation.update(Map.of());
            assertEquals(1,signals.size());assertSignal(signals.getFirst(),120,true);
            assertEquals(world.position,signals.getFirst().position());
            world.position.set(9,1,6);assertNotEquals(world.position,signals.getFirst().position(),"Signals retain their captured position");
            for(int frame=0;frame<100;frame++)presentation.update(Map.of());
            session.tick=239;presentation.update(Map.of());assertEquals(1,signals.size(),"Render rate and pause cannot replay a start");
            session.tick=240;action.set(view("CHARGE",240,420));presentation.update(Map.of());
            assertEquals(2,signals.size());assertSignal(signals.getLast(),120,false);
            presentation.update(Map.of());assertEquals(2,signals.size());
            session.tick=600;action.set(view("TELEGRAPH",600,720));presentation.update(Map.of());
            assertSignal(signals.getLast(),600,true);
        }
        assertSignal(signals.getLast(),600,false);assertEquals(4,signals.size());
    }

    @Test void restoredActionIsVisibleButSilentAndAnExpiredReplayCannotRestartIt() {
        MatchSession session=session();session.tick=180;World world=new World();
        var action=new AtomicReference<>(view("TELEGRAPH",120,240));List<BossActionPresentation.Signal> signals=new ArrayList<>();
        Node model=model();var lamps=(Geometry)model.getChild("headlights");
        try(var presentation=new BossActionPresentation(session,()->Optional.of(action.get()),world,signals::add)) {
            VehicleVisual.updateBossPhase(model,0,true);var base=color(lamps);
            presentation.update(Map.of(1,model));assertNotEquals(base,color(lamps));assertTrue(signals.isEmpty());
            var paused=color(lamps);
            for(int frame=0;frame<100;frame++) {
                VehicleVisual.updateBossPhase(model,0,true);presentation.update(Map.of(1,model));assertEquals(paused,color(lamps));
            }
            session.tick=240;presentation.update(Map.of(1,model));assertEquals(base,color(lamps));assertTrue(signals.isEmpty());
            session.tick=300;action.set(view("RECOVERY",240,420));presentation.update(Map.of(1,model));
            session.tick=180;action.set(view("TELEGRAPH",120,240));presentation.update(Map.of(1,model));
            assertTrue(signals.isEmpty(),"A repeated view cannot replay a seeded warning");
        }
    }

    @Test void phaseDeathMissingBossAndResultStopTheWarningWithoutQueryingInvalidParticipants() {
        for(int reason=0;reason<4;reason++) {
            MatchSession session=session();AtomicInteger queries=new AtomicInteger();World world=new World();
            var action=new AtomicReference<>(view("CRUISE",0,0));List<BossActionPresentation.Signal> signals=new ArrayList<>();
            try(var presentation=new BossActionPresentation(session,()->{queries.incrementAndGet();return Optional.of(action.get());},world,signals::add)) {
                session.tick=10;action.set(view("TELEGRAPH",10,130));presentation.update(Map.of());int queried=queries.get();
                switch(reason) {
                    case 0 -> session.phase=MatchSession.Phase.RESULT;
                    case 1 -> session.vehicle(1).hp=0;
                    case 2 -> session.bossParticipantId=-1;
                    case 3 -> session.outcome=MatchSession.Outcome.VICTORY;
                }
                presentation.update(Map.of());assertEquals(queried,queries.get());
                assertEquals(2,signals.size());assertSignal(signals.getLast(),10,false);
            }
        }
        MatchSession session=session();session.bossParticipantId=-1;
        try(var presentation=new BossActionPresentation(session,()->{throw new AssertionError("No boss is registered");},new World(),signal->fail())) {
            presentation.update(Map.of());
        }
    }

    @Test void onlyAValidCurrentTelegraphIsPresentedAndItsReplacementCancelsFirst() {
        MatchSession session=session();session.tick=10;World world=new World();
        var action=new AtomicReference<>(Optional.<BossActionView>empty());List<BossActionPresentation.Signal> signals=new ArrayList<>();
        try(var presentation=new BossActionPresentation(session,action::get,world,signals::add)) {
            for(var invalid:List.of(view("CHARGE",10,130),view("RECOVERY",10,130),view("TELEGRAPH",11,130),view("TELEGRAPH",1,10),view("TELEGRAPH",10,10))) {
                action.set(Optional.of(invalid));presentation.update(Map.of());assertTrue(signals.isEmpty());
            }
            action.set(Optional.of(view("TELEGRAPH",10,130)));presentation.update(Map.of());
            session.tick=30;action.set(Optional.of(view("TELEGRAPH",30,150)));presentation.update(Map.of());
            assertEquals(3,signals.size());assertSignal(signals.get(1),10,false);assertSignal(signals.get(2),30,true);
            action.set(Optional.empty());presentation.update(Map.of());assertSignal(signals.getLast(),30,false);
            action.set(Optional.of(view("TELEGRAPH",30,150)));presentation.update(Map.of());assertEquals(4,signals.size());
        }
    }

    @Test void closeRestoresOnlyLampsCancelsOnceAndRetryCanPresentItsOwnFirstNewAction() {
        MatchSession session=session();World world=new World();Node model=model();
        var action=new AtomicReference<>(view("CRUISE",0,0));List<BossActionPresentation.Signal> signals=new ArrayList<>();
        VehicleVisual.updateBossPhase(model,2,true);var base=color((Geometry)model.getChild("headlights"));
        var presentation=new BossActionPresentation(session,()->Optional.of(action.get()),world,signals::add);
        presentation.setGlow(false);session.tick=10;action.set(view("TELEGRAPH",10,130));presentation.update(Map.of(1,model));
        assertEquals(ColorRGBA.Black,((Geometry)model.getChild("headlights")).getMaterial().getParam("GlowColor").getValue());
        presentation.close();presentation.close();presentation.update(Map.of(1,model));
        assertEquals(2,signals.size());assertEquals(base,color((Geometry)model.getChild("headlights")));
        assertEquals(Spatial.CullHint.Inherit,model.getChild("service-core").getLocalCullHint());
        assertEquals(Spatial.CullHint.Always,model.getChild("boss-panels").getLocalCullHint());
        action.set(view("CRUISE",0,0));
        try(var retry=new BossActionPresentation(session(),()->Optional.of(action.get()),world,signals::add)) {
            action.set(view("TELEGRAPH",0,120));retry.update(Map.of());assertSignal(signals.getLast(),0,true);
        }
        assertEquals(4,signals.size());
    }

    private static Node model() {return VehicleVisual.create(PresentationTestAssets.shared(),game.wreckriff.config.VehicleProfile.boss("boss_foreman",game.wreckriff.config.VehicleRules.load()),0);}
    private static MatchSession session() {
        // The view is injected; the authoritative participant slot supplies lifetime/HP without starting an AI or native world.
        MatchSession session=new MatchSession(42,360);session.bossParticipantId=1;session.phase=MatchSession.Phase.BOSS_COMBAT;return session;
    }
    private static BossActionView view(String phase,long began,long until) {return new BossActionView(phase,1,began,until,new ArenaDefinition.Vec3(20,1,20));}
    private static ColorRGBA color(Geometry lamps) {return ((ColorRGBA)lamps.getMaterial().getParam("Color").getValue()).clone();}
    private static void assertSignal(BossActionPresentation.Signal signal,long began,boolean active) {assertEquals(1,signal.subjectId());assertEquals(began,signal.beganTick());assertEquals(active,signal.active());}
    private static final class World implements WorldQuery {
        final Vector3f position=new Vector3f(3,1,4);
        public Vector3f position(int id) {assertEquals(1,id);return position;}
        public Vector3f velocity(int id) {throw new AssertionError();}
        public Quaternion rotation(int id) {throw new AssertionError();}
        public boolean grounded(int id) {throw new AssertionError();}
        public float mass(int id) {throw new AssertionError();}
        public Hit ray(Vector3f from,Vector3f to,int ignored) {throw new AssertionError();}
        public Hit sweep(Vector3f from,Vector3f to,float radius,int ignored,float start,float end) {throw new AssertionError();}
        public Hit staticSweep(Vector3f from,Vector3f to,float radius) {throw new AssertionError();}
        public boolean visible(Vector3f from,Vector3f to,int target) {throw new AssertionError();}
        public float distanceToHull(int id,Vector3f point) {throw new AssertionError();}
        public Vector3f closestHullPoint(int id,Vector3f from) {throw new AssertionError();}
        public void impulse(int id,Vector3f linear,Vector3f torque,float cap) {throw new AssertionError("Presentation cannot mutate physics");}
    }
}

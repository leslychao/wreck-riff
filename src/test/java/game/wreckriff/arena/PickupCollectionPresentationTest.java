package game.wreckriff.arena;

import com.jme3.math.Vector3f;
import game.wreckriff.combat.WeaponType;
import game.wreckriff.presentation.PickupStyle;
import game.wreckriff.simulation.*;
import game.wreckriff.ui.PickupFeedback;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Authoritative grants through the actual event-to-HUD path; TestWorld does not certify native collision. */
class PickupCollectionPresentationTest {
    @Test void everyTypeReportsExactSocketSessionRecipientAndAcceptedAmountWithoutSelectingOrFiring() {
        ArenaDefinition arena=ArenaDefinition.load();
        for(var style:PickupStyle.values()) {
            MatchSession session=new MatchSession(42,360);ArenaSystems systems=new ArenaSystems(session,arena);TestWorld world=new TestWorld();
            var pickup=pickup(arena,style.type());VehicleState player=session.vehicle(0);player.selectedWeapon=WeaponType.POWER;
            player.machineGunCooldown=9;player.weapon(WeaponType.POWER).cooldownTicks=17;leaveRoomForOne(player,style);
            Map<WeaponType,Integer> before=new EnumMap<>(WeaponType.class);
            for(var weapon:WeaponType.values())before.put(weapon,player.weapon(weapon).ammo);
            world.positions[0]=pickup.position().vector().add(0,.8f,0);systems.collectPickups(world);systems.collectPickups(world);
            var events=systems.drainEvents();assertEquals(1,events.size(),style.name());GameEvent event=events.getFirst();
            assertEquals(GameEvent.Type.PICKUP,event.type());assertEquals(session.sessionId,event.sessionId());
            assertEquals(pickup.id(),event.objectId());assertEquals(style.kind(),event.kind());
            assertEquals(pickup.position().vector(),event.position());assertEquals(0,event.subjectId());assertEquals(0,event.sourceId());
            assertEquals(1,event.value(),style+" shows the actual accepted resource, not nominal package size");
            assertEquals(WeaponType.POWER,player.selectedWeapon);assertEquals(9,player.machineGunCooldown);
            assertEquals(17,player.weapon(WeaponType.POWER).cooldownTicks);
            for(var weapon:WeaponType.values())assertEquals(before.get(weapon)+(style.weapon()==weapon?1:0),player.weapon(weapon).ammo);
            PickupFeedback feedback=new PickupFeedback(session.sessionId,0);feedback.accept(events,session.tick);feedback.accept(events,session.tick);
            assertEquals(style.receipt(1),feedback.text(session.tick));assertFalse(systems.active(pickup.id()));
            assertTrue(systems.drainEvents().isEmpty());
        }
    }

    @Test void fullResourcesLeaveAllEightItemsAvailableWithNoSuccessReceipt() {
        ArenaDefinition arena=ArenaDefinition.load();
        for(var style:PickupStyle.values()) {
            MatchSession session=new MatchSession(42,360);ArenaSystems systems=new ArenaSystems(session,arena);TestWorld world=new TestWorld();
            var pickup=pickup(arena,style.type());VehicleState player=session.vehicle(0);
            if(style.weapon()!=null)player.weapon(style.weapon()).ammo=player.weapon(style.weapon()).maximumAmmo;
            player.hp=player.maximumHp;player.turbo=100;
            world.positions[0]=pickup.position().vector().add(0,.8f,0);systems.collectPickups(world);
            var events=systems.drainEvents();assertTrue(events.isEmpty(),style.name());assertTrue(systems.active(pickup.id()));
            PickupFeedback feedback=new PickupFeedback(session.sessionId,0);feedback.accept(events,session.tick);
            assertEquals("",feedback.text(session.tick));assertTrue(feedback.highlighted(session.tick).isEmpty());
        }
    }

    @Test void tiedDriversReceiveOneGrantWithStableWinnerAndNoBotReceiptInOwnHud() {
        ArenaDefinition arena=ArenaDefinition.load();
        for(var style:PickupStyle.values())for(boolean playerFull:new boolean[]{false,true}) {
            var pickup=pickup(arena,style.type());
            MatchSession session=new MatchSession(42,360);ArenaSystems systems=new ArenaSystems(session,arena);TestWorld world=new TestWorld();
            for(int id=0;id<2;id++) {
                leaveRoomForOne(session.vehicle(id),style);
                world.positions[id]=pickup.position().vector().add(0,.8f,0);
            }
            if(playerFull) {
                var player=session.vehicle(0);
                if(style.weapon()!=null)player.weapon(style.weapon()).ammo++;
                else if(style==PickupStyle.REPAIR)player.hp++;
                else player.turbo++;
            }
            int winner=playerFull?1:0;float[] before={resource(session.vehicle(0),style),resource(session.vehicle(1),style)};
            systems.collectPickups(world);session.tick++;systems.collectPickups(world);
            var events=systems.drainEvents();assertEquals(1,events.size());assertEquals(winner,events.getFirst().subjectId());
            assertEquals(winner,events.getFirst().sourceId());assertEquals(pickup.id(),events.getFirst().objectId());
            assertEquals(style.kind(),events.getFirst().kind());assertEquals(1,events.getFirst().value());
            for(int id=0;id<2;id++)assertEquals(before[id]+(winner==id?1:0),resource(session.vehicle(id),style),style.name());
            PickupFeedback feedback=new PickupFeedback(session.sessionId,0);feedback.accept(events,session.tick);
            assertEquals(playerFull?"":style.receipt(1),feedback.text(session.tick));assertFalse(systems.active(pickup.id()));
        }
    }

    @Test void surfaceDistanceLineOfSightAndEligibilityRemainAuthoritativeForAnimatedItems() {
        ArenaDefinition arena=ArenaDefinition.load();var pickup=arena.pickups().stream()
                .filter(p->p.type()==ArenaDefinition.PickupType.REPAIR&&p.position().y()>4).findFirst().orElseThrow();
        for(String blocked:List.of("wrong-floor","wall","outside-radius","dead","protected")) {
            MatchSession session=new MatchSession(42,360);ArenaSystems systems=new ArenaSystems(session,arena);TestWorld world=new TestWorld();
            session.vehicle(0).hp=session.vehicle(0).maximumHp-1;world.positions[0]=pickup.position().vector().add(0,.8f,0);
            switch(blocked) {
                case "wrong-floor" -> world.positions[0].y=.8f;
                case "wall" -> world.wall=true;
                case "outside-radius" -> world.positions[0].x+=2.01f;
                case "dead" -> session.vehicle(0).hp=0;
                case "protected" -> session.vehicle(0).protectionTicks=1;
            }
            systems.collectPickups(world);assertTrue(systems.active(pickup.id()),blocked);assertTrue(systems.drainEvents().isEmpty(),blocked);
        }
        MatchSession session=new MatchSession(42,360);ArenaSystems systems=new ArenaSystems(session,arena);TestWorld world=new TestWorld();
        session.vehicle(0).hp=session.vehicle(0).maximumHp-1;world.positions[0]=pickup.position().vector().add(2,.8f,0);
        systems.collectPickups(world);assertFalse(systems.active(pickup.id()),"The existing two-metre horizontal collection boundary is retained");
        assertEquals(1,systems.drainEvents().getFirst().value());
    }

    @Test void checkpointRestoresRemainingCooldownAndRespawnDoesNotEmitCollection() {
        ArenaDefinition arena=ArenaDefinition.load();var pickup=pickup(arena,ArenaDefinition.PickupType.CANNON_AMMO);
        MatchSession old=new MatchSession(42,360);ArenaSystems original=new ArenaSystems(old,arena);TestWorld world=new TestWorld();
        old.vehicle(0).weapon(WeaponType.CANNON).ammo=0;world.positions[0]=pickup.position().vector().add(0,.8f,0);
        original.collectPickups(world);var event=original.drainEvents().getFirst();old.tick=100;
        var saved=original.snapshot();assertEquals(pickup.respawnTicks()-100,saved.pickups().get(pickup.id()).respawnTicks());
        MatchSession restored=new MatchSession(42,360);restored.tick=500;ArenaSystems systems=new ArenaSystems(restored,arena);
        systems.restore(saved,null,new NavGraph(arena));assertTrue(systems.drainEvents().isEmpty());
        PickupFeedback feedback=new PickupFeedback(restored.sessionId,0);feedback.accept(List.of(event),restored.tick);
        assertEquals("",feedback.text(restored.tick));assertFalse(systems.active(pickup.id()));
        restored.tick+=pickup.respawnTicks()-101;assertFalse(systems.active(pickup.id()));
        restored.tick++;assertTrue(systems.active(pickup.id()));assertTrue(systems.drainEvents().isEmpty());
        assertEquals("",feedback.text(restored.tick));
    }

    private static ArenaDefinition.Pickup pickup(ArenaDefinition arena,ArenaDefinition.PickupType type) {
        return arena.pickups().stream().filter(p->p.type()==type).findFirst().orElseThrow();
    }
    private static void leaveRoomForOne(VehicleState state,PickupStyle style) {
        if(style.weapon()!=null)state.weapon(style.weapon()).ammo=state.weapon(style.weapon()).maximumAmmo-1;
        else if(style==PickupStyle.REPAIR)state.hp=state.maximumHp-1;
        else state.turbo=99;
    }
    private static float resource(VehicleState state,PickupStyle style) {
        return style.weapon()!=null?state.weapon(style.weapon()).ammo:style==PickupStyle.REPAIR?state.hp:state.turbo;
    }
}

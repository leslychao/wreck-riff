package game.wreckriff.ui;

import com.jme3.math.Vector3f;
import game.wreckriff.arena.ArenaDefinition.PickupType;
import game.wreckriff.combat.WeaponType;
import game.wreckriff.presentation.PickupStyle;
import game.wreckriff.simulation.GameEvent;
import game.wreckriff.simulation.MatchSession;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PickupFeedbackTest {
    @Test void everyPickupSharesOneModelRoadSymbolAndReceiptIdentity() {
        assertEquals(PickupType.values().length,PickupStyle.values().length);
        Set<String> paths=new HashSet<>(),kinds=new HashSet<>();Set<VectorIcons.Icon> symbols=new HashSet<>();
        for(var style:PickupStyle.values()) {
            assertSame(style,PickupStyle.of(style.type()));assertSame(style,PickupStyle.ofKind(style.kind()));
            assertTrue(paths.add(style.model()));assertTrue(kinds.add(style.kind()));assertTrue(symbols.add(style.icon()));
            assertTrue(style.model().matches("models/pickups/[a-z]+\\.j3o"));
            assertEquals(1,style.color().a);assertNotSame(style.color(),style.color(),"Callers cannot mutate the shared palette");
            if(style.weapon()!=null)assertEquals(VectorIcons.weapon(style.weapon()),style.icon());
        }
        assertThrows(IllegalArgumentException.class,()->PickupStyle.ofKind("cannon"));
        assertEquals("Cannon +1",PickupStyle.CANNON.receipt(1));
        assertEquals("Repair +12.5 HP",PickupStyle.REPAIR.receipt(12.5f));
        assertEquals("Turbo +1%",PickupStyle.TURBO.receipt(1));
    }

    @Test void actualPartialGrantAppearsOnceForPointFourAndOnePointTwoSeconds() {
        MatchSession session=new MatchSession(42,360);PickupFeedback feedback=new PickupFeedback(session.sessionId,0);
        var pickup=event(session.sessionId,1,0,"cannon-ammo",1);
        assertEquals("",feedback.text(600));assertTrue(feedback.highlighted(600).isEmpty());
        feedback.accept(List.of(pickup),600);feedback.accept(List.of(pickup),610);
        assertEquals("Cannon +1",feedback.text(600));assertEquals(Set.of(WeaponType.CANNON),feedback.highlighted(647));
        assertTrue(feedback.highlighted(648).isEmpty(),"Highlight expires at exactly 0.4 seconds");
        assertEquals("Cannon +1",feedback.text(743));assertEquals("",feedback.text(744),"Receipt expires at exactly 1.2 seconds");
        feedback.accept(List.of(pickup),800);assertEquals("",feedback.text(800),"Expired receipts do not make old events new");
    }

    @Test void fixedTickKeepsReceiptsFrozenDuringPause() {
        UUID id=UUID.randomUUID();PickupFeedback feedback=new PickupFeedback(id,0);
        feedback.accept(List.of(event(id,1,0,"power-ammo",2)),120);
        for(int frame=0;frame<300;frame++) {
            assertEquals("Power +2",feedback.text(130));assertEquals(Set.of(WeaponType.POWER),feedback.highlighted(130));
        }
    }

    @Test void foreignSessionBotNonPickupAndFailedGrantCannotCreateOwnHudFeedback() {
        UUID id=UUID.randomUUID();PickupFeedback feedback=new PickupFeedback(id,0);
        feedback.accept(List.of(event(UUID.randomUUID(),1,0,"cannon-ammo",1),event(id,2,1,"cannon-ammo",1),
                event(id,3,0,"cannon-ammo",0),event(id,4,0,"repair",-1),
                new GameEvent(GameEvent.Type.DAMAGE,5,0,1,Vector3f.ZERO,"power",20).inSession(id),
                new GameEvent(GameEvent.Type.PICKUP,6,0,0,Vector3f.ZERO,"cannon-ammo",1)),0);
        assertEquals("",feedback.text(0));assertTrue(feedback.highlighted(0).isEmpty());
        feedback.accept(List.of(event(id,1,0,"cannon-ammo",1)),1);
        assertEquals("Cannon +1",feedback.text(1),"Foreign event IDs do not poison this session's dedupe");
    }

    @Test void quickGrantsAccumulateActualAmountsButKeepAtMostThreeRecentMessages() {
        UUID id=UUID.randomUUID();PickupFeedback feedback=new PickupFeedback(id,0);
        feedback.accept(List.of(event(id,1,0,"homing-ammo",3),event(id,2,0,"power-ammo",2),
                event(id,3,0,"mine-ammo",1),event(id,4,0,"cannon-ammo",1)),0);
        assertEquals("Power +2  |  Mine +1  |  Cannon +1",feedback.text(0));
        feedback.accept(List.of(event(id,5,0,"power-ammo",1)),12);
        assertEquals("Mine +1  |  Cannon +1  |  Power +3",feedback.text(12));
        assertEquals("Power +3",feedback.text(144));
        assertEquals("",feedback.text(156));
        feedback.accept(List.of(event(id,6,0,"power-ammo",1)),157);
        assertEquals("Power +1",feedback.text(157),"An expired total does not leak into the next receipt");
    }

    @Test void freshCheckpointPresentationHasNoSuccessUntilANewEventArrives() {
        MatchSession session=new MatchSession(42,360);session.vehicle(0).weapon(WeaponType.CANNON).ammo=8;
        PickupFeedback restored=new PickupFeedback(session.sessionId,0);
        assertEquals("",restored.text(12000));assertTrue(restored.highlighted(12000).isEmpty());
        assertEquals(8,session.vehicle(0).weapon(WeaponType.CANNON).ammo);
        assertEquals(WeaponType.HOMING,session.vehicle(0).selectedWeapon);
    }

    private static GameEvent event(UUID session,long id,int recipient,String kind,float amount) {
        return new GameEvent(GameEvent.Type.PICKUP,id,recipient,recipient,Vector3f.ZERO,kind,amount)
                .forObject("socket-"+id).inSession(session);
    }
}

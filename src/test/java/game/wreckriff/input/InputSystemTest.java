package game.wreckriff.input;

import com.jme3.input.*;
import com.jme3.input.event.*;
import game.wreckriff.config.SettingsStore;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

class InputSystemTest {
    @Test void firstFreshAttacksAfterStartOrResumeNeedNoUnrelatedRelease() {
        try(InputSystem input=input()) {
            input.clear(); input.setGameplay(true);
            input.onMouseButtonEvent(mouse(0,true)); input.onMouseButtonEvent(mouse(1,true));
            input.onKeyEvent(key(KeyInput.KEY_F,true));
            VehicleCommand command=input.consume();
            assertTrue(command.machineGun()); assertTrue(command.selectedWeapon()); assertTrue(command.special());
            assertFalse(input.consume().special(),"A pulse edge is consumed exactly once");
        }
    }
    @Test void attacksHeldAcrossResumeStaySuppressedIndividuallyUntilReleased() {
        try(InputSystem input=input()) {
            input.setGameplay(true); input.onMouseButtonEvent(mouse(0,true));input.onKeyEvent(key(KeyInput.KEY_F,true));
            input.clear(); input.setGameplay(false); input.clear(); input.setGameplay(true);
            // Repeated screen transitions preserve the latch even though clear already emptied held state.
            input.onMouseButtonEvent(mouse(0,true)); input.onKeyEvent(key(KeyInput.KEY_F,true));
            input.onMouseButtonEvent(mouse(1,true));
            VehicleCommand command=input.consume();
            assertFalse(command.machineGun());assertFalse(command.special());assertTrue(command.selectedWeapon());
            input.onKeyEvent(key(KeyInput.KEY_W,false));
            assertFalse(input.consume().machineGun(),"Unrelated key release must not re-arm old mouse hold");
            input.onMouseButtonEvent(mouse(0,false)); input.onKeyEvent(key(KeyInput.KEY_F,false));
            input.onMouseButtonEvent(mouse(0,true)); input.onKeyEvent(key(KeyInput.KEY_F,true));
            VehicleCommand fresh=input.consume();assertTrue(fresh.machineGun());assertTrue(fresh.special());
            assertFalse(input.consume().special());
        }
    }
    @Test void mouseClickThatActivatesResumeCannotBecomeMachineGunFire() {
        try(InputSystem input=input()) {
            input.setGameplay(false);
            input.onUi(action->{if(action.equals("click")){input.clear();input.setGameplay(true);}});
            input.onMouseButtonEvent(mouse(0,true));assertFalse(input.consume().machineGun());
            input.onMouseButtonEvent(mouse(0,false));input.onMouseButtonEvent(mouse(0,true));
            assertTrue(input.consume().machineGun());
        }
    }
    @Test void edgeSurvivesRenderFramesWithoutTicksButDoesNotRepeat() {
        try(InputSystem input=input()) {
            input.setGameplay(true);input.onKeyEvent(key(KeyInput.KEY_E,true));input.onKeyEvent(key(KeyInput.KEY_E,false));
            for(int frame=0;frame<8;frame++){input.beginInput();input.endInput();}
            assertEquals(1,input.consume().weaponDelta()); assertEquals(0,input.consume().weaponDelta());
        }
    }
    @Test void deadZoneRenormalizesRemainingTravelAndRejectsNan() {
        assertEquals(0,InputSystem.deadZone(.149f,.15f));assertEquals(0,InputSystem.deadZone(-.15f,.15f));
        assertEquals(1,InputSystem.deadZone(1,.15f),.00001f);assertEquals(-1,InputSystem.deadZone(-1,.15f),.00001f);
        assertEquals(.5f,InputSystem.deadZone(.575f,.15f),.00001f);assertEquals(0,InputSystem.deadZone(Float.NaN,.15f));
    }
    private static InputSystem input() {
        return new InputSystem(new InputManager(device(MouseInput.class),device(KeyInput.class),null,null),SettingsStore.Settings::new);
    }
    private static KeyInputEvent key(int code,boolean down){return new KeyInputEvent(code,'\0',down,false);}
    private static MouseButtonEvent mouse(int button,boolean down){return new MouseButtonEvent(button,down,0,0);}
    private static <T> T device(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)-> {
            if(method.getReturnType()==boolean.class)return true;
            if(method.getReturnType()==int.class)return 0;
            if(method.getReturnType()==long.class)return 0L;
            return null;
        }));
    }
}

package game.wreckriff.input;

import com.google.gson.JsonParser;
import com.jme3.input.*;
import com.jme3.input.event.KeyInputEvent;
import game.wreckriff.combat.AbilityId;
import game.wreckriff.config.Configs;
import game.wreckriff.config.SettingsStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.glfw.GLFWGamepadState;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;

class RosterInputTest {
    @TempDir Path directory;

    @Test void specialUsesOneFreshEdgeAndLosesToShieldButWinsOverFreeze() {
        try(var input=input()) {
            input.setGameplay(true);
            press(input,KeyInput.KEY_C);assertEquals(AbilityId.SPECIAL,input.consume().ability());
            press(input,KeyInput.KEY_C);assertEquals(AbilityId.NONE,input.consume().ability());
            input.onKeyEvent(new KeyInputEvent(KeyInput.KEY_C,'\0',false,false));
            press(input,KeyInput.KEY_Z);press(input,KeyInput.KEY_C);
            assertEquals(AbilityId.SPECIAL,input.consume().ability());
            input.onKeyEvent(new KeyInputEvent(KeyInput.KEY_C,'\0',false,false));
            press(input,KeyInput.KEY_C);press(input,KeyInput.KEY_F);
            assertEquals(AbilityId.SHIELD,input.consume().ability());
        }
    }

    @Test void dpadDownNavigatesMenusAndCannotLeakSpecialAcrossStartOrPause() {
        try(var input=input()) {
            var actions=new java.util.ArrayList<String>();input.onUi(actions::add);
            var pad=GLFWGamepadState.create();pad.axes(GLFW_GAMEPAD_AXIS_LEFT_TRIGGER,-1);pad.axes(GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER,-1);
            pad.buttons(GLFW_GAMEPAD_BUTTON_DPAD_DOWN,(byte)GLFW_PRESS);input.acceptGamepadState(pad);
            assertEquals(java.util.List.of("down"),actions);assertEquals(AbilityId.NONE,input.consume().ability());
            input.clear();input.setGameplay(true);input.acceptGamepadState(pad);
            assertEquals(AbilityId.NONE,input.consume().ability());
            pad.buttons(GLFW_GAMEPAD_BUTTON_DPAD_DOWN,(byte)GLFW_RELEASE);input.acceptGamepadState(pad);
            pad.buttons(GLFW_GAMEPAD_BUTTON_DPAD_DOWN,(byte)GLFW_PRESS);input.acceptGamepadState(pad);
            assertEquals(AbilityId.SPECIAL,input.consume().ability());assertEquals("D-PAD DOWN",input.displayBinding("Special"));
            input.clear();input.setGameplay(false);input.setGameplay(true);input.acceptGamepadState(pad);
            assertEquals(AbilityId.NONE,input.consume().ability());
        }
    }

    @Test void gamepadMigrationDoesNotStealAnExistingDownBinding() throws Exception {
        var old=JsonParser.parseString(Configs.gson().toJson(GamepadProfile.bundled())).getAsJsonObject();
        old.addProperty("schemaVersion",3);old.remove("special");old.addProperty("machineGun",13);
        String original=old.toString();Files.writeString(directory.resolve("gamepad.json"),original);
        var loaded=GamepadProfile.load(directory);
        assertEquals(4,loaded.schemaVersion());assertEquals(13,loaded.machineGun());assertEquals(-1,loaded.special());
        assertTrue(loaded.warning().contains("Special"));assertEquals(original,Files.readString(directory.resolve("gamepad.json.v3.bak")));
        assertEquals(loaded,GamepadProfile.load(directory));
    }

    private static void press(InputSystem input,int code) {input.onKeyEvent(new KeyInputEvent(code,'\0',true,false));}
    private static InputSystem input() {
        var settings=new SettingsStore.Settings();
        return new InputSystem(new InputManager(device(MouseInput.class),device(KeyInput.class),null,null),()->settings);
    }
    private static <T> T device(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)-> {
            if(method.getReturnType()==boolean.class)return true;if(method.getReturnType()==int.class)return 0;
            if(method.getReturnType()==long.class)return 0L;return null;
        }));
    }
}

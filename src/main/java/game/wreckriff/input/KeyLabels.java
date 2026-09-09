package game.wreckriff.input;

import com.jme3.input.KeyInput;
import com.jme3.input.KeyNames;
import java.util.Locale;

/** Physical binding labels stay readable regardless of the active OS keyboard layout. */
public final class KeyLabels {
    private KeyLabels() {}
    public static String name(Integer key) {
        if(key==null||key==0) return "UNBOUND";
        return switch(key) {
            case KeyInput.KEY_LCONTROL -> "L CTRL";
            case KeyInput.KEY_RCONTROL -> "R CTRL";
            case KeyInput.KEY_LSHIFT -> "L SHIFT";
            case KeyInput.KEY_RSHIFT -> "R SHIFT";
            case KeyInput.KEY_LMENU -> "L ALT";
            case KeyInput.KEY_RMENU -> "R ALT";
            default -> {
                String name=key>=0&&key<256?KeyNames.getName(key):null;
                yield name==null?"KEY "+key:name.toUpperCase(Locale.ROOT);
            }
        };
    }
}

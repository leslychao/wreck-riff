package game.wreckriff.input;

import com.jme3.input.KeyInput;
import org.junit.jupiter.api.Test;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.*;

class KeyLabelsTest {
    @Test void labelsRemainUsefulWithRussianLayoutAndModifierKeys() {
        Locale previous=Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ru-RU"));
            assertEquals("L CTRL",KeyLabels.name(KeyInput.KEY_LCONTROL));
            assertEquals("W",KeyLabels.name(KeyInput.KEY_W));
            assertEquals("F",KeyLabels.name(KeyInput.KEY_F));
            assertEquals("UNBOUND",KeyLabels.name(0));
            assertEquals("UNBOUND",KeyLabels.name(null));
            assertEquals("KEY 999",KeyLabels.name(999));
        } finally { Locale.setDefault(previous); }
    }
}

package game.wreckriff;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UiReviewOptionsTest {
    @Test void reviewIsDevOnlyAndExclusive() {
        assertThrows(IllegalArgumentException.class,()->Main.Options.parse(new String[]{"--ui-review"}));
        var options=Main.Options.parse(new String[]{"--dev","--ui-review"});
        assertTrue(options.automated());assertTrue(options.uiReview());assertFalse(options.cameraTour());
        for(String mode:new String[]{"--smoke-seconds=1","--benchmark-seconds=1","--soak-seconds=1","--showcase","--art-showcase","--vehicle-showcase"})
            assertThrows(IllegalArgumentException.class,()->Main.Options.parse(new String[]{"--dev","--ui-review",mode}));
    }
    @Test void dimensionsKeepAspectRatiosAndOldAliases() {
        for(String size:new String[]{"640x480","1280x720","1920x1080","2560x1440","3440x1440","3840x1080","3840x2160"}) {
            var option=Main.Options.parse(new String[]{"--dev","--ui-review","--resolution="+size});
            var dimensions=size.split("x");assertEquals(Integer.parseInt(dimensions[0]),option.resolutionWidth());assertEquals(Integer.parseInt(dimensions[1]),option.resolutionHeight());
        }
        assertEquals(1280,Main.Options.parse(new String[]{"--dev","--resolution=720p"}).resolutionWidth());
        assertEquals(1920,Main.Options.parse(new String[]{"--dev","--resolution=1080p"}).resolutionWidth());
    }
    @Test void windowAndScaleAreExplicitBoundedDiagnosticOverrides() {
        assertThrows(IllegalArgumentException.class,()->Main.Options.parse(new String[]{"--windowed"}));
        assertThrows(IllegalArgumentException.class,()->Main.Options.parse(new String[]{"--ui-scale=1"}));
        for(String value:new String[]{".8","1","1.5"}) {
            var option=Main.Options.parse(new String[]{"--dev","--ui-review","--windowed","--ui-scale="+value});
            assertTrue(option.windowed());assertEquals(Float.parseFloat(value),option.uiScale());
        }
        for(String value:new String[]{".79","1.51","NaN","Infinity","-1","0",""})
            assertThrows(IllegalArgumentException.class,()->Main.Options.parse(new String[]{"--dev","--ui-scale="+value}));
    }
}

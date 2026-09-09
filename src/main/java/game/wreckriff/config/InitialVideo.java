package game.wreckriff.config;

import java.awt.GraphicsEnvironment;
import java.util.*;

/** Chooses the first-run physical video mode before creating the GL context. */
public final class InitialVideo {
    private InitialVideo() {}
    public record Mode(int width,int height) {}

    public static Optional<Mode> choose(Collection<Mode> modes) {
        return modes.stream().filter(m->m.width()>=640&&m.height()>=480&&m.width()<=1920&&m.height()<=1080)
                .max(Comparator.comparingLong(m->(long)m.width()*m.height()));
    }
    public static void apply(SettingsStore.Settings settings) {
        try {
            var display=GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            List<Mode> modes=Arrays.stream(display.getDisplayModes()).map(m->new Mode(m.getWidth(),m.getHeight())).toList();
            choose(modes).ifPresent(mode->{ settings.width=mode.width();settings.height=mode.height();settings.fullscreen=true; });
        } catch(RuntimeException e) {
            // A missing display is not a successful graphical check. Keep the safe window request.
            settings.width=1280;settings.height=720;settings.fullscreen=false;
        }
    }
}

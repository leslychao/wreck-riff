package game.wreckriff.audio;

import com.jme3.audio.AudioRenderer;
import com.jme3.audio.lwjgl.*;
import com.jme3.audio.openal.ALAudioRenderer;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeDesktopSystem;

/** The stock desktop system and renderer, with a corrected LWJGL device-query boundary. */
public final class DesktopAudioSystem extends JmeDesktopSystem {
    @Override public AudioRenderer newAudioRenderer(AppSettings settings) {
        String backend=settings.getAudioRenderer();
        if(backend==null)return null;
        if(!backend.startsWith("LWJGL"))return super.newAudioRenderer(settings);
        initialize(settings);
        return new ALAudioRenderer(new LwjglAL(),new ConnectedQueryAlc(new LwjglALC()),new LwjglEFX());
    }
}

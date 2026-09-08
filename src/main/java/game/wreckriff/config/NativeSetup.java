package game.wreckriff.config;

import com.jme3.system.NativeLibraryLoader;
import java.io.IOException;
import java.nio.file.*;

/** Never require write access alongside the installed launcher. */
public final class NativeSetup {
    private NativeSetup() {}
    public static Path prepare(Path userDirectory) throws IOException {
        Path extraction=userDirectory.resolve("cache/natives");
        try { Files.createDirectories(extraction); }
        catch(IOException | SecurityException e) { extraction=Files.createTempDirectory("wreck-riff-natives-"); }
        NativeLibraryLoader.setCustomExtractionFolder(extraction.toAbsolutePath().toString());
        return extraction;
    }
}

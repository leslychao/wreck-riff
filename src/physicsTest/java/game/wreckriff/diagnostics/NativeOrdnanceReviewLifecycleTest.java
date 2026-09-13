package game.wreckriff.diagnostics;

import com.jme3.app.LegacyApplication;
import com.jme3.system.JmeContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.File;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Forks a failed, non-native context: no display/audio is opened by these lifecycle tests. */
class NativeOrdnanceReviewLifecycleTest {
    @TempDir Path directory;

    @Test void performanceSettingsRequireFullscreen1080pFourSamplesAudioAndNoFrameCap() {
        for(int seconds:new int[]{60,75,90}) {
            var settings=NativeOrdnanceSaturationReview.reviewSettings(seconds);
            assertEquals(1920,settings.getWidth());assertEquals(1080,settings.getHeight());
            assertTrue(settings.isFullscreen());assertFalse(settings.isResizable());assertEquals(4,settings.getSamples());
            assertFalse(settings.isVSync());assertEquals(0,settings.getFrameRate());assertNotNull(settings.getAudioRenderer());
        }
        assertThrows(IllegalArgumentException.class,()->NativeOrdnanceSaturationReview.reviewSettings(59));
        assertThrows(IllegalArgumentException.class,()->NativeOrdnanceSaturationReview.reviewSettings(91));
    }
    @Test void windowDecorationOrCameraSizeMismatchCannotPassAs1080p() {
        assertDoesNotThrow(()->NativeOrdnanceSaturationReview.validatePerformanceFramebuffer(1920,1080,1920,1080));
        assertThrows(IllegalStateException.class,()->NativeOrdnanceSaturationReview.validatePerformanceFramebuffer(1920,1055,1920,1055));
        assertThrows(IllegalStateException.class,()->NativeOrdnanceSaturationReview.validatePerformanceFramebuffer(1920,1080,1920,1055));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void initializationFailureExitsTwoWithoutWaitingForTheRenderThreadEvenIfShutdownFails(boolean shutdownFails) throws Exception {
        Path log=directory.resolve("child.log");
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-cp",forkClasspath(),
                FailureProbe.class.getName(),directory.toString(),Boolean.toString(shutdownFails)).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(8,TimeUnit.SECONDS),"Initialization failure deadlocked the runner");
            assertEquals(2,process.exitValue(),Files.readString(log));
            assertEquals("false",Files.readString(directory.resolve("shutdown-wait.txt")));
            try(var files=Files.list(directory)) {
                var report=files.filter(p->p.getFileName().toString().startsWith("failure-")).findFirst().orElseThrow();
                String json=Files.readString(report);assertTrue(json.contains("INITIALIZING"));assertTrue(json.contains("fixture initialization failure"));
            }
        } finally {if(process.isAlive())process.destroyForcibly().waitFor(5,TimeUnit.SECONDS);}
    }
    private static String forkClasspath() throws Exception {
        var entries=new LinkedHashSet<>(Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator)));
        for(ClassLoader loader=FailureProbe.class.getClassLoader();loader!=null;loader=loader.getParent())
            if(loader instanceof URLClassLoader urls)for(var url:urls.getURLs())if(url.getProtocol().equals("file"))entries.add(Path.of(url.toURI()).toString());
        return String.join(File.pathSeparator,entries);
    }
    public static final class FailureProbe {
        public static void main(String[] args) throws Exception {
            Path directory=Path.of(args[0]);
            var constructor=NativeOrdnanceSaturationReview.class.getDeclaredConstructor(Path.class,String.class,int.class);constructor.setAccessible(true);
            var app=constructor.newInstance(directory,"0".repeat(64),75);
            var context=(JmeContext)Proxy.newProxyInstance(JmeContext.class.getClassLoader(),new Class<?>[]{JmeContext.class},(proxy,method,values)->{
                if(method.getName().equals("destroy")) {
                    boolean wait=(boolean)values[0];Files.writeString(directory.resolve("shutdown-wait.txt"),Boolean.toString(wait));
                    if(wait)Thread.sleep(60_000);
                    if(Boolean.parseBoolean(args[1]))throw new IllegalStateException("fixture context was never initialized");
                }
                return null;
            });
            var field=LegacyApplication.class.getDeclaredField("context");field.setAccessible(true);field.set(app,context);
            app.handleError("fixture initialization failure",new IllegalStateException("fixture initialization failure"));
            throw new AssertionError("Failed application returned without terminating");
        }
    }
}

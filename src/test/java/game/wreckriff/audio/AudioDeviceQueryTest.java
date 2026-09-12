package game.wreckriff.audio;

import com.jme3.audio.AudioStream;
import com.jme3.audio.openal.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.nio.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the pinned renderer's actual query/delete code without creating a native device. */
class AudioDeviceQueryTest {
    @Test void decoderKeepsRunningAfterDisposalAndCleanupStopsItBeforeDestroyingTheDevice() throws Exception {
        AtomicInteger created=new AtomicInteger(),destroyed=new AtomicInteger();
        AtomicBoolean disposed=new AtomicBoolean();CountDownLatch checkedAfterDisposal=new CountDownLatch(1);
        AtomicReference<Throwable> uncaught=new AtomicReference<>();
        ALC backend=(ALC)Proxy.newProxyInstance(ALC.class.getClassLoader(),new Class<?>[]{ALC.class},(proxy,method,args)->{
            switch(method.getName()) {
                case "createALC" -> created.incrementAndGet();
                case "destroyALC" -> destroyed.incrementAndGet();
                case "isCreated" -> {return created.get()>destroyed.get();}
                case "alcIsExtensionPresent" -> {return args[0].equals("ALC_EXT_disconnect");}
                case "alcGetString" -> {return "test-device";}
                case "alcGetInteger" -> {
                    assertEquals(0,((IntBuffer)args[1]).position());assertEquals(1,((IntBuffer)args[1]).limit());
                    ((IntBuffer)args[1]).put(0,1);
                    if(disposed.get())checkedAfterDisposal.countDown();
                }
            }
            return null;
        });
        AtomicInteger sourceIds=new AtomicInteger();
        AL al=(AL)Proxy.newProxyInstance(AL.class.getClassLoader(),new Class<?>[]{AL.class},(proxy,method,args)->{
            if(method.getName().equals("alGenSources"))return sourceIds.incrementAndGet();
            if(method.getReturnType()==int.class)return 0;
            if(method.getReturnType()==String.class)return "test-openal";
            return null;
        });
        ALAudioRenderer renderer=new ALAudioRenderer(al,new ConnectedQueryAlc(backend),null);
        Field field=ALAudioRenderer.class.getDeclaredField("decoderThread");field.setAccessible(true);Thread decoder=(Thread)field.get(renderer);
        decoder.setUncaughtExceptionHandler((thread,failure)->uncaught.set(failure));
        try {
            renderer.initialize();
            AudioStream stream=new AudioStream();stream.setIds(new int[]{11,12,13,14,15});
            renderer.deleteAudioData(stream);disposed.set(true);
            assertTrue(checkedAfterDisposal.await(2,TimeUnit.SECONDS));
            assertTrue(decoder.isAlive());assertNull(uncaught.get());assertNull(stream.getIds());
        } finally {renderer.cleanup();}
        assertFalse(decoder.isAlive());assertNull(uncaught.get());assertEquals(1,created.get());assertEquals(1,destroyed.get());
        renderer.cleanup();assertEquals(1,destroyed.get(),"A completed cleanup does not destroy a second device");
    }

    @Test void disabledAudioDoesNotCreateARenderer() {
        var settings=new com.jme3.system.AppSettings(true);settings.setAudioRenderer(null);
        assertNull(new DesktopAudioSystem().newAudioRenderer(settings));
    }

    @Test void correctedQuerySurvivesRepeatedStreamDisposalWithoutChangingTheRenderer() throws Throwable {
        ALAudioRenderer renderer=renderer(new ConnectedQueryAlc(strictAlc()));
        for(int i=0;i<20;i++) {
            AudioStream stream=new AudioStream();stream.setIds(new int[]{1,2,3,4,5});
            renderer.deleteAudioData(stream);
            assertNull(stream.getIds());
            assertFalse(disconnected(renderer));
            assertEquals(0,scratch(renderer).position());assertEquals(1,scratch(renderer).limit());
        }
    }

    @Test void correctedWindowDoesNotHideRealDisconnectionOrNativeFailures() throws Throwable {
        ALC disconnected=(ALC)Proxy.newProxyInstance(ALC.class.getClassLoader(),new Class<?>[]{ALC.class},(proxy,method,args)->{
            if(method.getName().equals("alcGetInteger"))((IntBuffer)args[1]).put(0,0);
            return null;
        });
        assertTrue(disconnected(renderer(new ConnectedQueryAlc(disconnected))));
        IllegalStateException failure=new IllegalStateException("native device failure");
        ALC broken=(ALC)Proxy.newProxyInstance(ALC.class.getClassLoader(),new Class<?>[]{ALC.class},(proxy,method,args)->{throw failure;});
        assertSame(failure,assertThrows(IllegalStateException.class,()->disconnected(renderer(new ConnectedQueryAlc(broken)))));
    }

    @Test void unrelatedQueriesAndInvalidCardinalityStillReachTheStrictContract() {
        ALC alc=new ConnectedQueryAlc(strictAlc());IntBuffer buffer=IntBuffer.allocate(5);
        assertThrows(AssertionError.class,()->alc.alcGetInteger(ALC.ALC_MAJOR_VERSION,buffer,1));
        assertThrows(AssertionError.class,()->alc.alcGetInteger(ALC.ALC_CONNECTED,buffer,2));
        assertThrows(IllegalArgumentException.class,()->alc.alcGetInteger(ALC.ALC_CONNECTED,IntBuffer.allocate(0),1));
    }

    @Test void pinnedRendererLeavesTheFiveStreamBufferWindowForTheSingleValueDeviceQuery() throws Exception {
        ALC alc=strictAlc();
        ALAudioRenderer renderer=renderer(alc);
        AudioStream stream=new AudioStream();stream.setIds(new int[]{1,2,3,4,5});
        renderer.deleteAudioData(stream);
        assertNull(stream.getIds(),"The legitimate stream disposal completed");
        assertEquals(5,scratch(renderer).limit());
        AssertionError error=assertThrows(AssertionError.class,()->disconnected(renderer));
        assertEquals("ALC query requires position 0 and limit 1, got 0/5",error.getMessage());
    }

    private static ALAudioRenderer renderer(ALC alc)throws Exception {
        AL al=(AL)Proxy.newProxyInstance(AL.class.getClassLoader(),new Class<?>[]{AL.class},(proxy,method,args)->{
            if(method.getReturnType()==int.class)return 0;
            return null;
        });
        ALAudioRenderer renderer=new ALAudioRenderer(al,alc,null);
        Field scratch=ALAudioRenderer.class.getDeclaredField("ib");scratch.setAccessible(true);
        scratch.set(renderer,ByteBuffer.allocateDirect(64*Integer.BYTES).order(ByteOrder.nativeOrder()).asIntBuffer());
        Field supported=ALAudioRenderer.class.getDeclaredField("supportDisconnect");supported.setAccessible(true);supported.setBoolean(renderer,true);
        return renderer;
    }
    private static IntBuffer scratch(ALAudioRenderer renderer)throws Exception {
        Field field=ALAudioRenderer.class.getDeclaredField("ib");field.setAccessible(true);return (IntBuffer)field.get(renderer);
    }
    private static boolean disconnected(ALAudioRenderer renderer)throws Throwable {
        Method method=ALAudioRenderer.class.getDeclaredMethod("isDisconnected");method.setAccessible(true);
        try {return (boolean)method.invoke(renderer);}catch(InvocationTargetException failure){throw failure.getCause();}
    }
    private static ALC strictAlc() {
        return (ALC)Proxy.newProxyInstance(ALC.class.getClassLoader(),new Class<?>[]{ALC.class},(proxy,method,args)->{
            if(method.getName().equals("alcGetInteger")) {
                IntBuffer buffer=(IntBuffer)args[1];int size=(int)args[2];
                if(buffer.position()!=0||buffer.limit()!=size)
                    throw new AssertionError("ALC query requires position 0 and limit "+size+", got "+buffer.position()+"/"+buffer.limit());
                buffer.put(0,1);
            }
            if(method.getReturnType()==boolean.class)return true;
            return null;
        });
    }
}

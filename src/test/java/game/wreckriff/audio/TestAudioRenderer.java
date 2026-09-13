package game.wreckriff.audio;

import com.jme3.audio.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Consumer;

/** State-only test double; renderer/device races are tested separately using the real jME renderer. */
final class TestAudioRenderer {
    private TestAudioRenderer() {}
    static SourcePauseRenderer create() {return create(source->{});}
    static SourcePauseRenderer create(Consumer<AudioSource> played) {
        return (SourcePauseRenderer)Proxy.newProxyInstance(SourcePauseRenderer.class.getClassLoader(),
                new Class<?>[]{SourcePauseRenderer.class},(proxy,method,args)->{
                    if(method.getName().equals("pauseSources")||method.getName().equals("resumeSources")) {
                        boolean resume=method.getName().equals("resumeSources");
                        List<AudioSource> changed=new ArrayList<>();
                        for(Object item:(Collection<?>)args[0]) {
                            AudioSource source=(AudioSource)item;
                            if(source.getStatus()==(resume?AudioSource.Status.Paused:AudioSource.Status.Playing)) {
                                source.setStatus(resume?AudioSource.Status.Playing:AudioSource.Status.Paused);
                                changed.add(source);
                            }
                        }
                        return resume?null:List.copyOf(changed);
                    }
                    if(args!=null&&args.length>0&&args[0] instanceof AudioSource source)switch(method.getName()) {
                        case "playSource" -> {played.accept(source);source.setStatus(AudioSource.Status.Playing);}
                        case "pauseSource" -> source.setStatus(AudioSource.Status.Paused);
                        case "stopSource" -> source.setStatus(AudioSource.Status.Stopped);
                    }
                    if(method.getReturnType()==float.class)return 0f;
                    if(method.getReturnType()==boolean.class)return false;
                    return null;
                });
    }
}

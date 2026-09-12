package game.wreckriff.audio;

import com.jme3.audio.openal.ALC;
import java.nio.IntBuffer;
import java.util.Objects;

/** Boundary correction for the single-value device query in the pinned jME 3.8.1 renderer. */
final class ConnectedQueryAlc implements ALC {
    private final ALC delegate;
    ConnectedQueryAlc(ALC delegate) {this.delegate=Objects.requireNonNull(delegate);}
    @Override public void createALC() {delegate.createALC();}
    @Override public void destroyALC() {delegate.destroyALC();}
    @Override public boolean isCreated() {return delegate.isCreated();}
    @Override public String alcGetString(int parameter) {return delegate.alcGetString(parameter);}
    @Override public boolean alcIsExtensionPresent(String extension) {return delegate.alcIsExtensionPresent(extension);}
    @Override public void alcGetInteger(int parameter,IntBuffer buffer,int size) {
        // jME 3.8.1 reuses this buffer after deleting five stream buffers but omits
        // the single-value window in isDisconnected(). The renderer holds its own
        // thread lock here. Correct only this query; preserve native results/errors.
        if(parameter==ALC_CONNECTED&&size==1)buffer.position(0).limit(1);
        delegate.alcGetInteger(parameter,buffer,size);
    }
    @Override public void alcDevicePauseSOFT() {delegate.alcDevicePauseSOFT();}
    @Override public void alcDeviceResumeSOFT() {delegate.alcDeviceResumeSOFT();}
}

package game.wreckriff.presentation;

import java.io.IOException;
import java.nio.*;
import java.util.*;
import com.jme3.renderer.Caps;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CombatVfxDdsTest {
    @Test void completeBlockMipChainIncludesTheSmallestLevels() throws Exception {
        var image=CombatVfxDds.read(fixture(),4);
        assertEquals(48,image.payloadBytes());
        assertEquals(3,image.levels());
        assertEquals(5_592_432,CombatVfxDds.mipBytes(2048));
    }
    @Test void dxt5AlphaUsesBothEndpointModesAndActualPixelSelectors() throws Exception {
        byte[] bytes=fixture();bytes[128]=(byte)210;bytes[129]=70;
        // Selectors 0,1,2,7 in the first row, little-endian packed three-bit indices.
        long selectors=0L|(1L<<3)|(2L<<6)|(7L<<9);
        for(int i=0;i<6;i++)bytes[130+i]=(byte)(selectors>>>(8*i));
        var image=CombatVfxDds.read(bytes,4);
        assertEquals(210,image.argb(0,0)>>>24);assertEquals(70,image.argb(1,0)>>>24);
        assertEquals(190,image.argb(2,0)>>>24);assertEquals(90,image.argb(3,0)>>>24);
        bytes[128]=20;bytes[129]=120;bytes[130]=(byte)(6|(7<<3));image=CombatVfxDds.read(bytes,4);
        assertEquals(0,image.argb(0,0)>>>24);assertEquals(255,image.argb(1,0)>>>24);
    }
    @Test void rgb565AndFourColorInterpolationAreIndependentOfAlpha() throws Exception {
        byte[] bytes=fixture();bytes[128]=(byte)255;bytes[136]=0;bytes[137]=(byte)0xf8;bytes[138]=(byte)0xe0;bytes[139]=7;
        bytes[140]=(byte)(0|(1<<2)|(2<<4)|(3<<6));var image=CombatVfxDds.read(bytes,4);
        assertEquals(0xffff0000,image.argb(0,0));assertEquals(0xff00ff00,image.argb(1,0));
        assertEquals(0xffaa5500,image.argb(2,0));assertEquals(0xff55aa00,image.argb(3,0));
    }
    @Test void missingMipsDx10PointersAndTrailingOrTruncatedPayloadFailClosed() {
        assertThrows(IOException.class,()->CombatVfxDds.read(Arrays.copyOf(fixture(),175),4));
        assertThrows(IOException.class,()->CombatVfxDds.read(Arrays.copyOf(fixture(),177),4));
        byte[] bad=fixture();ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(28,1);
        assertThrows(IOException.class,()->CombatVfxDds.read(bad,4));
        byte[] dx10=fixture();ByteBuffer.wrap(dx10).order(ByteOrder.LITTLE_ENDIAN).putInt(84,0x30315844);
        assertThrows(IOException.class,()->CombatVfxDds.read(dx10,4));
        assertTrue(assertThrows(IOException.class,()->CombatVfxDds.read("version https://git-lfs.github.com/spec/v1".getBytes(),4)).getMessage().contains("LFS"));
    }
    @Test void unsupportedNativeCompressionFailsClearlyInsteadOfUploadingAnAlternative() {
        assertTrue(assertThrows(IllegalStateException.class,()->CombatVfxAtlas.requireSupported(EnumSet.noneOf(Caps.class))).getMessage().contains("S3TC"));
        assertDoesNotThrow(()->CombatVfxAtlas.requireSupported(EnumSet.of(Caps.TextureCompressionS3TC)));
    }
    private static byte[] fixture() {
        var data=ByteBuffer.allocate(128+48).order(ByteOrder.LITTLE_ENDIAN);
        data.putInt(0,0x20534444);data.putInt(4,124);data.putInt(8,0xA1007);data.putInt(12,4);data.putInt(16,4);
        data.putInt(20,16);data.putInt(28,3);data.putInt(76,32);data.putInt(80,4);data.putInt(84,0x35545844);data.putInt(108,0x401008);
        return data.array();
    }
}

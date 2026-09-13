package game.wreckriff.presentation;

import com.jme3.asset.AssetInfo;
import com.jme3.asset.TextureKey;
import com.jme3.texture.Image;
import com.jme3.texture.plugins.AWTLoader;
import org.junit.jupiter.api.Test;
import javax.imageio.*;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RawPngLoaderTest {
    @Test void everyPackagedPngMatchesPinnedAwtBytesFormatColorSpaceAndBothFlips()throws Exception {
        Path resources=Path.of(Objects.requireNonNull(getClass().getClassLoader().getResource("build-info.properties")).toURI()).getParent();
        List<Path> paths;
        try(var files=Files.walk(resources)){paths=files.filter(p->p.toString().endsWith(".png")).sorted().toList();}
        assertFalse(paths.isEmpty());
        for(Path path:paths)for(boolean flip:List.of(false,true)) {
            try(var before=Files.newInputStream(path);var after=Files.newInputStream(path)) {
                assertEquivalent(new AWTLoader().load(before,flip),new RawPngLoader().load(after,flip),path+" flip="+flip);
            }
        }
    }

    @Test void indexedGreySixteenBitAndRgbaImagesKeepAwtConversionRules()throws Exception {
        for(int type:List.of(BufferedImage.TYPE_BYTE_INDEXED,BufferedImage.TYPE_BYTE_BINARY,
                BufferedImage.TYPE_BYTE_GRAY,BufferedImage.TYPE_USHORT_GRAY,BufferedImage.TYPE_4BYTE_ABGR)) {
            var image=new BufferedImage(13,9,type);
            for(int y=0;y<9;y++)for(int x=0;x<13;x++)image.setRGB(x,y,((x*17)<<24)|((x*19)<<16)|((y*27)<<8)|(x*y*2));
            byte[] png=png(image);
            for(boolean flip:List.of(false,true))assertEquivalent(new AWTLoader().load(new ByteArrayInputStream(png),flip),
                    new RawPngLoader().load(new ByteArrayInputStream(png),flip),"type="+type+" flip="+flip);
        }
    }

    @Test void assetStreamsCloseOnSuccessUnknownInputAndTruncatedPng()throws Exception {
        byte[] valid=png(new BufferedImage(13,9,BufferedImage.TYPE_3BYTE_BGR));
        for(byte[] data:List.of(valid,new byte[]{1,2,3},Arrays.copyOf(valid,valid.length/2))) {
            var input=new ClosingInput(data);
            var info=new AssetInfo(null,new TextureKey("owned.png",true)) {
                @Override public InputStream openStream(){return input;}
            };
            if(data==valid)assertNotNull(new RawPngLoader().load(info));
            else assertThrows(Exception.class,()->new RawPngLoader().load(info));
            assertTrue(input.closed,"AssetInfo owns and closes its source stream on every exit");
        }
    }

    @Test void readerIsDisposedOnSuccessAndDecodeFailure()throws Exception {
        for(boolean fail:List.of(false,true)) {
            var reader=new TrackingReader(fail);
            try(var stream=new MemoryCacheImageInputStream(new ByteArrayInputStream(new byte[0]))) {
                if(fail)assertThrows(IOException.class,()->RawPngLoader.read(reader,stream,false));
                else assertNotNull(RawPngLoader.read(reader,stream,false));
                assertTrue(reader.disposed);
            }
        }
    }

    @Test void rawDestinationAvoidsTheJdkPerRowChannelShuffleAllocation()throws Exception {
        byte[] bytes=png(new BufferedImage(1024,1024,BufferedImage.TYPE_3BYTE_BGR));
        var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        assertTrue(bean.isThreadAllocatedMemorySupported());bean.setThreadAllocatedMemoryEnabled(true);
        new AWTLoader().load(new ByteArrayInputStream(bytes),false);
        new RawPngLoader().load(new ByteArrayInputStream(bytes),false);
        long thread=Thread.currentThread().threadId(),start=bean.getThreadAllocatedBytes(thread);
        Image before=new AWTLoader().load(new ByteArrayInputStream(bytes),false);
        long baseline=bean.getThreadAllocatedBytes(thread)-start;
        start=bean.getThreadAllocatedBytes(thread);
        Image after=new RawPngLoader().load(new ByteArrayInputStream(bytes),false);
        long candidate=bean.getThreadAllocatedBytes(thread)-start;
        assertEquivalent(before,after,"allocation fixture");
        assertTrue(baseline-candidate>=3L*1024*1024,"Expected at least one RGB raster of avoided row allocations: "+baseline+" -> "+candidate);
    }

    private static void assertEquivalent(Image a,Image b,String context) {
        assertNotNull(a,context);assertNotNull(b,context);
        assertEquals(a.getFormat(),b.getFormat(),context);assertEquals(a.getColorSpace(),b.getColorSpace(),context);
        assertEquals(a.getWidth(),b.getWidth(),context);assertEquals(a.getHeight(),b.getHeight(),context);
        var x=a.getData(0);var y=b.getData(0);
        assertEquals(x.position(),y.position(),context);assertEquals(x.limit(),y.limit(),context);assertEquals(x.capacity(),y.capacity(),context);
        assertEquals(-1,x.duplicate().clear().mismatch(y.duplicate().clear()),context);
    }
    private static byte[] png(BufferedImage image)throws IOException {
        var output=new ByteArrayOutputStream();assertTrue(ImageIO.write(image,"png",output));return output.toByteArray();
    }
    private static final class ClosingInput extends ByteArrayInputStream {
        boolean closed;ClosingInput(byte[] bytes){super(bytes);}
        @Override public void close()throws IOException{closed=true;super.close();}
    }
    private static final class TrackingReader extends ImageReader {
        final boolean fail;boolean disposed;
        TrackingReader(boolean fail){super(null);this.fail=fail;}
        @Override public int getNumImages(boolean allowSearch){return 1;}
        @Override public int getWidth(int index){return 2;}
        @Override public int getHeight(int index){return 2;}
        @Override public Iterator<ImageTypeSpecifier> getImageTypes(int index){return List.of(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_BYTE_GRAY)).iterator();}
        @Override public ImageTypeSpecifier getRawImageType(int index){return getImageTypes(index).next();}
        @Override public IIOMetadata getStreamMetadata(){return null;}
        @Override public IIOMetadata getImageMetadata(int index){return null;}
        @Override public BufferedImage read(int index,ImageReadParam param)throws IOException {
            if(fail)throw new IOException("intentional decode failure");return new BufferedImage(2,2,BufferedImage.TYPE_BYTE_GRAY);
        }
        @Override public void dispose(){disposed=true;super.dispose();}
    }
}

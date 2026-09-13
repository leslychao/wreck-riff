package game.wreckriff.presentation;

import com.jme3.texture.Image;
import com.jme3.texture.image.ColorSpace;
import com.jme3.texture.plugins.AWTLoader;
import com.jme3.util.BufferUtils;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.PixelInterleavedSampleModel;
import java.io.IOException;
import java.io.InputStream;

/**
 * Uses the JDK PNG decoder's native RGB/RGBA destination to avoid its per-row BGR
 * shuffle allocations. The final jME bytes, format and flip match AWTLoader 3.8.1;
 * only decoding allocation changes. Scalar/indexed/other formats retain AWT conversion.
 * AssetInfo stream ownership and TextureKey caching remain with the inherited loader.
 */
public final class RawPngLoader extends AWTLoader {
    @Override public Image load(InputStream input,boolean flipY)throws IOException {
        // An explicit memory stream neither downloads assets nor creates ImageIO disk caches.
        // Closing it releases decoder storage; the caller still owns the source InputStream.
        try(var stream=new MemoryCacheImageInputStream(input)) {
            var readers=ImageIO.getImageReaders(stream);
            if(!readers.hasNext())return null;
            return read(readers.next(),stream,flipY);
        }
    }

    static Image read(ImageReader reader,ImageInputStream stream,boolean flipY)throws IOException {
        try {
            reader.setInput(stream,true,true);
            int type=reader.getImageTypes(0).next().getBufferedImageType();
            int components=type==BufferedImage.TYPE_3BYTE_BGR?3:type==BufferedImage.TYPE_4BYTE_ABGR?4:0;
            var raw=reader.getRawImageType(0);var param=reader.getDefaultReadParam();
            boolean useRaw=components>0&&raw!=null&&raw.getNumBands()==components&&raw.getBitsPerBand(0)==8
                    &&raw.getSampleModel() instanceof PixelInterleavedSampleModel;
            if(useRaw) {
                int[] offsets=((PixelInterleavedSampleModel)raw.getSampleModel()).getBandOffsets();
                for(int i=0;i<components;i++)useRaw&=offsets[i]==i&&raw.getBitsPerBand(i)==8;
            }
            if(useRaw)param.setDestinationType(raw);
            var decoded=reader.read(0,param);
            if(!useRaw)return new AWTLoader().load(decoded,flipY);
            var raster=decoded.getRaster();var model=(PixelInterleavedSampleModel)raster.getSampleModel();
            var storage=(DataBufferByte)raster.getDataBuffer();int width=decoded.getWidth(),height=decoded.getHeight();
            byte[] bytes=storage.getData(),row=new byte[Math.multiplyExact(width,components)];
            var output=BufferUtils.createByteBuffer(Math.multiplyExact(row.length,height));
            for(int y=0;y<height;y++) {
                int sourceY=flipY?height-1-y:y;
                int source=storage.getOffset()+(sourceY-raster.getSampleModelTranslateY())*model.getScanlineStride()
                        -raster.getSampleModelTranslateX()*model.getPixelStride();
                for(int x=0;x<width;x++)for(int c=0;c<components;c++)
                    row[x*components+c]=bytes[source+x*model.getPixelStride()+components-1-c];
                output.put(row);
            }
            // AWT's byte-backed fast paths leave position at capacity, intentionally preserved.
            return new Image(components==3?Image.Format.BGR8:Image.Format.ABGR8,width,height,output,null,ColorSpace.sRGB);
        } finally {reader.dispose();}
    }
}

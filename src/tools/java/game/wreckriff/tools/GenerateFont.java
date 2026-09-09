package game.wreckriff.tools;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;

/** Reproducible BMFont rasterization of locally retained OFL Roboto Condensed sources. */
public final class GenerateFont {
    private static final int SIZE=48, WIDTH=2048, HEIGHT=1024, CELL=96, PAD=4;
    private GenerateFont() {}
    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("GenerateFont <output-resources-root> <font-source-directory>");
        Path output=Path.of(args[0]).resolve("fonts"),sources=Path.of(args[1]);
        Files.createDirectories(output);
        List<Integer> codes=new ArrayList<>();
        for(int code=32;code<=126;code++)codes.add(code);
        codes.add(0x401);
        for(int code=0x410;code<=0x44f;code++)codes.add(code);
        codes.add(0x451);
        List<String> records=new ArrayList<>();
        for(String style:List.of("Regular","Bold")) {
            Path source=sources.resolve("RobotoCondensed-"+style+".ttf");
            Font font=Font.createFont(Font.TRUETYPE_FONT,source.toFile()).deriveFont((float)SIZE);
            if(!font.getFamily(Locale.ROOT).contains("Roboto Condensed"))throw new IllegalArgumentException("Unexpected font family");
            String name=style.equals("Regular")?"wreck":"wreck-bold";
            BufferedImage atlas=new BufferedImage(WIDTH,HEIGHT,BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics=atlas.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            graphics.setFont(font);
            FontMetrics metrics=graphics.getFontMetrics();
            StringBuilder fnt=new StringBuilder(String.format(Locale.ROOT,
                    "info face=\"Roboto Condensed\" size=48 bold=%d italic=0 charset=\"\" unicode=1 stretchH=100 smooth=1 aa=2 padding=4,4,4,4 spacing=0,0\n"
                    +"common lineHeight=%d base=%d scaleW=%d scaleH=%d pages=1 packed=0\npage id=0 file=\"%s.png\"\nchars count=%d\n",
                    style.equals("Bold")?1:0,metrics.getHeight(),metrics.getAscent(),WIDTH,HEIGHT,name,codes.size()));
            int index=0;
            for(int code:codes) {
                if(!font.canDisplay(code))throw new IllegalArgumentException("Font lacks glyph "+code);
                GlyphVector glyph=font.createGlyphVector(graphics.getFontRenderContext(),Character.toString(code));
                Rectangle bounds=glyph.getPixelBounds(graphics.getFontRenderContext(),0,0);
                int width=Math.max(1,bounds.width),height=Math.max(1,bounds.height);
                if(width+2*PAD>CELL || height+2*PAD>CELL)throw new IllegalArgumentException("Glyph exceeds atlas cell");
                int x=index%(WIDTH/CELL)*CELL+PAD,y=index/(WIDTH/CELL)*CELL+PAD;
                BufferedImage highImage=new BufferedImage(width*2,height*2,BufferedImage.TYPE_INT_ARGB);
                Graphics2D high=highImage.createGraphics(); high.scale(2,2);
                high.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                high.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,RenderingHints.VALUE_FRACTIONALMETRICS_ON);
                high.setColor(Color.WHITE); high.drawGlyphVector(glyph,-bounds.x,-bounds.y); high.dispose();
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.drawImage(highImage,x,y,width,height,null);
                fnt.append(String.format(Locale.ROOT,
                        "char id=%d x=%d y=%d width=%d height=%d xoffset=%d yoffset=%d xadvance=%d page=0 chnl=15\n",
                        code,x,y,width,height,bounds.x,metrics.getAscent()+bounds.y,Math.round(glyph.getGlyphMetrics(0).getAdvanceX())));
                index++;
            }
            graphics.dispose();fnt.append("kernings count=0\n");
            Path descriptor=output.resolve(name+".fnt"),texture=output.resolve(name+".png");
            Files.writeString(descriptor,fnt,StandardCharsets.UTF_8);
            if(!ImageIO.write(atlas,"png",texture.toFile()))throw new IllegalStateException("PNG writer unavailable");
            records.add("""
                    {"name":"%s","style":"%s","sourcePath":"src/tools/assets/fonts/RobotoCondensed-%s.ttf",
                    "sourceSha256":"%s","fntSha256":"%s","pngSha256":"%s"}
                    """.formatted(name,style,style,hash(Files.readAllBytes(source)),hash(Files.readAllBytes(descriptor)),hash(Files.readAllBytes(texture))).strip());
        }
        Files.writeString(output.resolve("font-provenance.json"),"""
                {"schemaVersion":2,"name":"Roboto Condensed","externalFontInput":true,"glyphs":161,
                "sourceUrl":"https://github.com/googlefonts/roboto-classic/releases/download/v3.008/Roboto_v3.008.zip",
                "author":"The Roboto Project Authors","license":"OFL-1.1","licensePath":"licenses/assets/Roboto-OFL.txt",
                "rendering":"48px grayscale antialiasing; 2x supersampling; true lowercase and Cyrillic; no system font",
                "artisticStatus":"NEEDS_CREATIVE_REVIEW","fonts":[%s]}
                """.formatted(String.join(",",records)),StandardCharsets.UTF_8);
        System.out.println("Generated antialiased Roboto Condensed regular/bold: "+output);
    }
    private static String hash(byte[] bytes)throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}

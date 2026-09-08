package game.wreckriff.tools;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Original geometric 5x7 ASCII recipes. Never reads or rasterizes a system/external font. */
public final class GenerateFont {
    private static final Map<Character, String> GLYPHS = new HashMap<>();
    static {
        glyph('A', "01110/10001/10001/11111/10001/10001/10001");
        glyph('B', "11110/10001/10001/11110/10001/10001/11110");
        glyph('C', "01111/10000/10000/10000/10000/10000/01111");
        glyph('D', "11110/10001/10001/10001/10001/10001/11110");
        glyph('E', "11111/10000/10000/11110/10000/10000/11111");
        glyph('F', "11111/10000/10000/11110/10000/10000/10000");
        glyph('G', "01111/10000/10000/10111/10001/10001/01110");
        glyph('H', "10001/10001/10001/11111/10001/10001/10001");
        glyph('I', "11111/00100/00100/00100/00100/00100/11111");
        glyph('J', "00111/00010/00010/00010/10010/10010/01100");
        glyph('K', "10001/10010/10100/11000/10100/10010/10001");
        glyph('L', "10000/10000/10000/10000/10000/10000/11111");
        glyph('M', "10001/11011/10101/10101/10001/10001/10001");
        glyph('N', "10001/11001/11001/10101/10011/10011/10001");
        glyph('O', "01110/10001/10001/10001/10001/10001/01110");
        glyph('P', "11110/10001/10001/11110/10000/10000/10000");
        glyph('Q', "01110/10001/10001/10001/10101/10010/01101");
        glyph('R', "11110/10001/10001/11110/10100/10010/10001");
        glyph('S', "01111/10000/10000/01110/00001/00001/11110");
        glyph('T', "11111/00100/00100/00100/00100/00100/00100");
        glyph('U', "10001/10001/10001/10001/10001/10001/01110");
        glyph('V', "10001/10001/10001/10001/10001/01010/00100");
        glyph('W', "10001/10001/10001/10101/10101/11011/10001");
        glyph('X', "10001/10001/01010/00100/01010/10001/10001");
        glyph('Y', "10001/10001/01010/00100/00100/00100/00100");
        glyph('Z', "11111/00001/00010/00100/01000/10000/11111");
        glyph('0', "01110/10001/10011/10101/11001/10001/01110");
        glyph('1', "00100/01100/00100/00100/00100/00100/01110");
        glyph('2', "01110/10001/00001/00010/00100/01000/11111");
        glyph('3', "11110/00001/00001/01110/00001/00001/11110");
        glyph('4', "00010/00110/01010/10010/11111/00010/00010");
        glyph('5', "11111/10000/10000/11110/00001/00001/11110");
        glyph('6', "01110/10000/10000/11110/10001/10001/01110");
        glyph('7', "11111/00001/00010/00100/01000/01000/01000");
        glyph('8', "01110/10001/10001/01110/10001/10001/01110");
        glyph('9', "01110/10001/10001/01111/00001/00001/01110");
        glyph(' ', "00000/00000/00000/00000/00000/00000/00000");
        glyph('!', "00100/00100/00100/00100/00100/00000/00100");
        glyph('"', "01010/01010/01010/00000/00000/00000/00000");
        glyph('#', "01010/01010/11111/01010/11111/01010/01010");
        glyph('$', "00100/01111/10100/01110/00101/11110/00100");
        glyph('%', "11001/11010/00100/00100/01000/10110/00110");
        glyph('&', "01100/10010/10100/01000/10101/10010/01101");
        glyph('\'', "00100/00100/01000/00000/00000/00000/00000");
        glyph('(', "00010/00100/01000/01000/01000/00100/00010");
        glyph(')', "01000/00100/00010/00010/00010/00100/01000");
        glyph('*', "00000/10101/01110/11111/01110/10101/00000");
        glyph('+', "00000/00100/00100/11111/00100/00100/00000");
        glyph(',', "00000/00000/00000/00000/00000/00100/01000");
        glyph('-', "00000/00000/00000/11111/00000/00000/00000");
        glyph('.', "00000/00000/00000/00000/00000/00000/00100");
        glyph('/', "00001/00010/00010/00100/01000/01000/10000");
        glyph(':', "00000/00100/00100/00000/00100/00100/00000");
        glyph(';', "00000/00100/00100/00000/00100/00100/01000");
        glyph('<', "00001/00010/00100/01000/00100/00010/00001");
        glyph('=', "00000/00000/11111/00000/11111/00000/00000");
        glyph('>', "10000/01000/00100/00010/00100/01000/10000");
        glyph('?', "01110/10001/00001/00010/00100/00000/00100");
        glyph('@', "01110/10001/10111/10101/10111/10000/01110");
        glyph('[', "01110/01000/01000/01000/01000/01000/01110");
        glyph('\\', "10000/01000/01000/00100/00010/00010/00001");
        glyph(']', "01110/00010/00010/00010/00010/00010/01110");
        glyph('^', "00100/01010/10001/00000/00000/00000/00000");
        glyph('_', "00000/00000/00000/00000/00000/00000/11111");
        glyph('`', "01000/00100/00010/00000/00000/00000/00000");
        glyph('{', "00011/00100/00100/01000/00100/00100/00011");
        glyph('|', "00100/00100/00100/00100/00100/00100/00100");
        glyph('}', "11000/00100/00100/00010/00100/00100/11000");
        glyph('~', "00000/00000/01001/10110/00000/00000/00000");
    }

    private GenerateFont() {}

    private static void glyph(char character, String recipe) {
        if (!recipe.matches("[01]{5}(/[01]{5}){6}")) throw new IllegalArgumentException("Malformed glyph " + character);
        GLYPHS.put(character, recipe);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("GenerateFont <output-resources-root>");
        Path output = Path.of(args[0]).resolve("fonts");
        Files.createDirectories(output);
        BufferedImage atlas = new BufferedImage(512, 256, BufferedImage.TYPE_INT_ARGB);
        StringBuilder fnt = new StringBuilder("""
                info face="Wreck Grid" size=24 bold=0 italic=0 charset="ASCII" unicode=0 stretchH=100 smooth=0 aa=1 padding=0,0,0,0 spacing=0,0
                common lineHeight=27 base=21 scaleW=512 scaleH=256 pages=1 packed=0
                page id=0 file="wreck.png"
                chars count=95
                """);
        StringBuilder recipes = new StringBuilder();
        for (int code = 32; code <= 126; code++) {
            char glyph = Character.toUpperCase((char) code);
            String recipe = Objects.requireNonNull(GLYPHS.get(glyph), "Missing ASCII glyph " + code);
            recipes.append(code).append(':').append(recipe).append('\n');
            int x = ((code - 32) % 16) * 30 + 2, y = ((code - 32) / 16) * 30 + 2;
            String[] rows = recipe.split("/");
            for (int row = 0; row < 7; row++) for (int column = 0; column < 5; column++) {
                if (rows[row].charAt(column) != '1') continue;
                for (int dy = 0; dy < 3; dy++) for (int dx = 0; dx < 3; dx++) atlas.setRGB(x + column * 3 + dx, y + row * 3 + dy, 0xffffffff);
            }
            fnt.append("char id=").append(code).append(" x=").append(x).append(" y=").append(y)
                    .append(" width=15 height=21 xoffset=0 yoffset=0 xadvance=18 page=0 chnl=15\n");
        }
        fnt.append("kernings count=0\n");
        Files.writeString(output.resolve("wreck.fnt"), fnt, StandardCharsets.UTF_8);
        if (!ImageIO.write(atlas, "png", output.resolve("wreck.png").toFile())) throw new IllegalStateException("PNG writer unavailable");
        Files.writeString(output.resolve("font-provenance.json"), """
                {
                  "schemaVersion": 1,
                  "name": "Wreck Grid",
                  "origin": "Original geometric ASCII recipes authored in GenerateFont.java for Wreck Riff",
                  "externalFontInput": false,
                  "glyphs": 95,
                  "lowercaseStyle": "Uppercase glyph forms",
                  "artisticStatus": "NEEDS_CREATIVE_REVIEW",
                  "recipeSha256": "%s",
                  "fntSha256": "%s",
                  "pngSha256": "%s"
                }
                """.formatted(hash(recipes.toString().getBytes(StandardCharsets.UTF_8)), hash(Files.readAllBytes(output.resolve("wreck.fnt"))), hash(Files.readAllBytes(output.resolve("wreck.png")))), StandardCharsets.UTF_8);
        System.out.println("Generated 95 original ASCII glyphs: " + output);
    }

    private static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
}

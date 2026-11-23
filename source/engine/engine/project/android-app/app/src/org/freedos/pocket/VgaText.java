package org.freedos.pocket;

import java.io.IOException;

/** Text-mode snapshot only. Not OCR, graphics-mode capture, or a DOS app clipboard. */
final class VgaText {
    final int columns, rows, address;
    private static final String HIGH =
        "ÇüéâäàåçêëèïîìÄÅÉæÆôöòûùÿÖÜ¢£¥₧ƒ" +
        "áíóúñÑªº¿⌐¬½¼¡«»░▒▓│┤╡╢╖╕╣║╗╝╜╛┐" +
        "└┴┬├─┼╞╟╚╔╩╦╠═╬╧╨╤╥╙╘╒╓╫╪┘┌█▄▌▐▀" +
        "αßΓπΣσµτΦΘΩδ∞φε∩≡±≥≤⌠⌡÷≈°∙·√ⁿ²■ ";
    private static final String LOW = " ☺☻♥♦♣♠•◘○◙♂♀♪♫☼►◄↕‼¶§▬↨↑↓→←∟↔▲▼";
    VgaText(byte[] bda) throws IOException {
        if (bda.length != 128) throw new IOException("Incomplete BIOS video data");
        int mode = bda[9] & 255;
        if (!(mode <= 3 || mode == 7))
            throw new IOException("Copy text is available in standard DOS text modes, not this graphics mode.");
        columns = word(bda, 10);
        rows = (bda[68] & 255) + 1;
        int offset = word(bda, 14);
        if (columns < 20 || columns > 160 || rows < 10 || rows > 100 || offset + columns * rows * 2 > 32768)
            throw new IOException("Unsupported DOS text layout");
        address = (mode == 7 ? 0xb0000 : 0xb8000) + offset;
    }
    private static int word(byte[] b, int n) { return (b[n]&255) | ((b[n+1]&255)<<8); }
    String decode(byte[] cells) throws IOException {
        if (cells.length != columns * rows * 2) throw new IOException("Incomplete DOS text snapshot");
        StringBuilder screen = new StringBuilder();
        for (int row = 0; row < rows; row++) {
            StringBuilder line = new StringBuilder();
            for (int col = 0; col < columns; col++) {
                int c = cells[(row * columns + col) * 2] & 255;
                line.append(c < 32 ? LOW.charAt(c) : c < 127 ? (char)c : c == 127 ? '⌂' : HIGH.charAt(c-128));
            }
            while (line.length()>0 && line.charAt(line.length()-1)==' ') line.setLength(line.length()-1);
            screen.append(line);
            if (row + 1 < rows) screen.append('\n');
        }
        return screen.toString();
    }
    static byte[] parseDump(String dump, int address, int count) throws IOException {
        byte[] bytes = new byte[count]; int position = 0;
        for (String line : dump.split("\\r?\\n")) {
            if (line.trim().isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon < 0) throw new IOException("Unexpected memory response");
            try {
                String location = line.substring(0, colon).trim().replaceFirst("^0x", "");
                if (Long.parseLong(location,16) != (long)address+position) throw new IOException("Non-contiguous memory response");
                for (String value : line.substring(colon+1).trim().split("\\s+")) {
                    if (!value.matches("0x[0-9a-fA-F]{2}") || position >= count) throw new IOException("Invalid memory byte");
                    bytes[position++] = (byte)Integer.parseInt(value.substring(2),16);
                }
            } catch (NumberFormatException ex) { throw new IOException("Invalid memory address", ex); }
        }
        if (position != count) throw new IOException("Truncated memory response");
        return bytes;
    }
}

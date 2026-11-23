package org.freedos.pocket;

/** Validate the entire paste before sending any keystrokes to DOS. */
final class PasteText {
    static String prepare(String text) {
        if (text == null || text.isEmpty()) throw new IllegalArgumentException("Clipboard has no text.");
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.length() > 1024) throw new IllegalArgumentException("Paste at most 1024 characters at a time.");
        try { DosTextCodec.encode(normalized); }
        catch (java.nio.charset.CharacterCodingException error) {
            throw new IllegalArgumentException("Text contains characters unavailable in DOS GB2312; nothing was sent.");
        }
        return normalized;
    }
}

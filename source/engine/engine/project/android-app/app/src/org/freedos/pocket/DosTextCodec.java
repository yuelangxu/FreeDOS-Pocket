package org.freedos.pocket;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;

/** Converts complete host text to DOS bytes before any guest mutation occurs. */
final class DosTextCodec {
    private static final Charset GB2312 = Charset.forName("GB2312");
    private DosTextCodec() {}
    static byte[] encode(String text) throws CharacterCodingException {
        if(text==null)return new byte[0];
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        for(int offset=0;offset<text.length();){
            int c=text.codePointAt(offset);offset+=Character.charCount(c);
            if(c=='\r'){
                if(offset<text.length()&&text.charAt(offset)=='\n')offset++;
                out.write(13);continue;
            }
            if(c=='\n'){out.write(13);continue;}
            if(c=='\t'){out.write(9);continue;}
            if(c<32||c==127)throw new CharacterCodingException();
            CharsetEncoder encoder=GB2312.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            ByteBuffer bytes=encoder.encode(CharBuffer.wrap(Character.toChars(c)));
            while(bytes.hasRemaining())out.write(bytes.get()&255);
        }
        return out.toByteArray();
    }
    static boolean isAscii(byte[] bytes){for(byte b:bytes)if((b&128)!=0)return false;return true;}
}

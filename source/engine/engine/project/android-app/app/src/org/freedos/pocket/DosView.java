package org.freedos.pocket;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.SparseIntArray;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal local RFB viewer. It deliberately stretches the complete framebuffer to
 * the available View rectangle, including when Android's IME resizes the window.
 * No network outside 127.0.0.1 is contacted and no server clipboard is imported.
 */
public final class DosView extends View {
    public interface StatusListener { void onStatus(String message); }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object frameLock = new Object();
    private final Object outputLock = new Object();
    private final Paint paint = new Paint();
    private static final long KEY_INTERVAL_NANOS = 16000000L;
    // Used only by the serial input writer. DOS/PS2 buffers are tiny; sending an
    // entire IME commit at network speed can drop its leading characters.
    private long lastKeyWriteNanos;
    private final ExecutorService inputWriter = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DOS input"); t.setDaemon(true); return t;
    });
    private volatile StatusListener listener;
    private volatile Socket socket;
    private volatile DataOutputStream output;
    private volatile int generation;
    private volatile boolean connected;
    private volatile int frameWidth = 640, frameHeight = 480;
    private Bitmap bitmap;
    private int[] pixels;
    private String statusText = "Starting FreeDOS...";
    private int pointerX, pointerY;
    private boolean pointerPressed;
    private final SparseIntArray heldKeys = new SparseIntArray();

    public DosView(Context context) { this(context, null); }
    public DosView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(Color.BLACK);
        paint.setFilterBitmap(false);
        setContentDescription("FreeDOS screen. Use the Keyboard button to type.");
    }

    public void setStatusListener(StatusListener listener) { this.listener = listener; }
    public boolean isConnected() { return connected; }
    public int getGuestWidth() { return frameWidth; }
    public int getGuestHeight() { return frameHeight; }

    private void report(String text) {
        main.post(() -> {
            statusText = text;
            StatusListener current = listener;
            if (current != null) current.onStatus(text);
            invalidate();
        });
    }

    /** Starts one bounded connection attempt sequence, suitable after Engine.start(). */
    public synchronized void connect() {
        disconnect();
        final int token = ++generation;
        Thread worker = new Thread(() -> runConnection(token), "DOS framebuffer");
        worker.setDaemon(true);
        worker.start();
    }

    public synchronized void disconnect() {
        ++generation;
        connected = false;
        pointerPressed = false;
        heldKeys.clear();
        Socket old = socket;
        socket = null;
        output = null;
        if (old != null) try { old.close(); } catch (IOException ignored) { }
    }

    private void runConnection(int token) {
        Socket local = null;
        try {
            report("Connecting to the local DOS engine...");
            IOException last = null;
            for (int attempt = 0; attempt < 80 && token == generation; attempt++) {
                local = new Socket();
                try {
                    local.connect(new InetSocketAddress("127.0.0.1", Engine.VNC_PORT), 500);
                    break;
                } catch (IOException error) {
                    last = error;
                    try { local.close(); } catch (IOException ignored) { }
                    local = null;
                    Thread.sleep(250);
                }
            }
            if (token != generation) return;
            if (local == null) throw new IOException("The local DOS display did not become ready", last);
            local.setTcpNoDelay(true);
            local.setSoTimeout(10000);
            synchronized (this) {
                if (token != generation) return;
                socket = local;
            }
            DataInputStream in = new DataInputStream(local.getInputStream());
            DataOutputStream out = new DataOutputStream(local.getOutputStream());
            negotiate(in, out);
            if (token != generation) return;
            local.setSoTimeout(0);
            synchronized (this) {
                if (token != generation) return;
                synchronized (outputLock) { output = out; connected = true; }
            }
            report("FreeDOS is running");
            requestFrame(out, false);
            while (token == generation) {
                int type = in.readUnsignedByte();
                if (type == 0) {
                    in.readUnsignedByte();
                    int count = in.readUnsignedShort();
                    boolean changed = false;
                    for (int i = 0; i < count; i++) {
                        int x = in.readUnsignedShort(), y = in.readUnsignedShort();
                        int w = in.readUnsignedShort(), h = in.readUnsignedShort();
                        int encoding = in.readInt();
                        if (encoding == -224) break;
                        if (encoding == -223) { resizeFramebuffer(w, h); changed = true; continue; }
                        validateRect(x, y, w, h);
                        if (encoding == 0) {
                            byte[] row = new byte[w * 4];
                            for (int iy = 0; iy < h; iy++) {
                                in.readFully(row);
                                int dst = (y + iy) * frameWidth + x;
                                for (int ix = 0, b = 0; ix < w; ix++, b += 4)
                                    pixels[dst + ix] = 0xff000000 | ((row[b + 2] & 255) << 16)
                                        | ((row[b + 1] & 255) << 8) | (row[b] & 255);
                            }
                            changed = true;
                        } else if (encoding == 1) {
                            int sx = in.readUnsignedShort(), sy = in.readUnsignedShort();
                            validateRect(sx, sy, w, h);
                            int[] copy = new int[w * h];
                            for (int row = 0; row < h; row++)
                                System.arraycopy(pixels, (sy + row) * frameWidth + sx, copy, row * w, w);
                            for (int row = 0; row < h; row++)
                                System.arraycopy(copy, row * w, pixels, (y + row) * frameWidth + x, w);
                            changed = true;
                        } else throw new IOException("Unsupported local display encoding: " + encoding);
                    }
                    if (changed) {
                        synchronized (frameLock) {
                            bitmap.setPixels(pixels, 0, frameWidth, 0, 0, frameWidth, frameHeight);
                        }
                        postInvalidateOnAnimation();
                    }
                    // Bound the request cadence; the server may defer unchanged frames.
                    Thread.sleep(16);
                    requestFrame(out, true);
                } else if (type == 1) {
                    in.readUnsignedByte(); in.readUnsignedShort();
                    int colours = in.readUnsignedShort(); skipFully(in, colours * 6);
                } else if (type == 2) {
                    // Bell: intentionally silent. Sound synthesis belongs to the emulator.
                } else if (type == 3) {
                    skipFully(in, 3); int size = in.readInt();
                    if (size < 0 || size > 1048576) throw new IOException("Invalid clipboard length");
                    skipFully(in, size);
                } else throw new IOException("Unexpected display message: " + type);
            }
        } catch (Exception error) {
            if (token == generation) report("Display disconnected: " + error.getMessage());
        } finally {
            if (local != null) try { local.close(); } catch (IOException ignored) { }
            synchronized (this) {
                if (token == generation) { socket = null; output = null; connected = false; }
            }
        }
    }

    private void negotiate(DataInputStream in, DataOutputStream out) throws IOException {
        byte[] greeting = new byte[12]; in.readFully(greeting);
        String version = new String(greeting, StandardCharsets.US_ASCII);
        if (!version.startsWith("RFB 003.")) throw new IOException("Not an RFB 3.x server");
        int minor;
        try { minor = Integer.parseInt(version.substring(8, 11)); }
        catch (NumberFormatException e) { throw new IOException("Invalid RFB version", e); }
        boolean modern = minor >= 7;
        out.write((minor >= 8 ? "RFB 003.008\n" : modern ? "RFB 003.007\n" : "RFB 003.003\n")
            .getBytes(StandardCharsets.US_ASCII)); out.flush();
        if (modern) {
            int count = in.readUnsignedByte();
            if (count == 0) throw new IOException("Local display rejected the connection");
            boolean none = false;
            for (int i = 0; i < count; i++) if (in.readUnsignedByte() == 1) none = true;
            if (!none) throw new IOException("Local display requires unexpected authentication");
            out.writeByte(1); out.flush();
            if (minor >= 8 && in.readInt() != 0) throw new IOException("Local display authentication failed");
        } else if (in.readInt() != 1) throw new IOException("Local display requires unexpected authentication");
        out.writeByte(1); out.flush(); // Shared client; no other session is disconnected.
        int width = in.readUnsignedShort(), height = in.readUnsignedShort();
        skipFully(in, 16);
        int nameLength = in.readInt();
        if (nameLength < 0 || nameLength > 1048576) throw new IOException("Invalid display name length");
        skipFully(in, nameLength);
        resizeFramebuffer(width, height);
        // SetPixelFormat: little-endian 32bpp / 24-bit RGB, shifts R16 G8 B0.
        out.writeByte(0); out.write(new byte[3]);
        out.writeByte(32); out.writeByte(24); out.writeByte(0); out.writeByte(1);
        out.writeShort(255); out.writeShort(255); out.writeShort(255);
        out.writeByte(16); out.writeByte(8); out.writeByte(0); out.write(new byte[3]);
        out.writeByte(2); out.writeByte(0); out.writeShort(4);
        out.writeInt(0); out.writeInt(1); out.writeInt(-223); out.writeInt(-224);
        out.flush();
    }

    private void resizeFramebuffer(int width, int height) throws IOException {
        if (width < 1 || height < 1 || width > 8192 || height > 8192 || (long)width * height > 16777216)
            throw new IOException("Invalid display size: " + width + " x " + height);
        synchronized (frameLock) {
            frameWidth = width; frameHeight = height;
            pixels = new int[width * height];
            java.util.Arrays.fill(pixels, Color.BLACK);
            Bitmap old = bitmap;
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            if (old != null) old.recycle();
        }
    }

    private void validateRect(int x, int y, int width, int height) throws IOException {
        if (x + width > frameWidth || y + height > frameHeight)
            throw new IOException("Display rectangle exceeds framebuffer");
    }

    private static void skipFully(DataInputStream in, int count) throws IOException {
        while (count > 0) {
            int n = in.skipBytes(count);
            if (n == 0) { if (in.read() < 0) throw new EOFException(); n = 1; }
            count -= n;
        }
    }

    private void requestFrame(DataOutputStream out, boolean incremental) throws IOException {
        synchronized (outputLock) {
            out.writeByte(3); out.writeByte(incremental ? 1 : 0);
            out.writeShort(0); out.writeShort(0); out.writeShort(frameWidth); out.writeShort(frameHeight); out.flush();
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.BLACK);
        synchronized (frameLock) {
            if (bitmap != null) canvas.drawBitmap(bitmap, null, new RectF(0, 0, getWidth(), getHeight()), paint);
            else {
                paint.setColor(Color.LTGRAY); paint.setTextSize(16 * getResources().getDisplayMetrics().scaledDensity);
                canvas.drawText(statusText, 16, 32 * getResources().getDisplayMetrics().density, paint);
            }
        }
    }

    private interface OutputAction { void write(DataOutputStream out) throws IOException; }
    private void queue(OutputAction action) {
        queue(action, false);
    }
    private void queue(OutputAction action, boolean pacedKey) {
        final int token = generation;
        if (!connected) return;
        inputWriter.execute(() -> {
            if (token != generation || !connected) return;
            if (pacedKey) {
                // Sleep off the UI thread and outside the socket lock, so frame
                // requests remain responsive while a pasted command is typed.
                long remaining;
                while (lastKeyWriteNanos != 0L && (remaining = KEY_INTERVAL_NANOS - (System.nanoTime() - lastKeyWriteNanos)) > 0) {
                    if (token != generation || !connected) return;
                    try { Thread.sleep(remaining / 1000000L, (int)(remaining % 1000000L)); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                }
            }
            synchronized (outputLock) {
                if (token != generation || !connected) return;
                DataOutputStream out = output;
                if (out == null) return;
                try {
                    action.write(out); out.flush();
                    if (pacedKey) lastKeyWriteNanos = System.nanoTime();
                }
                catch (IOException error) {
                    Socket current = socket;
                    if (current != null) try { current.close(); } catch (IOException ignored) { }
                }
            }
        });
    }

    public void sendKey(int keysym, boolean down) {
        queue(out -> { out.writeByte(4); out.writeByte(down ? 1 : 0); out.writeShort(0); out.writeInt(keysym); }, true);
    }
    public void tapKey(int keysym) { sendKey(keysym, true); sendKey(keysym, false); }
    /** Release modifiers when Android steals focus; a missing key-up must not
     * turn subsequent typing or Backspace into a control-character stream. */
    public void releaseKeys() {
        for (int i=0;i<heldKeys.size();i++) sendKey(heldKeys.valueAt(i), false);
        heldKeys.clear();
        for (int symbol : new int[]{0xffe1,0xffe2,0xffe3,0xffe4,0xffe9,0xffea}) sendKey(symbol,false);
        pointerPressed=false;
        sendPointer(0,pointerX,pointerY);
    }
    public void typeText(String text) {
        final byte[] bytes;
        try{bytes=DosTextCodec.encode(text);}
        catch(java.nio.charset.CharacterCodingException error){
            report("Text contains characters unavailable in DOS GB2312; nothing was sent");return;
        }
        if(!DosTextCodec.isAscii(bytes)){
            report("Sending GB2312 through DOS host-text receiver...");
            DosSerialBridge.send(bytes,(accepted,error)->{
                if(error==null)report("Host text delivered to DOS ("+accepted+" bytes)");
                else report("Host text stopped after "+accepted+" bytes: install Host IME at C:\\> first");
            });
            return;
        }
        for(byte value:bytes){
            int c=value&255;
            if(c==13)tapKey(0xff0d);
            else if(c==9)tapKey(0xff09);
            else tapKey(c);
        }
    }
    private void sendPointer(int mask, int x, int y) {
        queue(out -> { out.writeByte(5); out.writeByte(mask); out.writeShort(x); out.writeShort(y); });
    }
    public void rightClick() { sendPointer(4, pointerX, pointerY); sendPointer(0, pointerX, pointerY); }
    public void leftClick() { sendPointer(1, pointerX, pointerY); sendPointer(0, pointerX, pointerY); }

    /** Call when the owning Activity is permanently destroyed, not merely paused. */
    public void release() { disconnect(); inputWriter.shutdownNow(); }

    @Override public boolean onTouchEvent(MotionEvent event) {
        pointerX = Math.max(0, Math.min(frameWidth - 1, (int)(event.getX() * frameWidth / Math.max(1, getWidth()))));
        pointerY = Math.max(0, Math.min(frameHeight - 1, (int)(event.getY() * frameHeight / Math.max(1, getHeight()))));
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                requestFocus(); pointerPressed = true; sendPointer(1, pointerX, pointerY); return true;
            case MotionEvent.ACTION_MOVE:
                sendPointer(pointerPressed ? 1 : 0, pointerX, pointerY); return true;
            case MotionEvent.ACTION_UP:
                pointerPressed = false; sendPointer(0, pointerX, pointerY); performClick(); return true;
            case MotionEvent.ACTION_CANCEL:
                pointerPressed = false; sendPointer(0, pointerX, pointerY); return true;
            default: return true;
        }
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_HOVER_MOVE) {
            pointerX = Math.max(0, Math.min(frameWidth - 1, (int)(event.getX() * frameWidth / Math.max(1, getWidth()))));
            pointerY = Math.max(0, Math.min(frameHeight - 1, (int)(event.getY() * frameHeight / Math.max(1, getHeight()))));
            sendPointer(0, pointerX, pointerY); return true;
        }
        return super.onGenericMotionEvent(event);
    }

    public void showKeyboard() {
        requestFocus();
        InputMethodManager imm = (InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.restartInput(this);
        // A toolbar tap is an explicit request, including with a physical keyboard.
        // SHOW_FORCED is deliberately avoided: it can leave the IME visible globally.
        post(() -> imm.showSoftInput(this, 0));
    }
    @Override public boolean onCheckIsTextEditor() { return true; }
    @Override public InputConnection onCreateInputConnection(EditorInfo info) {
        info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        info.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_ACTION_NONE;
        info.initialSelStart = 0; info.initialSelEnd = 0;
        return new BaseInputConnection(this, false) {
            private String composing = "";
            private boolean consumedDeleteDown;
            private void finishPending() {
                String pending = composing;
                composing = "";
                if (!pending.isEmpty()) typeText(pending);
            }
            @Override public boolean commitText(CharSequence text, int newCursorPosition) {
                // commitText replaces the current composing span, not appends it.
                composing = "";
                typeText(text == null ? "" : text.toString()); return true;
            }
            @Override public boolean setComposingText(CharSequence text, int newCursorPosition) {
                // Preserve provisional text locally: injecting every revision into
                // a DOS terminal would duplicate characters and trigger commands.
                composing = text == null ? "" : text.toString(); return true;
            }
            @Override public boolean finishComposingText() {
                finishPending(); return true;
            }
            @Override public CharSequence getTextBeforeCursor(int length, int flags) {
                return composing.substring(Math.max(0, composing.length() - Math.max(0, length)));
            }
            @Override public CharSequence getTextAfterCursor(int length, int flags) { return ""; }
            @Override public CharSequence getSelectedText(int flags) { return ""; }
            @Override public int getCursorCapsMode(int reqModes) { return 0; }
            @Override public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
                int available = composing.codePointCount(0, composing.length());
                int remove = Math.min(Math.max(0, beforeLength), available);
                int keep = composing.offsetByCodePoints(composing.length(), -remove);
                composing = composing.substring(0, keep);
                return deleteSurroundingText(Math.max(0, beforeLength) - remove, afterLength);
            }
            @Override public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                int remove = Math.min(Math.max(0, beforeLength), composing.length());
                composing = composing.substring(0, composing.length() - remove);
                // Delete pending IME characters before deleting already sent DOS text.
                for (int i = 0; i < Math.min(Math.max(0, beforeLength) - remove, 256); i++) tapKey(0xff08);
                for (int i = 0; i < Math.min(afterLength, 256); i++) tapKey(0xffff);
                return true;
            }
            @Override public boolean sendKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_DEL) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN && !composing.isEmpty()) {
                        int end = composing.offsetByCodePoints(composing.length(), -1);
                        composing = composing.substring(0, end); consumedDeleteDown = true; return true;
                    }
                    if (event.getAction() == KeyEvent.ACTION_UP && consumedDeleteDown) {
                        consumedDeleteDown = false; return true;
                    }
                    if (event.getAction() == KeyEvent.ACTION_DOWN) consumedDeleteDown = false;
                }
                if (event.getAction() == KeyEvent.ACTION_DOWN) finishPending();
                return handleKey(event);
            }
            @Override public boolean performEditorAction(int actionCode) { finishPending(); tapKey(0xff0d); return true; }
        };
    }

    private boolean handleKey(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_MULTIPLE) { typeText(event.getCharacters()); return true; }
        if (event.getAction() != KeyEvent.ACTION_DOWN && event.getAction() != KeyEvent.ACTION_UP) return false;
        int physicalKey = event.getKeyCode();
        int symbol = event.getAction() == KeyEvent.ACTION_UP
            ? heldKeys.get(physicalKey, keySym(event)) : keySym(event);
        if (symbol == 0) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) heldKeys.put(physicalKey, symbol);
        else heldKeys.delete(physicalKey);
        sendKey(symbol, event.getAction() == KeyEvent.ACTION_DOWN); return true;
    }
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) { return handleKey(event) || super.onKeyDown(keyCode, event); }
    @Override public boolean onKeyUp(int keyCode, KeyEvent event) { return handleKey(event) || super.onKeyUp(keyCode, event); }

    private int keySym(KeyEvent event) {
        int key = event.getKeyCode();
        if (key >= KeyEvent.KEYCODE_F1 && key <= KeyEvent.KEYCODE_F12) return 0xffbe + key - KeyEvent.KEYCODE_F1;
        switch (key) {
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: return 0xff0d;
            case KeyEvent.KEYCODE_DEL: return 0xff08;
            case KeyEvent.KEYCODE_FORWARD_DEL: return 0xffff;
            case KeyEvent.KEYCODE_ESCAPE: return 0xff1b;
            case KeyEvent.KEYCODE_TAB: return 0xff09;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 0xff51;
            case KeyEvent.KEYCODE_DPAD_UP: return 0xff52;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 0xff53;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 0xff54;
            case KeyEvent.KEYCODE_MOVE_HOME: return 0xff50;
            case KeyEvent.KEYCODE_MOVE_END: return 0xff57;
            case KeyEvent.KEYCODE_PAGE_UP: return 0xff55;
            case KeyEvent.KEYCODE_PAGE_DOWN: return 0xff56;
            case KeyEvent.KEYCODE_INSERT: return 0xff63;
            case KeyEvent.KEYCODE_SHIFT_LEFT: return 0xffe1;
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return 0xffe2;
            case KeyEvent.KEYCODE_CTRL_LEFT: return 0xffe3;
            case KeyEvent.KEYCODE_CTRL_RIGHT: return 0xffe4;
            case KeyEvent.KEYCODE_ALT_LEFT: return 0xffe9;
            case KeyEvent.KEYCODE_ALT_RIGHT: return 0xffea;
            case KeyEvent.KEYCODE_CAPS_LOCK: return 0xffe5;
            case KeyEvent.KEYCODE_NUM_LOCK: return 0xff7f;
            case KeyEvent.KEYCODE_SCROLL_LOCK: return 0xff14;
            case KeyEvent.KEYCODE_SYSRQ: return 0xff61;
            case KeyEvent.KEYCODE_BREAK: return 0xff13;
            default:
                // Strip Ctrl/Alt when resolving the base glyph; their own key events
                // carry the modifier state, so Ctrl+C must send 'c', not ASCII 3.
                int meta = event.getMetaState() & ~(KeyEvent.META_CTRL_MASK | KeyEvent.META_ALT_MASK | KeyEvent.META_META_MASK);
                int c = event.getUnicodeChar(meta);
                return c >= 32 && c <= 126 ? c : 0;
        }
    }
}

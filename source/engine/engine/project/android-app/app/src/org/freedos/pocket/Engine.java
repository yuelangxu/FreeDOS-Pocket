package org.freedos.pocket;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.max2idea.android.limbo.jni.VMExecutor;
import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLAudioManager;
import org.libsdl.app.SDLControllerManager;
import org.libsdl.app.AudioDiagnostics;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One in-process x86 QEMU instance. No Termux, shell, WebView, KVM or root required.
 * Video/keyboard use local RFB; VM lifecycle controls use local QMP.
 */
public final class Engine {
    public static final int VNC_PORT = 5907 + BuildConfig.PORT_OFFSET;
    public static final int QMP_PORT = 5908 + BuildConfig.PORT_OFFSET;
    public static final int SERIAL_PORT = 5910 + BuildConfig.PORT_OFFSET;
    private static final String TAG = "FreeDOSEngine";
    private static boolean librariesLoaded;
    private static boolean startedOnce;
    private static volatile boolean running;
    private static volatile String lastStatus = "Not started";
    private static volatile Listener listener;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService CONTROL = Executors.newSingleThreadExecutor();
    private static VMExecutor nativeExecutor;

    private Engine() {}
    public interface Listener { void onStatus(String message, boolean running); }
    public interface Reply { void onReply(String result, Exception error); }
    public static void setListener(Listener value) { listener = value; }
    // Called on the UI thread. A retiring Activity must not detach its successor.
    public static void clearListener(Listener owner) { if (listener == owner) listener = null; }
    public static boolean isRunning() { return running; }
    public static String getStatus() { return lastStatus; }

    private static void report(String message) {
        lastStatus = message;
        Log.i(TAG, message);
        MAIN.post(() -> { Listener l = listener; if (l != null) l.onStatus(message, running); });
    }

    /** Must be called on the UI thread, matching upstream Limbo's load requirement. */
    public static synchronized void start(Context context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Context app = context.getApplicationContext();
            MAIN.post(() -> start(app));
            return;
        }
        if (running) return;
        if (startedOnce) {
            report("Emulator has stopped. Close and reopen the app to start a new session.");
            return;
        }
        final Context app = context.getApplicationContext();
        final File files = app.getFilesDir();
        final File bios = new File(files, "bios");
        final File system = new File(files, "disks/system.vdi");
        final File software = new File(files, "disks/software.vdi");
        final File hostIme = new File(files, "guest/hostime.img");
        if (!system.isFile() || !software.isFile() || !hostIme.isFile() || !new File(bios, "bios-256k.bin").isFile()) {
            report("FreeDOS disks or BIOS are missing. Finish offline preparation first.");
            return;
        }
        try {
            loadLibraries();
            SDLActivity.initialize(app);
            SDLActivity.nativeSetupJNI();
            SDLAudioManager.nativeSetupJNI();
            SDLControllerManager.nativeSetupJNI();
            if (BuildConfig.ENABLE_AUDIO_DIAGNOSTICS) AudioDiagnostics.configure(app);
        } catch (LinkageError error) {
            report("Native emulator could not load: " + error.getMessage()
                    + ". Check that this APK supports your device's Android version and CPU.");
            return;
        }
        prepareDns(app, files);
        /* DOS games commonly render SB16 effects at 11025 or 22050 Hz. 44100
         * is an exact multiple of both, avoiding the audible repeated tails
         * produced by QEMU 5.1's old mixer when converting them to 48000 Hz.
         * Android's modern output mixer performs the final device conversion. */
        final int mixRate = 44100;
        final String audioDevice = "sdl,id=audio,out.frequency=" + mixRate
                + ",out.channels=2,out.format=s16,out.buffer-length=20000";
        Log.i(TAG, "DOS-friendly 44100 Hz stereo with a stable 20 ms QEMU callback and one-block ring");
        final String[] argv = {
            "qemu-system-x86_64",
            "-L", bios.getAbsolutePath(),
            "-machine", "pc,pcspk-audiodev=audio",
            "-accel", "tcg,thread=single",
            "-cpu", "pentium3",
            "-smp", "1",
            "-m", "64",
            "-boot", "order=c",
            "-drive", "file=" + system.getAbsolutePath() + ",format=vdi,if=ide,index=0,cache=writethrough",
            "-drive", "file=" + software.getAbsolutePath() + ",format=vdi,if=ide,index=1,cache=writethrough",
            "-drive", "file=" + hostIme.getAbsolutePath() + ",format=raw,if=floppy,index=0,readonly=on",
            /* Precise VGA retrace is important for early Mode X games that
             * poll port 0x3DA.  The default 'dumb' method merely toggles the
             * status bits on every read and can make timing loops race or
             * stall under TCG. */
            "-vga", "std,retrace=precise",
            "-display", "none",
            "-vnc", "127.0.0.1:" + (VNC_PORT - 5900),
            "-qmp", "tcp:127.0.0.1:" + QMP_PORT + ",server,nowait",
            "-monitor", "none",
            "-serial", "none",
            "-chardev", "socket,id=hostime,host=127.0.0.1,port="+SERIAL_PORT+",server=on,wait=off",
            "-device", "isa-serial,chardev=hostime,index=0",
            "-parallel", "none",
            "-netdev", "user,id=net",
            "-device", "pcnet,netdev=net",
            "-audiodev", audioDevice,
            "-device", "sb16,audiodev=audio,iobase=0x220,irq=5,dma=1,dma16=5",
            "-device", "adlib,audiodev=audio"
        };
        final String libName = "libqemu-system-x86_64.so";
        final String libPath = app.getApplicationInfo().nativeLibraryDir + "/" + libName;
        startedOnce = true;
        running = true;
        nativeExecutor = new VMExecutor();
        report("Starting FreeDOS with sound and optional Internet...");
        new Thread(() -> {
            try {
                String result = nativeExecutor.start(files.getAbsolutePath() + "/",
                        files.getAbsolutePath() + "/", libName, libPath, 0, argv);
                running = false;
                report(result == null ? "Emulator stopped" : result);
            } catch (Throwable error) {
                running = false;
                Log.e(TAG, "Emulator failed", error);
                report("Emulator failed: " + error.getMessage());
            } finally {
                SDLAudioManager.audioClose();
            }
        }, "FreeDOS-QEMU").start();
    }

    /** Limbo's native fopen maps /etc/resolv.conf to this app-private directory.
     * Slirp offers 10.0.2.3 to DOS and forwards queries to this upstream resolver.
     * No guest-to-host port forwards are installed. DNS needs no root access.
     */
    private static void prepareDns(Context context, File files) {
        String address = "8.8.8.8";
        try {
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = findActiveNetwork(manager);
            LinkProperties properties = network == null ? null : manager.getLinkProperties(network);
            if (properties != null) {
                for (InetAddress dns : properties.getDnsServers()) {
                    if (dns instanceof Inet4Address && !dns.isLoopbackAddress() && !dns.isAnyLocalAddress()) {
                        address = dns.getHostAddress();
                        break;
                    }
                }
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Active network DNS unavailable; using fallback resolver", error);
        }
        File directory = new File(files, "etc");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            Log.e(TAG, "Unable to create native DNS directory");
            return;
        }
        try (FileOutputStream stream = new FileOutputStream(new File(directory, "resolv.conf"))) {
            stream.write(("nameserver " + address + "\n").getBytes(StandardCharsets.US_ASCII));
            Log.i(TAG, "Guest NAT DNS upstream: " + address);
        } catch (IOException error) {
            Log.e(TAG, "Unable to prepare native DNS resolver", error);
        }
    }

    @SuppressWarnings("deprecation")
    private static Network findActiveNetwork(ConnectivityManager manager) {
        if (manager == null) return null;
        if (Build.VERSION.SDK_INT >= 23) return manager.getActiveNetwork();
        // API 21–22 expose default network info but not its Network handle.
        // Use the matching connected network only when the match is unambiguous.
        NetworkInfo active = manager.getActiveNetworkInfo();
        if (active == null || !active.isConnected()) return null;
        Network match = null;
        for (Network network : manager.getAllNetworks()) {
            NetworkInfo info = manager.getNetworkInfo(network);
            if (info == null || !info.isConnected() || info.getType() != active.getType()) continue;
            if (match != null) return null;
            match = network;
        }
        return match;
    }

    private static void loadLibraries() {
        if (librariesLoaded) return;
        System.loadLibrary("compat-limbo");
        System.loadLibrary("compat-musl");
        System.loadLibrary("glib-2.0");
        System.loadLibrary("pixman-1");
        // Optional AAudio adapter; the guest PCM path uses SDLAudioManager.
        // Android's libaaudio.so does not exist on API 21–25.
        if (Build.VERSION.SDK_INT >= 26) System.loadLibrary("compat-SDL2-addons");
        System.loadLibrary("SDL2");
        System.loadLibrary("compat-SDL2-ext");
        System.loadLibrary("limbo");
        System.loadLibrary("qemu-system-x86_64");
        librariesLoaded = true;
    }

    /** QMP command runs off the UI thread. Only loopback is used. */
    public static void command(String command, Reply reply) {
        CONTROL.execute(() -> {
            String result = null;
            Exception failure = null;
            try { result = executeQmp(command); }
            catch (Exception ex) { failure = ex; Log.w(TAG, "QMP " + command, ex); }
            final String r = result;
            final Exception e = failure;
            if (reply != null) MAIN.post(() -> reply.onReply(r, e));
        });
    }
    public static void pause() {
        SDLAudioManager.setSuspended(true);
        command("stop", null);
    }
    public static void resume() {
        command("cont", (result, error) -> {
            if (error == null) SDLAudioManager.setSuspended(false);
        });
    }
    public static void queryStatus(Reply reply) { command("query-status", reply); }
    /** ACPI request only: old DOS guests may ignore it. Prefer FDAPM POWEROFF in DOS. */
    public static void requestGuestShutdown() { command("system_powerdown", null); }
    /** End QEMU and flush host disk buffers; user must save guest work first. */
    public static void quit() { command("quit", null); }

    public static void readScreenText(Reply reply) {
        CONTROL.execute(() -> {
            String text = null; Exception failure = null;
            try {
                byte[] before = readMemory(0x440,128);
                VgaText layout = new VgaText(before);
                byte[] cells = readMemory(layout.address,layout.columns*layout.rows*2);
                VgaText after = new VgaText(readMemory(0x440,128));
                if (layout.address!=after.address || layout.rows!=after.rows || layout.columns!=after.columns)
                    throw new IOException("DOS video mode changed. Try Copy text again.");
                text = layout.decode(cells);
            } catch (Exception ex) { failure = ex; }
            final String result=text; final Exception error=failure;
            MAIN.post(() -> reply.onReply(result,error));
        });
    }
    private static byte[] readMemory(int address,int count) throws Exception {
        JSONObject arguments = new JSONObject();
        arguments.put("command-line", "xp /"+count+"bx 0x"+Integer.toHexString(address));
        String response = executeQmp("human-monitor-command", arguments);
        return VgaText.parseDump(new JSONObject(response).getString("return"),address,count);
    }
    private static String executeQmp(String name) throws Exception { return executeQmp(name,null); }
    private static String executeQmp(String name, JSONObject arguments) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", QMP_PORT), 3000);
            socket.setSoTimeout(5000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            OutputStreamWriter writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            String greeting = reader.readLine();
            if (greeting == null || !new JSONObject(greeting).has("QMP")) throw new IOException("No QMP greeting");
            writer.write("{\"execute\":\"qmp_capabilities\",\"id\":\"caps\"}\r\n");
            writer.flush();
            waitReply(reader, "caps");
            JSONObject cmd = new JSONObject();
            cmd.put("execute", name);
            if(arguments!=null)cmd.put("arguments", arguments);
            cmd.put("id", "request");
            writer.write(cmd.toString() + "\r\n");
            writer.flush();
            return waitReply(reader, "request");
        }
    }
    private static String waitReply(BufferedReader reader, String id) throws Exception {
        for (int n = 0; n < 100; n++) {
            String line = reader.readLine();
            if (line == null) throw new IOException("QMP connection closed");
            JSONObject json = new JSONObject(line);
            if (!id.equals(json.optString("id"))) continue;
            if (json.has("error")) throw new IOException(json.getJSONObject("error").optString("desc"));
            return line;
        }
        throw new IOException("Too many QMP events without a reply");
    }
}

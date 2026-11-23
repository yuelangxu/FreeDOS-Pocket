package org.freedos.pocket;

import android.app.*;
import android.os.*;
import android.content.*;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.zip.GZIPInputStream;

/** Offline FreeDOS appliance. The bundled disks are copied only on first launch. */
public class MainActivity extends Activity {
    // Android can recreate or open another Activity during first-run disk expansion.
    // The service and activities use one process; all seed writers share this lock.
    private static final Object SEED_WRITER = new Object();
    private Engine.Listener engineListener;
    private DosView display;
    private TextView status;
    private Button start;
    private boolean ready, paused, leaving, backgroundPaused;
    private boolean visible;
    private LinearLayout root;
    private final java.util.ArrayList<ToggleButton> modifierButtons = new java.util.ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        paused = state != null && state.getBoolean("userPaused", false) && Engine.isRunning();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.BLACK);
        if (Build.VERSION.SDK_INT >= 35 && getApplicationInfo().targetSdkVersion >= 35)
            Api35Insets.apply(root);
        status = new TextView(this); status.setTextColor(0xffa5efc5); status.setTextSize(12); status.setPadding(12,3,12,3);
        status.setText("FreeDOS Pocket | Preparing offline FreeDOS..."); root.addView(status);
        HorizontalScrollView scroll = new HorizontalScrollView(this); scroll.setFillViewport(false);
        LinearLayout bar = new LinearLayout(this); scroll.addView(bar);
        start = button(bar,"Start", () -> launch()); start.setEnabled(false);
        button(bar,"Keyboard", () -> display.showKeyboard());
        button(bar,"Paste", () -> previewPaste());
        button(bar,"Copy text", () -> copyScreenText());
        button(bar,"Host IME", () -> confirmHostImeInstall());
        ToggleButton mute = new ToggleButton(this);
        mute.setTextOn("Muted"); mute.setTextOff("Sound"); mute.setFocusable(false);
        mute.setChecked(getPreferences(0).getBoolean("muted",false));
        org.libsdl.app.SDLAudioManager.setMuted(mute.isChecked());
        mute.setOnCheckedChangeListener((v,on)->{
            org.libsdl.app.SDLAudioManager.setMuted(on);
            getPreferences(0).edit().putBoolean("muted",on).apply();
        });
        bar.addView(mute,new LinearLayout.LayoutParams(dp(78),-1));
        button(bar,"Reset keys", () -> resetKeys());
        button(bar,"Esc", () -> display.tapKey(0xff1b));
        button(bar,"Tab", () -> display.tapKey(0xff09));
        button(bar,"Enter", () -> display.tapKey(0xff0d));
        modifier(bar,"Ctrl",0xffe3); modifier(bar,"Alt",0xffe9); modifier(bar,"Shift",0xffe1);
        button(bar,"Up", () -> display.tapKey(0xff52)); button(bar,"Down", () -> display.tapKey(0xff54));
        button(bar,"Left", () -> display.tapKey(0xff51)); button(bar,"Right", () -> display.tapKey(0xff53));
        for (int i=1;i<=12;i++) { final int key=0xffbd+i; button(bar,"F"+i, () -> display.tapKey(key)); }
        button(bar,"Right click", () -> display.rightClick());
        button(bar,"Pause", () -> { if (!Engine.isRunning()) return; paused=!paused; if(paused)Engine.pause();else Engine.resume(); status.setText(paused?"Paused | Tap Pause to resume":"Running offline"); });
        button(bar,"Help", () -> help());
        button(bar,"Reconnect", () -> { if(Engine.isRunning())display.connect(); });
        button(bar,"Exit", () -> confirmExit());
        root.addView(scroll, new LinearLayout.LayoutParams(-1,dp(44)));
        display = new DosView(this); root.addView(display,new LinearLayout.LayoutParams(-1,0,1));
        display.setStatusListener(message -> status.setText(message));
        engineListener = (message,running) -> {
            status.setText(message);
            if(!running) {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                // Only the native engine's successful shutdown result ends the app.
                // Startup/linker failures stay visible so they can be diagnosed.
                if(message != null && "VM shutdown".equalsIgnoreCase(message.trim())) {
                    leaving=true; display.disconnect(); endProcess();
                }
            }
        };
        Engine.setListener(engineListener);
        setContentView(root);
        new Thread(() -> prepare(),"Disk-preparation").start();
    }
    private int dp(int n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    // Keep new platform classes out of the Android 5 verifier's activity path.
    private static final class Api35Insets {
        static void apply(View view) {
            view.setOnApplyWindowInsetsListener((v, insets) -> {
                // This callback can run later than onCreate. Guard at the API
                // use site as well as its registration, including on test variants.
                if (Build.VERSION.SDK_INT < 35) return insets;
                android.graphics.Insets usable = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(usable.left, usable.top, usable.right, usable.bottom);
                return WindowInsets.CONSUMED;
            });
        }
    }
    private Button button(LinearLayout bar,String label,Runnable action) { Button b=new Button(this); b.setText(label);b.setTextSize(12);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(12),0,dp(12),0);b.setFocusable(false); b.setOnClickListener(v -> action.run());bar.addView(b,new LinearLayout.LayoutParams(-2,-1));return b; }
    private void modifier(LinearLayout bar,String label,int key) { ToggleButton b=new ToggleButton(this);b.setTextOn(label+" ON");b.setTextOff(label);b.setText(label);b.setTextSize(12);b.setFocusable(false);b.setOnCheckedChangeListener((v,on)->display.sendKey(key,on));modifierButtons.add(b);bar.addView(b,new LinearLayout.LayoutParams(dp(66),-1)); }
    private void resetKeys(){if(display==null)return;for(ToggleButton b:modifierButtons)b.setChecked(false);display.releaseKeys();}
    private boolean readingScreen;
    private View boundedTextPreview(TextView content){
        ScrollView scroll=new ScrollView(this);scroll.addView(content);
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);
        int height=Math.min(dp(280),Math.max(dp(80),getResources().getDisplayMetrics().heightPixels/3));
        box.addView(scroll,new LinearLayout.LayoutParams(-1,height));return box;
    }
    private void copyScreenText(){
        if(readingScreen || !Engine.isRunning())return;
        readingScreen=true;status.setText("Reading DOS text screen...");
        Engine.readScreenText((text,error)->{
            readingScreen=false;
            if(isFinishing() || isDestroyed() || !visible)return;
            if(error!=null){new AlertDialog.Builder(this).setTitle("Cannot copy screen text").setMessage(error.getMessage()).setPositiveButton("OK",null).show();return;}
            TextView content=new TextView(this);content.setText(text);content.setTextIsSelectable(true);
            content.setTypeface(android.graphics.Typeface.MONOSPACE);content.setPadding(dp(12),dp(8),dp(12),dp(8));
            new AlertDialog.Builder(this).setTitle("DOS text snapshot (CP437)")
                .setMessage("Long-press to select and copy text, or copy all. Graphics and Chinese DOS screens are not supported yet. This is a screen snapshot, not the DOS application's internal selection.")
                .setView(boundedTextPreview(content)).setNegativeButton("Close",null)
                .setPositiveButton("Copy all",(d,w)->{
                    ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("DOS screen",text));
                    status.setText("DOS screen copied to Android clipboard");
                }).show();
        });
    }
    private void previewPaste(){
        if(!Engine.isRunning() || paused || !display.isConnected()){
            status.setText("Start or resume DOS before pasting."); return;
        }
        ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        ClipData clip=clipboard.getPrimaryClip();
        CharSequence source=clip!=null && clip.getItemCount()>0 ? clip.getItemAt(0).getText() : null;
        final String text;
        try { text=PasteText.prepare(source==null?null:source.toString()); }
        catch(IllegalArgumentException ex){new AlertDialog.Builder(this).setTitle("Cannot paste").setMessage(ex.getMessage()).setPositiveButton("OK",null).show();return;}
        TextView preview=new TextView(this);preview.setText(text);preview.setTextIsSelectable(true);preview.setPadding(dp(16),dp(8),dp(16),dp(8));
        new AlertDialog.Builder(this).setTitle("Paste into DOS?")
            .setMessage("Text is sent as keystrokes. Line breaks press Enter and may run commands. Check the current DOS program before continuing.")
            .setView(boundedTextPreview(preview)).setNegativeButton("Cancel",null)
            .setPositiveButton("Paste",(d,w)->{
                if(!Engine.isRunning() || paused || !display.isConnected()){status.setText("Paste cancelled: DOS is not ready.");return;}
                resetKeys();display.typeText(text);
            }).show();
    }
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(!focused)resetKeys();}
    private void update(String text) { ui.post(() -> { if(!isFinishing()&&!isDestroyed())status.setText(text); }); }
    private void prepare() {
        try {
            synchronized (SEED_WRITER) { prepareFiles(); }
            ui.post(() -> { if(isFinishing()||isDestroyed())return; ready=true;start.setEnabled(true);status.setText("Ready | Starting offline FreeDOS..."); if(visible)launch(); });
        } catch(Exception ex) { android.util.Log.e("FreeDOSPrepare","Disk preparation failed",ex);update("Preparation failed: "+ex.getMessage()+". Close and retry; existing disks are preserved."); }
    }
    private void prepareFiles() throws Exception {
            JSONObject manifest = new JSONObject(readAsset("seed/manifest.json"));
            File disks = new File(getFilesDir(),"disks"); if(!disks.isDirectory()&&!disks.mkdirs())throw new IOException("Cannot create disk directory");
            long need=0; for(String name:new String[]{"system","software"}) if(!new File(disks,name+".vdi").exists()) need+=Math.max(0L,manifest.getJSONObject(name).getLong("bytes")-new File(disks,name+".part").length());
            if(need>0 && new StatFs(getFilesDir().getAbsolutePath()).getAvailableBytes()<need+100*1024*1024)throw new IOException("Not enough storage to prepare the missing disks.");
            for(String name:new String[]{"system","software"}) {
                File disk=new File(disks,name+".vdi");
                if(disk.exists())continue; // Never overwrite the user's persistent modifications.
                File temp=new File(disks,name+".part");
                MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=manifest.getJSONObject(name).getLong("bytes"), count=0,last=-1;
                try(InputStream input=new GZIPInputStream(getAssets().open("seed/"+name+".vdi.bin"),131072);FileOutputStream out=new FileOutputStream(temp)) {
                    byte[] buf=new byte[131072];int n;
                    while((n=input.read(buf))!=-1) { out.write(buf,0,n);digest.update(buf,0,n);count+=n;long percent=count*100/total;if(percent!=last){last=percent;update("Preparing "+name+" disk: "+percent+"% (first launch only)");} }
                    out.getFD().sync();
                }
                StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format("%02x",b&255));
                if(count!=total||!hash.toString().equals(manifest.getJSONObject(name).getString("sha256")))throw new IOException("Disk integrity verification failed: "+name);
                if(!temp.renameTo(disk))throw new IOException("Cannot commit prepared disk");
            }
            copyAssets("bios",new File(getFilesDir(),"bios"));
            copyAssets("guest",new File(getFilesDir(),"guest"));
    }
    private String readAsset(String name)throws IOException { try(InputStream in=getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");} }
    private void copyAssets(String path,File dest)throws IOException { String[] children=getAssets().list(path);if(children!=null&&children.length>0){if(!dest.isDirectory()&&!dest.mkdirs())throw new IOException("Cannot create "+dest);for(String c:children)copyAssets(path+"/"+c,new File(dest,c));}else{if(dest.isFile())return;File part=new File(dest.getParentFile(),dest.getName()+".part");try(InputStream in=getAssets().open(path);FileOutputStream out=new FileOutputStream(part)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)out.write(b,0,n);out.getFD().sync();}if(!part.renameTo(dest))throw new IOException("Cannot commit offline asset: "+path);} }
    private void launch(){if(!ready)return;boolean existing=Engine.isRunning();Engine.start(this);if(Engine.isRunning()){if(existing&&!paused){Engine.resume();backgroundPaused=false;}startService(new Intent(this,SessionService.class));start.setVisibility(View.GONE);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);display.connect();display.requestFocus();}}
    @Override protected void onStart(){super.onStart();visible=true;if(backgroundPaused&&Engine.isRunning()){Engine.resume();backgroundPaused=false;}if(ready&&!Engine.isRunning())launch();}
    @Override protected void onStop(){visible=false;if(Engine.isRunning()&&!paused&&!leaving){backgroundPaused=true;Engine.pause();}super.onStop();}
    private void help(){
        String edition=getString(R.string.edition_description);
        String common="Runs offline after first launch, with no root and no Termux.\n\n"+edition+"\n\nKeyboard: use Keyboard and the scrolling special-key row. Width and height stretch independently to fit the available screen. ASCII uses virtual keys. For Android IME Chinese, first run Host IME at a C:\\> prompt; committed text is then acknowledged over a loopback serial bridge as GB2312. A compatible Chinese DOS display/program is still required.\n\nSound: SB16 (220, IRQ 5, DMA 1/5), AdLib and PC speaker; use the Android media volume. Configure each DOS game as needed.\n\nNetwork: outbound NAT through your Android connection. Basic HTTP sites may work; modern HTTPS and JavaScript sites may not. Avoid entering passwords in old DOS browsers. No incoming port forwards are enabled.\n\nSave inside DOS before Exit. Android may terminate background apps: save often. Uninstalling or clearing app data deletes your mobile disk changes.\n\nPreview: newer 16 KB-page Android devices and all installed DOS programs are not fully validated.\n\nEngine: Limbo 6.0.1 / QEMU 5.1.0 (GPL). Full project and license/source references accompany the distribution.";
        CharSequence label=getApplicationInfo().loadLabel(getPackageManager());
        new AlertDialog.Builder(this).setTitle(label+" - Help").setMessage(common).setPositiveButton("OK",null).show();
    }
    private void confirmHostImeInstall(){
        new AlertDialog.Builder(this).setTitle("Install host text receiver")
            .setMessage("Use only at a DOS command prompt such as C:\\>. This types A:\\HOSTIME.COM and presses Enter. It enables acknowledged host Chinese/GB2312 byte delivery for this boot; a compatible Chinese DOS program or display system is still required.")
            .setNegativeButton("Cancel",null).setPositiveButton("At prompt - Install",(d,w)->{
                resetKeys();display.typeText("A:\\HOSTIME.COM\n");
            }).show();
    }
    private void endProcess(){stopService(new Intent(this,SessionService.class));finishAndRemoveTask();android.os.Process.killProcess(android.os.Process.myPid());}
    private void confirmExit(){if(!Engine.isRunning()){endProcess();return;}new AlertDialog.Builder(this).setTitle("Save DOS work first").setMessage("Prefer running FDAPM POWEROFF inside DOS. Closing the emulator without saving may lose unsaved guest work. Exit now?").setNegativeButton("Cancel",null).setPositiveButton("Saved - Exit",(d,w)->{leaving=true;status.setText("Stopping emulator and flushing disk writes...");Engine.command("quit",(reply,error)->waitForExit(0));}).show();}
    private void waitForExit(int attempt){if(!Engine.isRunning()){display.disconnect();endProcess();return;}if(attempt>=40){leaving=false;new AlertDialog.Builder(this).setTitle("Emulator is still stopping").setMessage("The app has not forced termination, to protect your disk data. Wait and try Exit again.").setPositiveButton("OK",null).show();return;}ui.postDelayed(()->waitForExit(attempt+1),250);}
    @Override public void onBackPressed(){confirmExit();}
    @Override protected void onSaveInstanceState(Bundle state){state.putBoolean("userPaused",paused);super.onSaveInstanceState(state);}
    @Override protected void onDestroy(){Engine.clearListener(engineListener);if(display!=null)display.release();super.onDestroy();}
}

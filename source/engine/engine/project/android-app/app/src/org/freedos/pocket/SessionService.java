package org.freedos.pocket;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
/** Best-effort shutdown on task removal. Engine lives in this same process. */
public final class SessionService extends Service {
    private final Handler main = new Handler();
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int id){return START_NOT_STICKY;}
    @Override public void onTaskRemoved(Intent intent){if(!Engine.isRunning()){stopSelf();android.os.Process.killProcess(android.os.Process.myPid());return;}Engine.command("quit",(reply,error)->awaitExit(0));}
    private void awaitExit(int n){if(!Engine.isRunning()){stopSelf();android.os.Process.killProcess(android.os.Process.myPid());return;}if(n<80)main.postDelayed(()->awaitExit(n+1),250);else stopSelf();}
}

package org.freedos.pocket;

import android.os.Handler;
import android.os.Looper;
import java.io.*;
import java.net.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loopback-only, acknowledged transport to HOSTIME.COM in the DOS guest. */
final class DosSerialBridge {
    interface Callback { void finished(int accepted, Exception error); }
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private DosSerialBridge(){}
    static void send(byte[] bytes,Callback callback){
        WORKER.execute(()->{
            int accepted=0;Exception failure=null;
            try(Socket socket=new Socket()){
                socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),Engine.SERIAL_PORT),1000);
                socket.setSoTimeout(2500);socket.setTcpNoDelay(true);
                InputStream in=socket.getInputStream();OutputStream out=socket.getOutputStream();
                transfer(out,in,0); // handshake is acknowledged but never queued in DOS
                for(byte value:bytes){transfer(out,in,value&255);accepted++;}
            }catch(Exception error){failure=error;}
            final int count=accepted;final Exception error=failure;
            MAIN.post(()->callback.finished(count,error));
        });
    }
    private static void transfer(OutputStream out,InputStream in,int value)throws IOException{
        out.write(value);out.flush();int ack=in.read();
        if(ack<0)throw new EOFException("DOS serial bridge closed");
        if(ack!=value)throw new IOException("DOS serial acknowledgement mismatch");
    }
}

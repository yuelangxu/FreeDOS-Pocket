package org.libsdl.app;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;
import android.os.Build;
import android.os.SystemClock;

/** Playback-only SDL 2.0.8 JNI adapter. PCM goes directly into Android AudioTrack.
 * No microphone permission, SDL window, WebView or background audio service.
 * Exact method signatures are required by the unmodified upstream native SDL.
 */
public final class SDLAudioManager {
    private static final String TAG = "FreeDOSAudio";
    private static volatile AudioTrack track;
    private static volatile boolean nonzeroLogged;
    private static volatile long samplesWritten;
    private static volatile boolean userMuted;
    private static volatile boolean suspended;
    private static int outputRate;
    private static int outputChannels;
    private static volatile long lastWriteNanos = -1;
    private static volatile long lastPlaybackHead;
    private static volatile int lastUnderruns = -1;
    /**
     * AudioTrack accepts small writes in bursts on some Android devices (two
     * 10 ms writes immediately, then roughly 20 ms of blocking).  SDL uses a
     * completed Java write as its clock, so those bursts also make QEMU ask
     * for guest PCM in bursts.  Pace the SDL producer independently of the
     * device buffer so the guest mixer is sampled at a steady cadence.
     */
    private static volatile long nextWriteDeadlineNanos;
    private SDLAudioManager() {}
    public static native void nativeSetupJNI();

    // Keep the Builder type (introduced in API 23) out of the playback class
    // verified by Android 5's VM.
    private static final class Api23 {
        static AudioTrack open(AudioAttributes attributes, AudioFormat format, int bytes) {
            if (Build.VERSION.SDK_INT < 23) throw new IllegalStateException("AudioTrack.Builder requires API 23");
            AudioTrack.Builder builder = new AudioTrack.Builder()
                    .setAudioAttributes(attributes).setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(bytes);
            if (Build.VERSION.SDK_INT >= 26) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            }
            return builder.build();
        }
    }

    /** SDL expects zero on success, not the buffer frame count. */
    public static synchronized int audioOpen(int sampleRate, boolean is16Bit, boolean isStereo, int desiredFrames) {
        audioClose();
        int channels = isStereo ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
        int encoding = is16Bit ? AudioFormat.ENCODING_PCM_16BIT : AudioFormat.ENCODING_PCM_8BIT;
        int frameBytes = (isStereo ? 2 : 1) * (is16Bit ? 2 : 1);
        int minimum = AudioTrack.getMinBufferSize(sampleRate, channels, encoding);
        if (minimum <= 0 || desiredFrames <= 0) {
            Log.e(TAG, "Unsupported AudioTrack format or buffer");
            return -1;
        }
        AudioTrack opened = null;
        try {
            int bytes = Math.max(minimum, desiredFrames * frameBytes);
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
            AudioFormat format = new AudioFormat.Builder().setSampleRate(sampleRate)
                    .setChannelMask(channels).setEncoding(encoding).build();
            opened = Build.VERSION.SDK_INT >= 23
                    ? Api23.open(attributes, format, bytes)
                    : new AudioTrack(attributes, format, bytes, AudioTrack.MODE_STREAM,
                            AudioManager.AUDIO_SESSION_ID_GENERATE);
            if (opened.getState() != AudioTrack.STATE_INITIALIZED) {
                opened.release();
                Log.e(TAG, "AudioTrack initialization failed");
                return -1;
            }
            opened.play();
            opened.setVolume(userMuted || suspended ? 0.0f : 1.0f);
            outputRate = sampleRate;
            outputChannels = isStereo ? 2 : 1;
            nonzeroLogged = false;
            samplesWritten = 0;
            lastWriteNanos = -1; lastPlaybackHead = 0;
            lastUnderruns = Build.VERSION.SDK_INT >= 24 ? 0 : -1;
            nextWriteDeadlineNanos = 0;
            track = opened;
            Log.i(TAG, "AudioTrack playing: " + sampleRate + " Hz, "
                    + (isStereo ? "stereo" : "mono") + ", PCM" + (is16Bit ? "16" : "8")
                    + ", requestedBuffer=" + bytes + " bytes"
                    + (Build.VERSION.SDK_INT >= 23 ? ", frames=" + opened.getBufferSizeInFrames() : "")
                    + (Build.VERSION.SDK_INT >= 26 ? ", performanceMode=" + opened.getPerformanceMode() : "")
                    + ", session=" + opened.getAudioSessionId());
            return 0;
        } catch (RuntimeException failure) {
            if (opened != null) opened.release();
            Log.e(TAG, "AudioTrack open failed", failure);
            return -1;
        }
    }

    public static void audioWriteShortBuffer(short[] buffer) {
        AudioTrack output = track;
        if (output == null) return;
        AudioDiagnostics.observe(buffer, outputRate, outputChannels,
                lastWriteNanos, lastPlaybackHead, lastUnderruns);
        long writeStarted = SystemClock.elapsedRealtimeNanos();
        try {
            for (int offset = 0; offset < buffer.length && track == output;) {
                // The three-argument PCM array overload is blocking on all supported APIs.
                int count = output.write(buffer, offset, buffer.length - offset);
                if (count <= 0) { Log.w(TAG, "PCM16 write stopped: " + count); return; }
                samplesWritten += count;
                if (!nonzeroLogged) {
                    for (int i = offset; i < offset + count; i++) {
                        if (buffer[i] != 0) { logNonzero(output); break; }
                    }
                }
                offset += count;
            }
            lastWriteNanos = SystemClock.elapsedRealtimeNanos() - writeStarted;
            lastPlaybackHead = output.getPlaybackHeadPosition() & 0xffffffffL;
            if (Build.VERSION.SDK_INT >= 24) lastUnderruns = output.getUnderrunCount();
            paceProducer(buffer.length / Math.max(1, outputChannels));
        } catch (RuntimeException failure) { Log.w(TAG, "PCM16 output ended", failure); }
    }

    public static void audioWriteByteBuffer(byte[] buffer) {
        AudioTrack output = track;
        if (output == null) return;
        try {
            for (int offset = 0; offset < buffer.length && track == output;) {
                int count = output.write(buffer, offset, buffer.length - offset);
                if (count <= 0) { Log.w(TAG, "PCM8 write stopped: " + count); return; }
                samplesWritten += count;
                if (!nonzeroLogged) {
                    for (int i = offset; i < offset + count; i++) {
                        if ((buffer[i] & 255) != 128) { logNonzero(output); break; }
                    }
                }
                offset += count;
            }
            paceProducer(buffer.length / Math.max(1, outputChannels));
        } catch (RuntimeException failure) { Log.w(TAG, "PCM8 output ended", failure); }
    }

    private static void paceProducer(int frames) {
        if (frames <= 0 || outputRate <= 0) return;
        long period = (1_000_000_000L * frames) / outputRate;
        long now = SystemClock.elapsedRealtimeNanos();
        long deadline = nextWriteDeadlineNanos;
        if (deadline == 0 || now - deadline > period * 4) deadline = now;
        deadline += period;
        nextWriteDeadlineNanos = deadline;
        long remaining = deadline - SystemClock.elapsedRealtimeNanos();
        if (remaining <= 0) return;
        try {
            long millis = remaining / 1_000_000L;
            int nanos = (int) (remaining % 1_000_000L);
            Thread.sleep(millis, nanos);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            nextWriteDeadlineNanos = 0;
        }
    }

    private static void logNonzero(AudioTrack output) {
        nonzeroLogged = true;
        Log.i(TAG, "Nonzero guest PCM delivered to Android, playbackHead="
                + output.getPlaybackHeadPosition() + ", samplesWritten=" + samplesWritten);
    }

    /** App-local gain only: do not modify Android's global/media volume. */
    public static synchronized void setMuted(boolean value) {
        userMuted = value;
        applyGain();
    }
    public static boolean isMuted() { return userMuted; }
    /** Keep writes draining while paused/backgrounded to avoid blocking QEMU. */
    public static synchronized void setSuspended(boolean value) {
        suspended = value;
        applyGain();
    }
    private static void applyGain() {
        AudioTrack output = track;
        if (output == null) return;
        try { output.setVolume(userMuted || suspended ? 0.0f : 1.0f); }
        catch (IllegalStateException failure) { Log.w(TAG, "Audio gain unavailable", failure); }
    }

    public static synchronized void audioClose() {
        AudioTrack output = track;
        track = null;
        nextWriteDeadlineNanos = 0;
        if (output == null) return;
        try { output.stop(); } catch (IllegalStateException ignored) {}
        finally { output.release(); }
        Log.i(TAG, "AudioTrack closed; samplesWritten=" + samplesWritten);
    }

    // Deliberately no recording/capture support and no Android microphone access.
    public static int captureOpen(int rate, boolean pcm16, boolean stereo, int frames) { return -1; }
    public static int captureReadShortBuffer(short[] buffer, boolean blocking) { return 0; }
    public static int captureReadByteBuffer(byte[] buffer, boolean blocking) { return 0; }
    public static void captureClose() {}
}

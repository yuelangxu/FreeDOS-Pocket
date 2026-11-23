package org.libsdl.app;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Opt-in developer capture of this emulator's generated PCM, never microphone.
 * Create files/audio-capture.request to capture the next five seconds of PCM.
 * Nothing is captured by default. No disk writes occur on the audio thread;
 * after the bounded RAM recording a worker writes WAV and callback timing CSV.
 */
public final class AudioDiagnostics {
    private static final String TAG = "FreeDOSAudio";
    private static volatile boolean enabled;
    private static File directory;
    private static long nextCheck;
    private static short[] recording;
    private static int position;
    private static int rate;
    private static int channels;
    private static long started;
    private static long[] callbackTimes;
    private static int[] callbackSamples;
    private static long[] previousWriteNanos;
    private static long[] playbackHeads;
    private static int[] underruns;
    private static int callbacks;
    private AudioDiagnostics() {}

    public static void configure(Context context) {
        directory = context.getFilesDir();
        enabled = true;
    }

    static void observe(short[] pcm, int sampleRate, int channelCount,
                        long previousWriteNs, long playbackHead, int underrunCount) {
        if (!enabled) return;
        long now = SystemClock.elapsedRealtimeNanos();
        if (recording == null) {
            if (directory == null || now < nextCheck) return;
            nextCheck = now + 1000000000L;
            File trigger = new File(directory, "audio-capture.request");
            if (!trigger.isFile() || !trigger.delete()) return;
            rate = sampleRate;
            channels = channelCount;
            recording = new short[sampleRate * channelCount * 5];
            position = 0;
            callbackTimes = new long[10000];
            callbackSamples = new int[10000];
            previousWriteNanos = new long[10000];
            playbackHeads = new long[10000];
            underruns = new int[10000];
            callbacks = 0;
            started = now;
            Log.i(TAG, "Diagnostic capture started: five seconds of emulator PCM only");
        }
        if (callbacks < callbackTimes.length) {
            callbackTimes[callbacks] = now - started;
            callbackSamples[callbacks] = pcm.length;
            previousWriteNanos[callbacks] = previousWriteNs;
            playbackHeads[callbacks] = playbackHead;
            underruns[callbacks++] = underrunCount;
        }
        int count = Math.min(pcm.length, recording.length - position);
        System.arraycopy(pcm, 0, recording, position, count);
        position += count;
        if (position < recording.length) return;

        final short[] completed = recording;
        final long[] times = callbackTimes;
        final int[] sizes = callbackSamples;
        final long[] writes = previousWriteNanos;
        final long[] heads = playbackHeads;
        final int[] xruns = underruns;
        final int eventCount = callbacks;
        final int savedRate = rate;
        final int savedChannels = channels;
        final File target = directory;
        recording = null;
        callbackTimes = null;
        callbackSamples = null;
        previousWriteNanos = null; playbackHeads = null; underruns = null;
        new Thread(() -> save(target, completed, savedRate, savedChannels,
                times, sizes, writes, heads, xruns, eventCount),
                "FreeDOS-AudioDiagnostic").start();
    }

    private static void save(File directory, short[] pcm, int rate, int channels,
                             long[] times, int[] sizes, long[] writes,
                             long[] heads, int[] xruns, int callbacks) {
        try {
            File wav = new File(directory, "audio-capture.wav");
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(wav))) {
                ascii(out, "RIFF"); le32(out, 36 + pcm.length * 2); ascii(out, "WAVEfmt ");
                le32(out, 16); le16(out, 1); le16(out, channels); le32(out, rate);
                le32(out, rate * channels * 2); le16(out, channels * 2); le16(out, 16);
                ascii(out, "data"); le32(out, pcm.length * 2);
                for (short value : pcm) le16(out, value);
            }
            StringBuilder csv = new StringBuilder("elapsed_ns,samples,previous_write_ns,playback_head_frames,underruns\n");
            for (int i = 0; i < callbacks; i++) csv.append(times[i]).append(',').append(sizes[i])
                    .append(',').append(writes[i]).append(',').append(heads[i])
                    .append(',').append(xruns[i]).append('\n');
            try (OutputStream out = new FileOutputStream(new File(directory, "audio-capture-timing.csv"))) {
                out.write(csv.toString().getBytes(StandardCharsets.US_ASCII));
            }
            Log.i(TAG, "Diagnostic capture complete: " + wav.getAbsolutePath()
                    + ", callbacks=" + callbacks + ", elapsedMs="
                    + (callbacks == 0 ? 0 : times[callbacks - 1] / 1000000));
        } catch (Exception failure) { Log.e(TAG, "Diagnostic capture failed", failure); }
    }
    private static void ascii(OutputStream out, String value) throws Exception {
        out.write(value.getBytes(StandardCharsets.US_ASCII));
    }
    private static void le16(OutputStream out, int value) throws Exception {
        out.write(value & 255); out.write((value >>> 8) & 255);
    }
    private static void le32(OutputStream out, int value) throws Exception {
        le16(out, value); le16(out, value >>> 16);
    }
}

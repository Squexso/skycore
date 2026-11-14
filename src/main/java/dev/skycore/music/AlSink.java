package dev.skycore.music;

import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/**
 * One OpenAL source with a ring of buffers, played on the audio context Minecraft's sound engine created. Used from the
 * music audio thread only. If the game restarts its sound system (new device, resource reload) the context changes; the
 * source is then recreated instead of failing.
 */
final class AlSink {
    private static final int BUFFERS = 8, RATE = 48000;
    private long context;
    private int source;
    private final int[] ids = new int[BUFFERS];
    private final ArrayDeque<Integer> free = new ArrayDeque<>();
    private ByteBuffer scratch = BufferUtils.createByteBuffer(3840 * 5);
    private boolean pausedApplied;
    private float gainApplied = -1;

    /** True when a source exists on the current audio context; creates it if needed. */
    boolean ensureOpen() {
        long ctx = ALC10.alcGetCurrentContext();
        if (ctx == 0) { source = 0; return false; }
        if (source != 0 && ctx == context && AL10.alIsSource(source)) return true;
        source = 0; // the old context (and its source) is gone with it
        free.clear();
        AL10.alGetError();
        int s = AL10.alGenSources();
        if (AL10.alGetError() != AL10.AL_NO_ERROR) return false;
        for (int i = 0; i < BUFFERS; i++) ids[i] = AL10.alGenBuffers();
        if (AL10.alGetError() != AL10.AL_NO_ERROR) { AL10.alDeleteSources(s); return false; }
        source = s;
        context = ctx;
        AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE); // plays "in the head": no direction, no distance fade
        AL10.alSource3f(source, AL10.AL_POSITION, 0, 0, 0);
        for (int id : ids) free.add(id);
        pausedApplied = false;
        gainApplied = -1;
        return true;
    }

    void invalidate() { source = 0; }

    void setGain(float g) {
        if (source == 0 || Math.abs(g - gainApplied) < 0.002f) return;
        AL10.alSourcef(source, AL10.AL_GAIN, g);
        gainApplied = g;
    }

    void setPaused(boolean p) {
        if (source == 0 || p == pausedApplied) return;
        pausedApplied = p;
        if (p) { if (state() == AL10.AL_PLAYING) AL10.alSourcePause(source); }
        else if (state() == AL10.AL_PAUSED) AL10.alSourcePlay(source);
    }

    private int state() { return AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE); }

    /** Queues PCM (16 bit stereo, little endian). @return false if every buffer is still busy, so the caller waits and tries again. */
    boolean write(byte[] pcm, int len) {
        if (source == 0) return true;
        int done = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
        for (int i = 0; i < done; i++) free.add(AL10.alSourceUnqueueBuffers(source));
        Integer id = free.poll();
        if (id == null) return false;
        if (scratch.capacity() < len) scratch = BufferUtils.createByteBuffer(len);
        scratch.clear();
        scratch.put(pcm, 0, len).flip();
        AL10.alBufferData(id, AL10.AL_FORMAT_STEREO16, scratch, RATE);
        AL10.alSourceQueueBuffers(source, id);
        if (!pausedApplied && state() != AL10.AL_PLAYING) AL10.alSourcePlay(source); // first buffer, or the output ran dry
        return true;
    }

    void keepAlive() {
        if (source == 0) return;
        int done = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
        for (int i = 0; i < done; i++) free.add(AL10.alSourceUnqueueBuffers(source));
    }

    /** Drops everything that is queued (skip, seek, stop). */
    void flush() {
        if (source == 0) return;
        AL10.alSourceStop(source);
        AL10.alSourcei(source, AL10.AL_BUFFER, 0); // detaches all queued buffers
        free.clear();
        for (int id : ids) free.add(id);
    }

    void close() {
        if (source == 0 || ALC10.alcGetCurrentContext() != context) return; // context already torn down by the game
        AL10.alSourceStop(source);
        AL10.alSourcei(source, AL10.AL_BUFFER, 0);
        AL10.alDeleteSources(source);
        for (int id : ids) AL10.alDeleteBuffers(id);
        source = 0;
    }
}

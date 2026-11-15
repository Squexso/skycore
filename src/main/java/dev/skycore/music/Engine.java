package dev.skycore.music;

import com.sedmelluq.discord.lavaplayer.format.Pcm16AudioDataFormat;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import dev.skycore.core.Skycore;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Decoding and output. LavaPlayer fetches and decodes the stream on its own threads; one daemon thread ("SkyCore-Music-Audio")
 * moves the decoded 16 bit PCM into an OpenAL source, which plays through the audio device Minecraft already opened. The client
 * thread never waits for any of it. Only {@link Player} talks to this class, and {@link Player} stays free of LavaPlayer types so
 * that the library is only loaded here, on a worker thread.
 */
final class Engine {
    /** Callbacks arrive on LavaPlayer threads. */
    interface Events {
        /** The current track reached its end normally. */
        void ended();
        /** The current track broke down (decoder error, network loss while playing, stalled stream). */
        void broke(String detail);
    }

    private static final int FRAMES_PER_BUFFER = 5; // 5 x 20 ms
    private final AudioPlayerManager manager = new DefaultAudioPlayerManager();
    private final AudioPlayer player;
    private final Events events;
    private final Thread audioThread;
    private volatile boolean running = true;
    private volatile float volume = 0.6f;
    private volatile boolean flushRequested;
    private volatile boolean paused;

    Engine(Events events) {
        this.events = events;
        manager.getConfiguration().setOutputFormat(new Pcm16AudioDataFormat(2, 48000, 960, false)); // little endian, what OpenAL wants
        manager.setFrameBufferDuration(1500);
        manager.setPlayerCleanupThreshold(TimeUnit.HOURS.toMillis(12)); // default 60 s: a longer pause ended the song silently
        manager.registerSourceManager(new HttpAudioSourceManager());
        manager.registerSourceManager(new LocalAudioSourceManager());
        player = manager.createPlayer();
        player.addListener(new AudioEventAdapter() {
            @Override public void onTrackEnd(AudioPlayer p, AudioTrack t, AudioTrackEndReason reason) {
                if (reason == AudioTrackEndReason.FINISHED) events.ended();
                else if (reason == AudioTrackEndReason.LOAD_FAILED) events.broke("load failed");
                else if (reason == AudioTrackEndReason.CLEANUP) events.broke("player cleaned up");
            }
            @Override public void onTrackException(AudioPlayer p, AudioTrack t, FriendlyException e) { events.broke(String.valueOf(e.getMessage())); }
            @Override public void onTrackStuck(AudioPlayer p, AudioTrack t, long thresholdMs) { events.broke("stalled " + thresholdMs + " ms"); }
        });
        audioThread = new Thread(this::audioLoop, "SkyCore-Music-Audio");
        audioThread.setDaemon(true);
        audioThread.start();
    }

    /** Opaque handle of a probed track. */
    record Loaded(AudioTrack track, long durationMs, boolean seekable) {}

    /** Probes a stream address. Callbacks come from a LavaPlayer thread. */
    void load(String url, Consumer<Loaded> ok, Consumer<String> fail) {
        manager.loadItem(url, new AudioLoadResultHandler() {
            @Override public void trackLoaded(AudioTrack t) { ok.accept(new Loaded(t, t.getDuration(), t.isSeekable())); }
            @Override public void playlistLoaded(AudioPlaylist p) {
                if (p.getTracks().isEmpty()) fail.accept("empty");
                else { AudioTrack t = p.getTracks().get(0); ok.accept(new Loaded(t, t.getDuration(), t.isSeekable())); }
            }
            @Override public void noMatches() { fail.accept("no matches"); }
            @Override public void loadFailed(FriendlyException e) { fail.accept(String.valueOf(e.getMessage())); }
        });
    }

    /** Starts a probed track at once. {@code cut}: drop what is still queued for the output (skip), else it plays out (gapless). */
    void play(Loaded l, boolean cut) {
        if (cut) flushRequested = true;
        paused = false;
        player.setPaused(false);
        player.playTrack(l.track().makeClone());
    }

    void stop() {
        flushRequested = true;
        player.stopTrack();
    }

    void setPaused(boolean p) {
        paused = p;
        player.setPaused(p);
    }

    long position() {
        AudioTrack t = player.getPlayingTrack();
        return t == null ? 0 : t.getPosition();
    }

    boolean seek(long ms) {
        AudioTrack t = player.getPlayingTrack();
        if (t == null || !t.isSeekable()) return false;
        try { t.setPosition(Math.max(0, ms)); flushRequested = true; return true; }
        catch (RuntimeException e) { return false; }
    }

    void setVolume(float v) { volume = Math.max(0f, Math.min(1f, v)); }

    void shutdown() {
        running = false;
        try { player.stopTrack(); } catch (RuntimeException ignored) {}
        try { audioThread.join(800); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        try { manager.shutdown(); } catch (RuntimeException ignored) {}
    }

    // ------------------------------------------------------------------ audio thread
    private void audioLoop() {
        AlSink sink = new AlSink();
        byte[] chunk = new byte[3840 * FRAMES_PER_BUFFER];
        int filled = 0;
        try {
            while (running) {
                try {
                    if (!sink.ensureOpen()) { sleep(250); continue; } // Minecraft's audio device not (yet) there, or restarting
                    sink.setGain(volume * volume * masterVolume());
                    if (flushRequested) { flushRequested = false; sink.flush(); filled = 0; }
                    sink.setPaused(paused);
                    if (paused) { player.provide(); sleep(40); continue; } // returns nothing while paused, but marks the player as in use
                    AudioFrame f = player.provide(25, TimeUnit.MILLISECONDS);
                    if (f == null) { sink.keepAlive(); continue; }
                    byte[] d = f.getData();
                    if (filled + d.length > chunk.length) filled = 0; // cannot happen with a fixed format, but never overflow
                    System.arraycopy(d, 0, chunk, filled, d.length);
                    filled += d.length;
                    if (filled >= chunk.length) {
                        while (running && !flushRequested && !sink.write(chunk, filled)) sleep(8); // all buffers busy: wait until one played
                        filled = 0;
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (TimeoutException e) {
                    sink.keepAlive(); // no frame within 25 ms: nothing to play right now
                } catch (RuntimeException | LinkageError e) {
                    Skycore.LOG.warn("Music output error: {}", e.toString());
                    sink.invalidate();
                    filled = 0;
                    sleep(500);
                }
            }
        } finally {
            try { sink.close(); } catch (RuntimeException | LinkageError ignored) {}
        }
    }

    private static float masterVolume() {
        try { return Minecraft.getInstance().options.getSoundSourceVolume(SoundSource.MASTER); }
        catch (RuntimeException e) { return 1f; }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}

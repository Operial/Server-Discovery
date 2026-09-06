package com.xaria.serverdiscovery.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * On-disk cache so opening Discovery again - a fresh game session, or just
 * resizing the window, which recreates the whole screen - doesn't mean
 * re-fetching the directory and re-pinging thousands of servers from zero
 * every time. Two independent things are cached:
 *
 * <ul>
 *   <li>The merged, de-duplicated directory itself ({@link #getDirectory()}
 *   / {@link #putDirectory(List)}) - the slow part when the network is
 *   behaving badly (multi-minute timeouts observed in practice).</li>
 *   <li>Per-server ping results ({@link #getPing(String)} /
 *   {@link #putPing(String, long, int)}) - the slow part under normal
 *   conditions, purely from the volume of servers to check one at a time.</li>
 * </ul>
 *
 * <p>Retrieval is deliberately NOT gated on age - a resize, a fresh open, or
 * re-entering Discovery always shows whatever was last known immediately,
 * however old, while a live re-check runs in the background for every card
 * (see DiscoveryServerList.ServerCard) and self-corrects it within seconds
 * regardless. Age only matters for {@link #isDirectoryFresh()}, which is
 * used purely to decide whether that background re-check is worth doing at
 * all right now - never whether to show what's already cached. Getting
 * this backwards once caused a real bug: showing nothing (rather than the
 * last known directory) whenever a re-entry happened to land outside the
 * old TTL AND the live refetch also failed - two independent unlucky
 * timings that shouldn't have been able to compound into an empty screen.
 */
public final class DiscoveryCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("serverdiscovery/cache");
    private static final Gson GSON = new GsonBuilder().create();
    private static final long DIRECTORY_TTL_MILLIS = 15 * 60 * 1000L;
    private static final long SAVE_DEBOUNCE_MILLIS = 5000L;

    public record PingEntry(long pingMillis, int onlinePlayers, long timestamp) {
    }

    private record CacheFile(long directoryTimestamp, List<ServerListing> directory, Map<String, PingEntry> pings) {
    }

    private static final Map<String, PingEntry> pings = new ConcurrentHashMap<>();
    private static volatile List<ServerListing> directory = null;
    private static volatile long directoryTimestamp = 0;
    private static volatile boolean loaded = false;
    private static volatile boolean dirty = false;
    private static volatile long lastSave = 0;

    private DiscoveryCache() {
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("serverdiscovery-cache.json");
    }

    private static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path path = path();
        if (!Files.exists(path)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            CacheFile file = GSON.fromJson(reader, CacheFile.class);
            if (file != null) {
                directory = file.directory();
                directoryTimestamp = file.directoryTimestamp();
                if (file.pings() != null) {
                    pings.putAll(file.pings());
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not read serverdiscovery-cache.json, starting fresh", e);
        }
    }

    /** The last known merged directory, whatever its age - or null if never cached. */
    public static List<ServerListing> getDirectory() {
        ensureLoaded();
        return directory;
    }

    /** Whether the cached directory is recent enough that a background refresh isn't needed yet. */
    public static boolean isDirectoryFresh() {
        ensureLoaded();
        return directory != null && System.currentTimeMillis() - directoryTimestamp <= DIRECTORY_TTL_MILLIS;
    }

    public static void putDirectory(List<ServerListing> listings) {
        ensureLoaded();
        directory = listings;
        directoryTimestamp = System.currentTimeMillis();
        markDirtyAndMaybeSave();
    }

    /** The last known ping result for this address, whatever its age - or null if never checked. */
    public static PingEntry getPing(String address) {
        ensureLoaded();
        return pings.get(address);
    }

    public static void putPing(String address, long pingMillis, int onlinePlayers) {
        ensureLoaded();
        pings.put(address, new PingEntry(pingMillis, onlinePlayers, System.currentTimeMillis()));
        markDirtyAndMaybeSave();
    }

    private static void markDirtyAndMaybeSave() {
        dirty = true;
        long now = System.currentTimeMillis();
        if (now - lastSave < SAVE_DEBOUNCE_MILLIS) {
            return;
        }
        saveNow();
    }

    private static synchronized void saveNow() {
        if (!dirty) {
            return;
        }
        dirty = false;
        lastSave = System.currentTimeMillis();
        Path path = path();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(new CacheFile(directoryTimestamp, directory, pings), writer);
            }
        } catch (IOException e) {
            LOGGER.warn("Could not save serverdiscovery-cache.json", e);
        }
    }
}

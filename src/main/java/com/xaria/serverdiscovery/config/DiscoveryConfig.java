package com.xaria.serverdiscovery.config;

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
import java.util.ArrayList;
import java.util.List;

/**
 * Plain JSON config stored at {@code config/serverdiscovery.json}.
 *
 * <p>Two directory sources are merged together (de-duplicated by address):
 * {@link #mirrorServersUrl} points at a self-hosted static mirror of
 * server metadata (see {@code mirror-site/README.md} in this project for
 * how to set one up on GitHub Pages - this deliberately does NOT talk to
 * Lunar Client's own CDN, so nothing here contacts their infrastructure or
 * is bound by their terms; leave it blank to skip this source entirely),
 * and {@link #apiUrl} points at the small custom-schema API used by the
 * open-source "ServerBrowser" mod
 * (github.com/ExcessiveAmountsOfZombies/ServerBrowser) by default - leave
 * this blank too if you'd rather rely on the mirror alone.
 * {@link #additionalApiUrls} accepts more URLs in that same small schema
 * (see the README) if you find or run other lists worth adding.
 */
public class DiscoveryConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("serverdiscovery/config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DEFAULT_API_URL = "https://minecraft.multiplayerservers.net/api/v1/servers";
    private static final String DEFAULT_MIRROR_URL = "https://operial.github.io/Lunar-Mapping/servers.json";

    public String mirrorServersUrl = DEFAULT_MIRROR_URL;
    public String apiUrl = DEFAULT_API_URL;
    public List<String> additionalApiUrls = new ArrayList<>();

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("serverdiscovery.json");
    }

    public static DiscoveryConfig load() {
        Path path = configPath();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                DiscoveryConfig loaded = GSON.fromJson(reader, DiscoveryConfig.class);
                if (loaded != null) {
                    if (loaded.apiUrl == null) {
                        loaded.apiUrl = DEFAULT_API_URL;
                    }
                    if (loaded.mirrorServersUrl == null) {
                        loaded.mirrorServersUrl = DEFAULT_MIRROR_URL;
                    }
                    if (loaded.additionalApiUrls == null) {
                        loaded.additionalApiUrls = new ArrayList<>();
                    }
                    return loaded;
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Could not read serverdiscovery.json, using defaults", e);
            }
        }
        DiscoveryConfig fresh = new DiscoveryConfig();
        fresh.save();
        return fresh;
    }

    public void save() {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            LOGGER.warn("Could not save serverdiscovery.json", e);
        }
    }
}

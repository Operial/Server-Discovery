package com.xaria.serverdiscovery.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Fetches a curated server directory from a self-hosted static mirror
 * (see {@code mirror-site/} in this project) instead of contacting Lunar
 * Client's CDN directly at runtime. The mirror is a snapshot of the public
 * metadata Lunar Client publishes at github.com/LunarClient/ServerMappings
 * (freely reusable per their docs) - names, addresses, and descriptions
 * only, deliberately no logos/backgrounds (mirroring images for ~2500
 * servers would run into the gigabytes) and no calls to Lunar's own
 * infrastructure at all once the mirror is set up. Cards fall back to each
 * server's own live favicon, or the shared default thumbnail, for images.
 *
 * <p>Points at {@link DiscoveryConfig#mirrorServersUrl} - left blank, this
 * source is skipped entirely (no data, no network call).
 */
public final class ServerMappingsClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("serverdiscovery/mappings");

    private ServerMappingsClient() {
    }

    public static CompletableFuture<List<ServerListing>> fetch(String url) {
        if (url == null || url.isBlank()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return HttpUtil.fetchText(url).thenApply(ServerMappingsClient::parse);
    }

    private static List<ServerListing> parse(String body) {
        List<ServerListing> result = new ArrayList<>();
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject() || !root.getAsJsonObject().has("servers")) {
            LOGGER.warn("Unexpected servers.json shape from the mirror - skipping this source");
            return result;
        }

        JsonArray array = root.getAsJsonObject().getAsJsonArray("servers");
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            String address = getString(object, "address");
            String name = getString(object, "name");
            if (address == null || name == null) {
                continue;
            }
            String description = getString(object, "description");
            result.add(new ServerListing(name, address, 0, description == null ? "" : description, List.of(), null, null));
        }
        return result;
    }

    private static String getString(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : null;
    }
}

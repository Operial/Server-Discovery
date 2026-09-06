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
 * Fetches a JSON server list from a configurable URL. This is one of two
 * directory sources merged together (the other being
 * {@link ServerMappingsClient}) - see {@code DiscoveryScreen.refresh()}.
 *
 * <p>The primary field names below ("serverName" / "ipAddress" / "port" /
 * "description" / "tags") match the real API used by the default
 * {@code apiUrl} (github.com/ExcessiveAmountsOfZombies/ServerBrowser). A few
 * common alternate key names are also accepted so this keeps working if you
 * point it at a different or self-hosted list.
 *
 * <p>Expected shape (either a bare array, or an object with a "servers"
 * array):
 * <pre>
 * [
 *   { "serverName": "Example SMP", "ipAddress": "play.example.com", "port": 0,
 *     "description": "A friendly survival server", "tags": ["smp", "vanilla"] }
 * ]
 * </pre>
 */
public final class ServerDirectoryClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("serverdiscovery/api");

    private ServerDirectoryClient() {
    }

    /** Fetches and parses the directory off the calling thread. */
    public static CompletableFuture<List<ServerListing>> fetch(String apiUrl) {
        return HttpUtil.fetchText(apiUrl).thenApply(ServerDirectoryClient::parse);
    }

    private static List<ServerListing> parse(String body) {
        List<ServerListing> result = new ArrayList<>();
        JsonElement root = JsonParser.parseString(body);

        JsonArray array;
        if (root.isJsonArray()) {
            array = root.getAsJsonArray();
        } else if (root.isJsonObject() && root.getAsJsonObject().has("servers")) {
            array = root.getAsJsonObject().getAsJsonArray("servers");
        } else {
            LOGGER.warn("Server directory response was not a recognised shape (expected a JSON array)");
            return result;
        }

        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            ServerListing listing = parseEntry(element.getAsJsonObject());
            if (listing != null) {
                result.add(listing);
            }
        }
        return result;
    }

    private static ServerListing parseEntry(JsonObject object) {
        String name = firstString(object, "serverName", "name", "label");
        String ip = firstString(object, "ipAddress", "ip", "address", "host");
        if (name == null || ip == null || ip.isBlank()) {
            return null;
        }

        // "ip" is sometimes given as "host:port" in its own right - split it
        // out so we don't end up with a port baked into the host twice.
        int port = firstInt(object, "port");
        if (port == 0 && ip.contains(":")) {
            int idx = ip.lastIndexOf(':');
            try {
                port = Integer.parseInt(ip.substring(idx + 1));
                ip = ip.substring(0, idx);
            } catch (NumberFormatException ignored) {
                // Not actually a port suffix - leave ip as-is.
            }
        }

        String description = firstString(object, "description", "motd");
        List<String> tags = new ArrayList<>();
        if (object.has("tags") && object.get("tags").isJsonArray()) {
            for (JsonElement tag : object.getAsJsonArray("tags")) {
                if (tag.isJsonPrimitive()) {
                    tags.add(tag.getAsString());
                }
            }
        }

        return new ServerListing(name, ip, port, description == null ? "" : description, tags, null, null);
    }

    private static String firstString(JsonObject object, String... keys) {
        for (String key : keys) {
            if (object.has(key) && !object.get(key).isJsonNull() && object.get(key).isJsonPrimitive()) {
                return object.get(key).getAsString();
            }
        }
        return null;
    }

    private static int firstInt(JsonObject object, String... keys) {
        for (String key : keys) {
            if (object.has(key) && !object.get(key).isJsonNull() && object.get(key).isJsonPrimitive()) {
                try {
                    return object.get(key).getAsInt();
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }
        }
        return 0;
    }
}

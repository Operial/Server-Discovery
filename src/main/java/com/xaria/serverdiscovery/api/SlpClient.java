package com.xaria.serverdiscovery.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * A minimal, from-scratch Server List Ping (SLP) client.
 *
 * <p>Earlier versions of this mod used Minecraft's own ServerStatusPinger,
 * but its method signature has changed shape twice already while getting
 * this mod to compile against 26.2 - it's clearly the single most volatile
 * internal API touched by this project. The wire protocol underneath it,
 * by contrast, has been stable for well over a decade (every server-list
 * website and Discord bot depends on it staying that way), so implementing
 * it directly here sidesteps that churn for good.
 *
 * <p>Protocol reference: the "Server List Ping" section of wiki.vg /
 * minecraft.wiki's protocol documentation, if you want to see this laid out
 * packet-by-packet.
 */
public final class SlpClient {

    private static final int TIMEOUT_MS = 4000;

    private SlpClient() {
    }

    public record Result(long pingMillis, String motd, byte[] iconBytes, int onlinePlayers, int maxPlayers) {
    }

    /**
     * DNS resolution, split out from {@link #pingResolved} so callers can run
     * it on a separate, more generously sized pool. Real evidence from this
     * project showed why that separation matters: {@code InetAddress.getByName}
     * has no timeout of its own, and a handful of addresses with slow or
     * non-responding DNS can hang far longer than any sane ping should take.
     * Left in the same bounded pool as the actual connect-and-ping work, that
     * hang doesn't just fail one server - it permanently occupies one of a
     * limited number of worker threads, and once enough of those pile up the
     * pool is saturated and every other queued server times out without ever
     * actually being attempted (which read as "only ~40 servers ever go
     * online, even freshly, even with the cache wiped" - not a caching bug,
     * a resource-exhaustion one).
     */
    public static InetAddress resolve(String host) throws IOException {
        return InetAddress.getByName(host);
    }

    /** Convenience wrapper for callers that don't need the two stages split apart. */
    public static Result ping(String host, int port) throws IOException {
        return pingResolved(resolve(host), host, port);
    }

    public static Result pingResolved(InetAddress resolved, String host, int port) throws IOException {
        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(resolved, port), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            writePacket(out, handshakeBody(host, port));
            writePacket(out, new byte[]{0x00}); // Status Request: just packet id 0, no fields

            readVarInt(in); // response length prefix - not needed, we just read what follows
            int packetId = readVarInt(in);
            if (packetId != 0x00) {
                throw new IOException("Unexpected status response packet id " + packetId);
            }
            String json = readString(in);
            long pingMillis = System.currentTimeMillis() - start;
            return parseStatus(json, pingMillis);
        }
    }

    private static byte[] handshakeBody(String host, int port) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream body = new DataOutputStream(buffer);
        writeVarInt(body, 0x00);   // packet id
        writeVarInt(body, -1);     // protocol version - "unspecified", accepted for status requests
        writeString(body, host);
        body.writeShort(port);
        writeVarInt(body, 1);      // next state: 1 = status
        return buffer.toByteArray();
    }

    private static Result parseStatus(String json, long pingMillis) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        String motd = "";
        if (root.has("description")) {
            var description = root.get("description");
            if (description.isJsonObject() && description.getAsJsonObject().has("text")) {
                motd = description.getAsJsonObject().get("text").getAsString();
            } else if (description.isJsonPrimitive()) {
                motd = description.getAsString();
            }
        }

        int online = 0;
        int max = 0;
        if (root.has("players") && root.get("players").isJsonObject()) {
            JsonObject players = root.getAsJsonObject("players");
            if (players.has("online")) online = players.get("online").getAsInt();
            if (players.has("max")) max = players.get("max").getAsInt();
        }

        byte[] icon = null;
        if (root.has("favicon") && root.get("favicon").isJsonPrimitive()) {
            String favicon = root.get("favicon").getAsString();
            int comma = favicon.indexOf(',');
            String base64 = comma >= 0 ? favicon.substring(comma + 1) : favicon;
            try {
                icon = Base64.getDecoder().decode(base64);
            } catch (IllegalArgumentException ignored) {
                // Malformed favicon - not worth failing the whole ping over.
            }
        }

        return new Result(pingMillis, motd, icon, online, max);
    }

    private static void writePacket(DataOutputStream out, byte[] body) throws IOException {
        writeVarInt(out, body.length);
        out.write(body);
        out.flush();
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while (true) {
            if ((value & ~0x7F) == 0) {
                out.writeByte(value);
                return;
            }
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        int position = 0;
        while (true) {
            byte current = in.readByte();
            value |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) {
                break;
            }
            position += 7;
            if (position >= 32) {
                throw new IOException("VarInt too big");
            }
        }
        return value;
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = readVarInt(in);
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}

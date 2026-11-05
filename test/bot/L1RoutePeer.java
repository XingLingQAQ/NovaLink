import com.google.gson.Gson;
import com.nova.chat.common.protocol.*;
import com.nova.chat.common.protocol.packets.*;
import com.nova.chat.common.security.ChallengeHmac;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

public class L1RoutePeer {
    private static final Gson JSON = new Gson();
    private static final String CLIENT = "E2E_RoutePeer";
    private final PacketRegistry registry = NovaProtocol.createRegistry();
    private final String runId = required("E2E_RUN_ID");
    private final Path output = Path.of(required("E2E_ROUTE_PEER_FILE"));
    private final UUID playerId = UUID.randomUUID();
    private Socket socket;
    private byte[] wire;
    private long sentAtMs;

    public static void main(String[] args) throws Exception {
        new L1RoutePeer().run();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }

    private void event(String kind, Map<String, Object> fields) throws IOException {
        Map<String, Object> row = new LinkedHashMap<>(fields);
        row.put("kind", kind);
        row.put("runId", runId);
        row.putIfAbsent("atMs", System.currentTimeMillis());
        Files.writeString(output, JSON.toJson(row) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private byte[] send(Packet packet) throws IOException {
        ByteBuf payload = Unpooled.buffer();
        ByteBuf frame = Unpooled.buffer();
        try {
            registry.encode(packet, payload);
            PacketBuffer.writeVarInt(frame, payload.readableBytes());
            frame.writeBytes(payload, payload.readerIndex(), payload.readableBytes());
            byte[] bytes = new byte[frame.readableBytes()];
            frame.readBytes(bytes);
            sentAtMs = System.currentTimeMillis();
            socket.getOutputStream().write(bytes);
            socket.getOutputStream().flush();
            byte[] body = new byte[payload.readableBytes()];
            payload.getBytes(payload.readerIndex(), body);
            return body;
        } finally {
            payload.release();
            frame.release();
        }
    }

    private Packet receive() throws IOException {
        InputStream in = socket.getInputStream();
        int length = 0;
        int shift = 0;
        while (true) {
            int next = in.read();
            if (next < 0) throw new EOFException("Backend disconnected");
            if (shift >= 28) throw new IOException("Invalid frame length");
            length |= (next & 127) << shift;
            if ((next & 128) == 0) break;
            shift += 7;
        }
        if (length < 17 || length > 4 * 1024 * 1024) throw new IOException("Invalid frame size");
        wire = in.readNBytes(length);
        if (wire.length != length) throw new EOFException("Incomplete backend frame");
        ByteBuf buf = Unpooled.wrappedBuffer(wire);
        try { return registry.decode(buf); } finally { buf.release(); }
    }

    private String encoded(byte[] body) { return Base64.getEncoder().encodeToString(body); }

    private void run() throws Exception {
        try (Socket connection = new Socket()) {
            socket = connection;
            socket.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(required("E2E_ROUTE_PORT"))), 10000);
            socket.setSoTimeout(90000);
            String nonce = ChallengeHmac.generateNonceHex();
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(required("E2E_ROUTE_PASSWORD").getBytes(StandardCharsets.UTF_8)));
            send(new HandshakeInitPacket(NovaProtocol.PROTOCOL_VERSION, CLIENT, PlatformType.BUKKIT, "l1-protocol-peer", nonce));
            Packet challengePacket = receive();
            if (!(challengePacket instanceof HandshakeChallengePacket challenge)) throw new IOException("Missing auth challenge");
            HandshakeAuthenticatePacket auth = new HandshakeAuthenticatePacket(CLIENT, nonce,
                    ChallengeHmac.compute(hash, challenge.getServerNonce(), nonce));
            send(auth);
            Packet responsePacket = receive();
            if (!(responsePacket instanceof HandshakeResponsePacket response) || !response.isSuccess()
                    || !response.getRequestId().equals(auth.getRequestId())) throw new IOException("Route peer authentication failed");
            event("authenticated", Map.of("clientId", CLIENT, "requestId", auth.getRequestId().toString(), "wire", encoded(wire)));
            ChannelActionPacket join = new ChannelActionPacket(ChannelAction.JOIN, "global");
            join.addExtra("playerId", playerId.toString());
            join.addExtra("playerName", CLIENT);
            join.addExtra("platform", "bukkit");
            byte[] joined = send(join);
            event("join-sent", Map.of("requestId", join.getRequestId().toString(), "wire", encoded(joined), "playerId", playerId.toString()));
            boolean ready = false;
            while (true) {
                Packet packet = receive();
                if (packet instanceof KeepAlivePacket keepAlive) { send(keepAlive); continue; }
                if (packet instanceof ChannelActionResponsePacket result && result.getRequestId().equals(join.getRequestId())) {
                    if (!result.isSuccess() || !"global".equals(result.getChannelId())) throw new IOException("Route peer could not join global");
                    event("ready", Map.of("wire", encoded(wire), "requestId", join.getRequestId().toString(), "playerId", playerId.toString()));
                    ready = true;
                }
                if (packet instanceof ChatMessagePacket chat && ("l1-" + runId + "-out").equals(chat.getContent())) {
                    if (!ready || !required("E2E_ROUTE_SENDER").equals(chat.getSenderName())
                            || !"E2E_Client".equals(chat.getClientId()) || !"global".equals(chat.getChannelId())) {
                        throw new IOException("Unexpected game sender or channel");
                    }
                    event("chat-received", Map.of("wire", encoded(wire)));
                    ChatMessagePacket reply = new ChatMessagePacket(playerId, CLIENT, CLIENT, "global",
                            "l1-" + runId + "-reply-" + chat.getRequestId());
                    byte[] sent = send(reply);
                    event("chat-sent", Map.of("wire", encoded(sent), "atMs", sentAtMs));
                    Thread.sleep(1500);
                    event("complete", Map.of());
                    return;
                }
            }
        }
    }
}

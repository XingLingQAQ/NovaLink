package com.nova.joint.fixture;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

final class CompletionPlayers {
    static final String NODE = "novachat.feature.mention";
    static final String RECEIPT = "JOINT_COMPLETION_PERMISSION ";
    private final Map<String, Boolean> overrides = new ConcurrentHashMap<>();

    CompletionPlayers(Path config) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(config, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        String requester = properties.getProperty("requester", "");
        String target = properties.getProperty("target", "");
        if (!requester.matches("[A-Za-z0-9_]{1,16}") || !target.matches("[A-Za-z0-9_]{1,16}")
                || requester.equalsIgnoreCase(target)) {
            throw new IllegalArgumentException("players.properties needs two distinct Minecraft names");
        }
        overrides.put(requester, true);
        overrides.put(target, true);
    }

    boolean contains(String name) {
        return overrides.containsKey(name);
    }

    Boolean permission(String name, String permission) {
        return NODE.equals(permission) ? overrides.get(name) : null;
    }

    Change change(String[] args) {
        if (args.length != 4 || !"permission".equals(args[0]) || !contains(args[1])
                || !("grant".equals(args[2]) || "revoke".equals(args[2]))
                || !args[3].matches("[a-f0-9]{32}")) {
            throw new IllegalArgumentException("jointcompletion permission <configured-player> <grant|revoke> <32-hex-nonce>");
        }
        boolean allowed = "grant".equals(args[2]);
        overrides.put(args[1], allowed);
        return new Change(args[1], allowed, args[3]);
    }

    static String receipt(Change change, boolean online, boolean effective) {
        return RECEIPT + "{\"nonce\":\"" + change.nonce() + "\",\"player\":\"" + change.name()
                + "\",\"node\":\"" + NODE + "\",\"allowed\":" + change.allowed()
                + ",\"online\":" + online + ",\"effective\":" + (online ? effective : "null")
                + ",\"ok\":" + (!online || effective == change.allowed()) + "}";
    }

    record Change(String name, boolean allowed, String nonce) { }
}

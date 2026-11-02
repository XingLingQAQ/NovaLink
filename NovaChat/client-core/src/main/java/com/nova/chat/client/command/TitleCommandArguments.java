package com.nova.chat.client.command;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record TitleCommandArguments(String channelId, String title, String subtitle,
                                    int fadeIn, int stay, int fadeOut) {

    public static TitleCommandArguments parse(String[] args) {
        if (args.length < 2 || args[0].startsWith("--") || args[1].startsWith("--")) {
            throw new IllegalArgumentException("Channel and title must precede timing options");
        }
        Map<String, Integer> timings = new HashMap<>();
        List<String> subtitle = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            String token = args[i];
            if (!token.startsWith("--")) {
                subtitle.add(token);
                continue;
            }
            String name = switch (token) {
                case "--fadeIn" -> "fadeIn";
                case "--stay" -> "stay";
                case "--fadeOut" -> "fadeOut";
                default -> throw new IllegalArgumentException("Unknown title timing option");
            };
            if (timings.containsKey(name) || ++i >= args.length || !args[i].matches("[0-9]+")) {
                throw new IllegalArgumentException("Timing options require one non-negative integer each");
            }
            timings.put(name, Integer.parseInt(args[i]));
        }
        return new TitleCommandArguments(args[0], args[1], String.join(" ", subtitle),
                timings.getOrDefault("fadeIn", 10), timings.getOrDefault("stay", 70),
                timings.getOrDefault("fadeOut", 20));
    }
}

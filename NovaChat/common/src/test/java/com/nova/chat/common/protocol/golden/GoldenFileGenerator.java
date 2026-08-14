package com.nova.chat.common.protocol.golden;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Regenerates all golden byte files under {@code <repo>/test/protocol-golden}.
 *
 * <p>This is a tool, not a regular test: the gated test method only runs when
 * the environment variable {@code NOVALINK_GOLDEN_GENERATE=true} is set, e.g.
 *
 * <pre>
 *   $env:NOVALINK_GOLDEN_GENERATE = "true"
 *   .\gradlew.bat :NovaChat:common:test --tests "*GoldenFileGenerator*" --no-daemon
 * </pre>
 *
 * <p>It can also be run directly via {@link #main(String[])} with the test
 * runtime classpath.
 *
 * <p>Outputs per sample: {@code <name>.bin} (complete frame incl. VarInt
 * length prefix) and {@code <name>.json} (expected decoded field values).
 * Plus {@code manifest.json} (machine-readable index) and {@code README.md}
 * (human-readable sample list). Stale {@code .bin}/{@code .json} files that
 * no longer belong to the sample set are deleted.
 */
public class GoldenFileGenerator {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    @Test
    @EnabledIfEnvironmentVariable(named = "NOVALINK_GOLDEN_GENERATE", matches = "true")
    void regenerateGoldenFiles() throws IOException {
        generate(GoldenPaths.goldenDir());
    }

    public static void main(String[] args) throws IOException {
        generate(GoldenPaths.goldenDir());
    }

    static void generate(Path dir) throws IOException {
        Files.createDirectories(dir);
        List<GoldenSampleSet.Sample> samples = GoldenSampleSet.samples();

        Set<String> expectedFiles = new HashSet<>();
        JsonArray manifest = new JsonArray();
        StringBuilder readme = new StringBuilder();
        readme.append("# NovaProtocol v2 跨语言黄金字节（golden bytes）样本集\n\n");
        readme.append("由 Java 权威实现生成（`GoldenFileGenerator`，模块 `NovaChat:common` 测试源码）。\n");
        readme.append("每个样本包含：`<name>.bin`（完整帧：`Length(VarInt) | PacketID(1B) | RequestID(UUID 16B) | Payload`）\n");
        readme.append("与 `<name>.json`（包类型、RequestID、全部字段期望值、帧十六进制）。\n\n");
        readme.append("重新生成：设置环境变量 `NOVALINK_GOLDEN_GENERATE=true` 后运行\n");
        readme.append("`.\\gradlew.bat :NovaChat:common:test --tests \"*GoldenFileGenerator*\" --no-daemon`。\n\n");
        readme.append("字段说明：\n");
        readme.append("- `legacyWire=true`：wire 上缺省可选尾部字段（如 Handshake v1 无 serverVersion、\n");
        readme.append("  ChatMessage 无 placeholders 计数）。各语言解码必须成功，但 re-encode 会补写\n");
        readme.append("  规范尾部字段，因此不做字节比对（`reencodeExact=false`）。\n");
        readme.append("- `knownDrift`：某语言的已知行为漂移（该语言测试跳过字节比对并报告）。\n\n");
        readme.append("| # | 样本 | 包类型 | PacketID | RequestID | reencodeExact | 已知漂移语言 | 说明 |\n");
        readme.append("|---|------|--------|----------|-----------|---------------|--------------|------|\n");

        int i = 0;
        for (GoldenSampleSet.Sample sample : samples) {
            i++;
            String binName = sample.name + ".bin";
            String jsonName = sample.name + ".json";
            expectedFiles.add(binName);
            expectedFiles.add(jsonName);

            Files.write(dir.resolve(binName), sample.frame);
            Files.write(dir.resolve(jsonName),
                    (GSON.toJson(sample.json) + "\n").getBytes(StandardCharsets.UTF_8));

            JsonObject entry = new JsonObject();
            entry.addProperty("name", sample.name);
            entry.addProperty("packet", sample.json.get("packet").getAsString());
            entry.addProperty("packetId", sample.json.get("packetId").getAsInt());
            entry.addProperty("bin", binName);
            entry.addProperty("json", jsonName);
            entry.addProperty("legacyWire", sample.json.get("legacyWire").getAsBoolean());
            entry.addProperty("reencodeExact", sample.json.get("reencodeExact").getAsBoolean());
            manifest.add(entry);

            String driftLangs = sample.json.has("knownDrift")
                    ? String.join(", ", sample.json.getAsJsonObject("knownDrift").keySet())
                    : "-";
            readme.append(String.format("| %d | `%s` | %s | 0x%02X | `%s` | %s | %s | %s |%n",
                    i, sample.name,
                    sample.json.get("packet").getAsString(),
                    sample.json.get("packetId").getAsInt(),
                    sample.json.get("requestId").getAsString(),
                    sample.json.get("reencodeExact").getAsBoolean(),
                    driftLangs,
                    sample.json.get("description").getAsString()));
        }

        JsonObject manifestRoot = new JsonObject();
        manifestRoot.addProperty("protocolVersion", 2);
        manifestRoot.addProperty("frameFormat",
                "Length(VarInt) | PacketID(1B) | RequestID(UUID 16B big-endian) | Payload");
        manifestRoot.addProperty("generator",
                "NovaChat/common/src/test/java/com/nova/chat/common/protocol/golden/GoldenFileGenerator.java");
        manifestRoot.add("samples", manifest);
        Files.write(dir.resolve("manifest.json"),
                (GSON.toJson(manifestRoot) + "\n").getBytes(StandardCharsets.UTF_8));

        Files.write(dir.resolve("README.md"), readme.toString().getBytes(StandardCharsets.UTF_8));

        // Remove stale sample files from previous generations.
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.{bin,json}")) {
            for (Path p : stream) {
                String fileName = p.getFileName().toString();
                if (fileName.equals("manifest.json")) {
                    continue;
                }
                if (!expectedFiles.contains(fileName)) {
                    Files.delete(p);
                    System.out.println("[golden] deleted stale file: " + fileName);
                }
            }
        }

        System.out.println("[golden] wrote " + samples.size() + " samples to " + dir);
    }
}

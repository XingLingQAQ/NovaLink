package com.nova.link.environment;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.nova.link.database.DatabaseMigration;
import com.nova.link.database.DatabaseProvider;
import com.nova.link.database.Invitation;
import com.nova.link.database.MessageFilter;
import com.nova.link.database.MySQLProvider;
import com.nova.link.database.PostgreSQLProvider;
import com.nova.link.database.RedisProvider;
import com.nova.link.database.dialect.MigrationDialect;
import com.nova.link.database.dialect.MySQLDialect;
import com.nova.link.database.dialect.PostgreSQLDialect;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import redis.clients.jedis.Jedis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class StorageEnvironmentProbe {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final JsonObject config;
    private final String engine;
    private final String prefix;
    private final String channel = "environment_probe_" + UUID.randomUUID().toString().substring(0, 12);
    private final UUID inviter = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();
    private final UUID wrong = UUID.randomUUID();
    private final String legacyCode = code();
    private final String boundCode = code();
    private final long createdAt = System.currentTimeMillis();
    private final long expiresAt = createdAt + TimeUnit.HOURS.toMillis(1);
    private final List<Map<String, Object>> assertions = new ArrayList<>();
    private final Map<String, Object> evidence = new LinkedHashMap<>();
    private final Map<String, Object> operations = new LinkedHashMap<>();

    private StorageEnvironmentProbe(JsonObject config) {
        this.config = config;
        engine = text("engine");
        prefix = switch (engine) {
            case "mysql" -> "ENV-MYSQL";
            case "postgresql" -> "ENV-POSTGRES";
            case "redis" -> "ENV-REDIS";
            default -> throw new IllegalArgumentException("Unsupported real database engine");
        };
        evidence.put("engine", engine);
        evidence.put("verification", "real-database-provider");
        evidence.put("assertions", assertions);
        evidence.put("operations", operations);
        operations.put("target", target.toString());
        operations.put("wrong", wrong.toString());
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected private config JSON and public result JSON");
        var probe = new StorageEnvironmentProbe(JSON.fromJson(Files.readString(Path.of(args[0])), JsonObject.class));
        boolean passed = false;
        try {
            probe.run();
            passed = true;
        } finally {
            probe.evidence.put("status", passed ? "PASS" : "FAIL");
            Path result = Path.of(args[1]).toAbsolutePath();
            Files.createDirectories(result.getParent());
            Files.writeString(result, JSON.toJson(probe.evidence));
        }
    }

    private void run() throws Exception {
        Map<Integer, List<String>> oldMigrations = null;
        List<List<String>> oldInvitations = null;
        List<List<String>> oldMessages = null;
        if (!engine.equals("redis")) {
            try (HikariDataSource source = sqlSource()) {
                try (Connection connection = source.getConnection();
                     ResultSet tables = connection.getMetaData().getTables(
                             connection.getCatalog(), connection.getSchema(), "%", new String[]{"TABLE"})) {
                    if (tables.next()) throw new IllegalStateException("SQL probe requires an empty dedicated database");
                    evidence.put("server", connection.getMetaData().getDatabaseProductName()
                            + " " + connection.getMetaData().getDatabaseProductVersion());
                }
                var migration = new DatabaseMigration(source, dialect15());
                migration.migrate();
                require("V15-FIXTURE", migration.getVersion() == 15 && !hasTargetColumn(source),
                        "Production DatabaseMigration stopped at v15; recipient column absent");
                seedLegacySql(source);
                oldMigrations = migrationRows(source);
                oldInvitations = invitationRows(source);
                oldMessages = rows(source, "SELECT * FROM messages ORDER BY id");
                evidence.put("v15Migrations", oldMigrations);
                evidence.put("v15Invitations", oldInvitations);
                evidence.put("v15Messages", oldMessages);
            }
        }

        DatabaseProvider provider = provider();
        try {
            provider.initialize();
            if (engine.equals("redis")) {
                RedisCampaignProbe.verify(config);
                require("CAMPAIGN-PERSISTENCE", true,
                        "Redis campaign CRUD, ordering, status updates and complete fields survived provider reconnects");
                provider.saveInvitation(invitation(legacyCode, null));
                try (Jedis redis = redisConnection()) {
                    evidence.put("server", redis.info("server").lines()
                            .filter(line -> line.startsWith("redis_version:")).findFirst().orElseThrow());
                    JsonObject dto = JSON.fromJson(redis.get("novalink:invitation:" + legacyCode), JsonObject.class);
                    dto.remove("targetPlayerId");
                    redis.setex("novalink:invitation:" + legacyCode, 3600, JSON.toJson(dto));
                }
                require("LEGACY-DTO", provider.loadInvitation(legacyCode).orElseThrow().getTargetPlayerId() == null,
                        "Real Redis JSON without targetPlayerId loads as an unbound invitation");
            } else {
                try (HikariDataSource source = sqlSource()) {
                    var migration = new DatabaseMigration(source, dialect());
                    Map<Integer, List<String>> upgraded = migrationRows(source);
                    require("SCHEMA16", migration.getVersion() == 16 && hasTargetColumn(source)
                                    && upgraded.size() == 16 && "COMPLETED".equals(upgraded.get(16).get(2)),
                            "Production provider upgraded the existing v15 database to completed v16");
                    Map<Integer, List<String>> retained = new LinkedHashMap<>(upgraded);
                    retained.remove(16);
                    require("OLD-CHECKSUMS", oldMigrations.size() == 15 && oldMigrations.equals(retained),
                            "All 15 migration rows, checksums and timestamps unchanged");
                    List<List<String>> upgradedInvitations = invitationRows(source);
                    evidence.put("v16Invitations", upgradedInvitations);
                    require("OLD-DATA", oldInvitations.equals(upgradedInvitations)
                                    && oldMessages.equals(rows(source, "SELECT * FROM messages ORDER BY id")),
                            "v15 unbound invitation and message row survived the upgrade unchanged");
                    evidence.put("v16Migrations", upgraded);
                    evidence.put("v16Messages", rows(source, "SELECT * FROM messages ORDER BY id"));
                }
                require("LEGACY-LOAD", provider.loadInvitation(legacyCode).orElseThrow().getTargetPlayerId() == null
                                && provider.searchMessages(MessageFilter.any(), 0, 20).stream()
                                .anyMatch(row -> row.getContent().equals(channel)),
                        "Production APIs read the migrated unbound invitation and legacy message");
            }
            provider.saveInvitation(invitation(boundCode, target));
            operations.put("saved", snapshot(provider.loadInvitation(boundCode).orElseThrow()));
            operations.put("databaseSaved", databaseSnapshot());
            require("TARGET-PERSISTED", target.equals(provider.loadInvitation(boundCode).orElseThrow().getTargetPlayerId()),
                    "Recipient UUID round-tripped through the production provider");
            if (engine.equals("redis")) {
                try (Jedis redis = redisConnection()) {
                    JsonObject dto = JSON.fromJson(redis.get("novalink:invitation:" + boundCode), JsonObject.class);
                    require("TARGET-DTO", target.toString().equals(dto.get("targetPlayerId").getAsString()),
                            "Real Redis server stores the targetPlayerId DTO field");
                }
            }
            int wrongResult = provider.claimInvitationUse(boundCode, wrong, System.currentTimeMillis());
            operations.put("wrongResult", wrongResult);
            operations.put("afterWrong", snapshot(provider.loadInvitation(boundCode).orElseThrow()));
            require("WRONG-TARGET", wrongResult == 0
                            && untouched(provider.loadInvitation(boundCode).orElseThrow()),
                    "Wrong recipient atomically rejected; usedCount, used, usedBy and usedAt unchanged");
        } finally {
            provider.shutdown();
        }

        provider = provider();
        try {
            provider.initialize();
            operations.put("reconnected", snapshot(provider.loadInvitation(boundCode).orElseThrow()));
            require("RECONNECT", target.equals(provider.loadInvitation(boundCode).orElseThrow().getTargetPlayerId())
                            && untouched(provider.loadInvitation(boundCode).orElseThrow()),
                    "A new provider and connection pool retained the recipient and unconsumed state");
            DatabaseProvider active = provider;
            var workers = Executors.newFixedThreadPool(8);
            var ready = new CountDownLatch(8);
            var start = new CountDownLatch(1);
            List<Future<Integer>> claims = new ArrayList<>();
            int accepted = 0;
            try {
                for (int i = 0; i < 8; i++) {
                    claims.add(workers.submit(() -> {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Claim start timed out");
                        return active.claimInvitationUse(boundCode, target, System.currentTimeMillis());
                    }));
                }
                if (!ready.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Claim workers timed out");
                start.countDown();
                List<Integer> results = new ArrayList<>();
                for (Future<Integer> claim : claims) {
                    int result = claim.get(15, TimeUnit.SECONDS);
                    results.add(result);
                    accepted += result;
                }
                operations.put("claims", results);
            } finally {
                start.countDown();
                workers.shutdownNow();
                workers.awaitTermination(5, TimeUnit.SECONDS);
            }
            Invitation consumed = provider.loadInvitation(boundCode).orElseThrow();
            operations.put("consumed", snapshot(consumed));
            require("ATOMIC-CLAIM", accepted == 1 && consumed.getUsedCount() == 1 && consumed.isUsed()
                            && target.equals(consumed.getUsedBy()) && consumed.getUsedAt() > 0
                            && target.equals(consumed.getTargetPlayerId())
                            && provider.claimInvitationUse(boundCode, target, System.currentTimeMillis()) == 0,
                    "Eight simultaneous correct-recipient claims yielded exactly one success; replay rejected");
            require("UNBOUND-COMPAT", provider.claimInvitationUse(legacyCode, wrong, System.currentTimeMillis()) == 1
                            && provider.loadInvitation(legacyCode).orElseThrow().getTargetPlayerId() == null
                            && provider.claimInvitationUse(legacyCode, target, System.currentTimeMillis()) == 0,
                    "Migrated or legacy-DTO unbound code accepted an arbitrary recipient exactly once");
        } finally {
            provider.shutdown();
        }
        provider = provider();
        try {
            provider.initialize();
            Invitation consumed = provider.loadInvitation(boundCode).orElseThrow();
            operations.put("reconnectedConsumed", snapshot(consumed));
            operations.put("databaseConsumed", databaseSnapshot());
            require("CLAIM-PERSISTED", consumed.isUsed() && consumed.getUsedCount() == 1
                            && target.equals(consumed.getTargetPlayerId()) && target.equals(consumed.getUsedBy())
                            && provider.claimInvitationUse(boundCode, target, System.currentTimeMillis()) == 0,
                    "Consumed state and recipient survived a second provider reconnect; replay still rejected");
        } finally {
            provider.shutdown();
        }
    }

    private HikariDataSource sqlSource() {
        HikariConfig pool = new HikariConfig();
        String sslMode = text("sslMode");
        boolean mysql = engine.equals("mysql");
        pool.setJdbcUrl("jdbc:" + (mysql ? "mysql" : "postgresql") + "://" + text("host") + ":"
                + config.get("port").getAsInt() + "/" + text("database")
                + (mysql ? "?sslMode=" + sslMode + "&serverTimezone=UTC" : "?sslmode=" + sslMode));
        pool.setDriverClassName(mysql ? "com.mysql.cj.jdbc.Driver" : "org.postgresql.Driver");
        pool.setUsername(text("username"));
        pool.setPassword(text("password"));
        pool.setMaximumPoolSize(2);
        pool.setMinimumIdle(0);
        pool.setConnectionTimeout(10000);
        return new HikariDataSource(pool);
    }

    private DatabaseProvider provider() {
        String host = text("host");
        int port = config.get("port").getAsInt();
        String password = text("password");
        if (engine.equals("redis")) return new RedisProvider(host, port, password, config.get("database").getAsInt());
        boolean insecure = config.has("insecurePlaintext") && config.get("insecurePlaintext").getAsBoolean();
        return engine.equals("mysql")
                ? new MySQLProvider(host, port, text("database"), text("username"), password, 4, text("sslMode"), insecure)
                : new PostgreSQLProvider(host, port, text("database"), text("username"), password, 4, text("sslMode"), insecure);
    }

    private Jedis redisConnection() {
        Jedis redis = new Jedis(text("host"), config.get("port").getAsInt(), 5000);
        try {
            if (!text("password").isEmpty()) redis.auth(text("password"));
            redis.select(config.get("database").getAsInt());
            return redis;
        } catch (RuntimeException exception) {
            redis.close();
            throw exception;
        }
    }

    private MigrationDialect dialect() {
        return engine.equals("mysql") ? new MySQLDialect() : new PostgreSQLDialect();
    }

    private MigrationDialect dialect15() {
        return engine.equals("mysql") ? new MySQLDialect() {
            @Override public int getCurrentVersion() { return 15; }
        } : new PostgreSQLDialect() {
            @Override public int getCurrentVersion() { return 15; }
        };
    }

    private void seedLegacySql(HikariDataSource source) throws Exception {
        try (Connection connection = source.getConnection();
             PreparedStatement invite = connection.prepareStatement("INSERT INTO invitations "
                     + "(code, channel_id, inviter_id, expire_time, created_at, used, used_at, max_uses, used_count) "
                     + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)");
             PreparedStatement message = connection.prepareStatement("INSERT INTO messages "
                     + "(channel_id, sender_id, sender_name, client_id, content, created_at) VALUES (?, ?, ?, ?, ?, ?)")) {
            invite.setString(1, legacyCode);
            invite.setString(2, channel);
            invite.setString(3, inviter.toString());
            invite.setLong(4, expiresAt);
            invite.setLong(5, createdAt);
            invite.setBoolean(6, false);
            invite.setLong(7, 0);
            invite.setInt(8, 1);
            invite.setInt(9, 0);
            invite.executeUpdate();
            message.setString(1, channel);
            message.setString(2, inviter.toString());
            message.setString(3, "EnvironmentLegacy");
            message.setString(4, "environment-probe");
            message.setString(5, channel);
            message.setLong(6, createdAt);
            message.executeUpdate();
        }
    }

    private static Map<Integer, List<String>> migrationRows(HikariDataSource source) throws Exception {
        Map<Integer, List<String>> result = new LinkedHashMap<>();
        for (List<String> row : rows(source, "SELECT version, checksum, status, description, applied_at, started_at, "
                + "completed_at FROM novalink_migrations ORDER BY version")) {
            result.put(Integer.valueOf(row.get(0)), row);
        }
        return result;
    }

    private static List<List<String>> invitationRows(HikariDataSource source) throws Exception {
        return rows(source, "SELECT code, channel_id, inviter_id, expire_time, created_at, used, "
                + "used_by, used_at, max_uses, used_count, revoked_at FROM invitations ORDER BY code");
    }

    private static List<List<String>> rows(HikariDataSource source, String sql) throws Exception {
        List<List<String>> rows = new ArrayList<>();
        try (Connection connection = source.getConnection(); var statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) row.add(result.getString(i));
                rows.add(row);
            }
        }
        return rows;
    }

    private static boolean hasTargetColumn(HikariDataSource source) throws Exception {
        try (Connection connection = source.getConnection(); ResultSet columns = connection.getMetaData()
                .getColumns(connection.getCatalog(), connection.getSchema(), "invitations", "target_player_id")) {
            return columns.next();
        }
    }

    private Invitation invitation(String code, UUID recipient) {
        return new Invitation(code, channel, inviter, expiresAt, createdAt, false, null, 0, 1, 0, null, recipient);
    }

    private Map<String, Object> databaseSnapshot() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        if (engine.equals("redis")) {
            try (Jedis redis = redisConnection()) {
                JsonObject dto = JSON.fromJson(redis.get("novalink:invitation:" + boundCode), JsonObject.class);
                result.put("target", dto.get("targetPlayerId").getAsString());
                result.put("used", dto.get("used").getAsBoolean());
                result.put("usedCount", dto.get("usedCount").getAsInt());
                result.put("usedBy", dto.has("usedBy") && !dto.get("usedBy").isJsonNull() ? dto.get("usedBy").getAsString() : null);
                result.put("usedAt", dto.get("usedAt").getAsLong());
            }
        } else {
            try (HikariDataSource source = sqlSource(); Connection connection = source.getConnection();
                 PreparedStatement query = connection.prepareStatement("SELECT target_player_id, used, used_count, used_by, used_at FROM invitations WHERE code = ?")) {
                query.setString(1, boundCode);
                try (ResultSet row = query.executeQuery()) {
                    if (!row.next()) throw new IllegalStateException("Invitation missing from database");
                    result.put("target", row.getString(1));
                    result.put("used", row.getBoolean(2));
                    result.put("usedCount", row.getInt(3));
                    result.put("usedBy", row.getString(4));
                    result.put("usedAt", row.getLong(5));
                    if (row.next()) throw new IllegalStateException("Duplicate invitation in database");
                }
            }
        }
        return result;
    }

    private static Map<String, Object> snapshot(Invitation invitation) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", invitation.getTargetPlayerId() == null ? null : invitation.getTargetPlayerId().toString());
        result.put("used", invitation.isUsed());
        result.put("usedCount", invitation.getUsedCount());
        result.put("usedBy", invitation.getUsedBy() == null ? null : invitation.getUsedBy().toString());
        result.put("usedAt", invitation.getUsedAt());
        return result;
    }

    private static boolean untouched(Invitation invitation) {
        return !invitation.isUsed() && invitation.getUsedCount() == 0
                && invitation.getUsedBy() == null && invitation.getUsedAt() == 0;
    }

    private void require(String suffix, boolean pass, String detail) {
        assertions.add(Map.of("id", prefix + "-" + suffix, "pass", pass,
                "verification", "real-database-provider", "evidence", detail));
        if (!pass) throw new IllegalStateException(prefix + "-" + suffix + ": " + detail);
    }

    private String text(String name) { return config.get(name).getAsString(); }
    private static String code() { return UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(); }
}

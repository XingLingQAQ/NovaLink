package com.nova.link.integration;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * Testcontainers configuration for integration tests.
 * Manages MySQL and Redis containers for testing database operations.
 * 
 * Requirements: 24.5 - Use Testcontainers to manage MySQL/Redis dependencies
 */
public class TestContainersConfig {

    private static final String MYSQL_IMAGE = "mysql:8.0";
    private static final String REDIS_IMAGE = "redis:7-alpine";
    
    private static final String MYSQL_DATABASE = "novalink_test";
    private static final String MYSQL_USERNAME = "novalink";
    private static final String MYSQL_PASSWORD = "novalink_test_password";
    
    private static final int REDIS_PORT = 6379;

    private MySQLContainer<?> mysqlContainer;
    private GenericContainer<?> redisContainer;
    private Network network;

    /**
     * Creates a new TestContainersConfig with a shared network.
     */
    public TestContainersConfig() {
        this.network = Network.newNetwork();
    }

    /**
     * Starts the MySQL container.
     * 
     * @return the started MySQL container
     */
    public MySQLContainer<?> startMySQL() {
        if (mysqlContainer == null || !mysqlContainer.isRunning()) {
            mysqlContainer = new MySQLContainer<>(DockerImageName.parse(MYSQL_IMAGE))
                .withDatabaseName(MYSQL_DATABASE)
                .withUsername(MYSQL_USERNAME)
                .withPassword(MYSQL_PASSWORD)
                .withNetwork(network)
                .withNetworkAliases("mysql")
                .withStartupTimeout(Duration.ofMinutes(2))
                .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");
            
            mysqlContainer.start();
        }
        return mysqlContainer;
    }

    /**
     * Starts the Redis container.
     * 
     * @return the started Redis container
     */
    @SuppressWarnings("resource")
    public GenericContainer<?> startRedis() {
        if (redisContainer == null || !redisContainer.isRunning()) {
            redisContainer = new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
                .withExposedPorts(REDIS_PORT)
                .withNetwork(network)
                .withNetworkAliases("redis")
                .waitingFor(Wait.forListeningPort())
                .withStartupTimeout(Duration.ofMinutes(1));
            
            redisContainer.start();
        }
        return redisContainer;
    }

    /**
     * Starts both MySQL and Redis containers.
     */
    public void startAll() {
        startMySQL();
        startRedis();
    }

    /**
     * Stops all running containers.
     */
    public void stopAll() {
        if (mysqlContainer != null && mysqlContainer.isRunning()) {
            mysqlContainer.stop();
        }
        if (redisContainer != null && redisContainer.isRunning()) {
            redisContainer.stop();
        }
        if (network != null) {
            network.close();
        }
    }

    /**
     * Gets the MySQL JDBC URL.
     */
    public String getMySQLJdbcUrl() {
        if (mysqlContainer == null || !mysqlContainer.isRunning()) {
            throw new IllegalStateException("MySQL container is not running");
        }
        return mysqlContainer.getJdbcUrl();
    }

    /**
     * Gets the MySQL username.
     */
    public String getMySQLUsername() {
        return MYSQL_USERNAME;
    }

    /**
     * Gets the MySQL password.
     */
    public String getMySQLPassword() {
        return MYSQL_PASSWORD;
    }

    /**
     * Gets the MySQL database name.
     */
    public String getMySQLDatabase() {
        return MYSQL_DATABASE;
    }

    /**
     * Gets the MySQL host.
     */
    public String getMySQLHost() {
        if (mysqlContainer == null || !mysqlContainer.isRunning()) {
            throw new IllegalStateException("MySQL container is not running");
        }
        return mysqlContainer.getHost();
    }

    /**
     * Gets the MySQL port.
     */
    public int getMySQLPort() {
        if (mysqlContainer == null || !mysqlContainer.isRunning()) {
            throw new IllegalStateException("MySQL container is not running");
        }
        return mysqlContainer.getMappedPort(3306);
    }

    /**
     * Gets the Redis host.
     */
    public String getRedisHost() {
        if (redisContainer == null || !redisContainer.isRunning()) {
            throw new IllegalStateException("Redis container is not running");
        }
        return redisContainer.getHost();
    }

    /**
     * Gets the Redis port.
     */
    public int getRedisPort() {
        if (redisContainer == null || !redisContainer.isRunning()) {
            throw new IllegalStateException("Redis container is not running");
        }
        return redisContainer.getMappedPort(REDIS_PORT);
    }

    /**
     * Checks if MySQL container is running.
     */
    public boolean isMySQLRunning() {
        return mysqlContainer != null && mysqlContainer.isRunning();
    }

    /**
     * Checks if Redis container is running.
     */
    public boolean isRedisRunning() {
        return redisContainer != null && redisContainer.isRunning();
    }

    /**
     * Gets the shared network.
     */
    public Network getNetwork() {
        return network;
    }
}

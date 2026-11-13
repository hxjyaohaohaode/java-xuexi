package io.typeforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.typeforge.shared.JsonCodec;
import java.net.URI;
import java.sql.Connection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A prior nonlocking read must not hide a lock holder's later commit.
 * Default Maven runs use isolated H2. Only the explicit MySQL gate switches this class
 * to the task-owned MySQL database and the real SQL migrations. There is no enclosing
 * test transaction: the worker threads must own independent, committing transactions.
 */
@SpringBootTest
@ActiveProfiles("test")
class TransactionIsolationTest {
    private static final boolean MYSQL = "true".equals(System.getenv("TYPEFORGE_MYSQL_ISOLATION_TEST"));
    private static final String PROOF_PREFIX = "TYPEFORGE_ISOLATION_EVIDENCE ";
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired JsonCodec json;
    private final UUID owner = UUID.randomUUID();
    private final UUID release = UUID.randomUUID();
    private final UUID workspace = UUID.randomUUID();

    @DynamicPropertySource
    static void explicitTestDatabase(DynamicPropertyRegistry properties) {
        properties.add("typeforge.events.dispatch-enabled", () -> false);
        if (MYSQL) {
            String url = requiredEnvironment("TYPEFORGE_DB_URL");
            if (!url.startsWith("jdbc:mysql://")) throw new IllegalStateException("Explicit gate requires MySQL");
            URI target = URI.create(url.substring("jdbc:".length()));
            if (!Set.of("127.0.0.1", "localhost", "::1", "[::1]").contains(target.getHost())
                || target.getPort() < 1 || target.getPort() > 65535
                || !target.getPath().matches("/typeforge_(runtime|smoke_[a-z0-9_]{1,48})")) {
                throw new IllegalStateException("Isolation regression is restricted to a named loopback test database");
            }
            String username = requiredEnvironment("TYPEFORGE_DB_USER");
            String password = requiredEnvironment("TYPEFORGE_DB_PASSWORD");
            properties.add("spring.datasource.url", () -> url);
            properties.add("spring.datasource.username", () -> username);
            properties.add("spring.datasource.password", () -> password);
            properties.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
            properties.add("spring.flyway.locations", () -> "classpath:database");
        } else {
            properties.add("spring.datasource.url", () -> "jdbc:h2:mem:typeforge-isolation;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DEFAULT_NULL_ORDERING=HIGH");
            properties.add("spring.datasource.username", () -> "sa");
            properties.add("spring.datasource.password", () -> "");
            properties.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
            properties.add("spring.flyway.locations", () -> "classpath:io/typeforge/operations/migrations/h2");
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing explicit isolation-test setting: " + name);
        return value;
    }

    @BeforeEach
    void createOnlyThisTestsFixture() {
        tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO app_user(id,username,password_hash,roles,status) VALUES(?,?,?,'[]','ACTIVE')",
                owner.toString(), "isolation-" + owner, "NOT-A-LOGIN-CREDENTIAL");
            jdbc.update("INSERT INTO content_release(id,version,status,manifest_hash,created_by) VALUES(?,?,'PUBLISHED',?,?)",
                release.toString(), "isolation-" + release.toString().substring(0, 20), "0".repeat(64), owner.toString());
            jdbc.update("INSERT INTO learning_workspace(id,owner_id,release_id,title,guidance_mode) VALUES(?,?,?,'before','ON_DEMAND')",
                workspace.toString(), owner.toString(), release.toString());
        });
    }

    @AfterEach
    void removeOnlyThisTestsRows() {
        tx.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM learning_workspace WHERE id=? AND owner_id=?", workspace.toString(), owner.toString());
            jdbc.update("DELETE FROM content_release WHERE id=? AND created_by=?", release.toString(), owner.toString());
            jdbc.update("DELETE FROM app_user WHERE id=?", owner.toString());
        });
    }

    @Test
    void transactionTemplateUsesReadCommittedJdbcConnection() {
        Map<String,Object> proof = tx.execute(status -> jdbc.execute((ConnectionCallback<Map<String,Object>>) connection -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(connection.getAutoCommit(), "This must be the actual transaction-bound JDBC connection");
            int isolation = connection.getTransactionIsolation();
            assertEquals(Connection.TRANSACTION_READ_COMMITTED, isolation);
            String engine = connection.getMetaData().getDatabaseProductName();
            String version = connection.getMetaData().getDatabaseProductVersion();
            assertEquals(MYSQL ? "MySQL" : "H2", engine, "An explicit MySQL run must never silently exercise H2");
            String serverIsolation = "NOT_APPLICABLE_H2";
            if (MYSQL) {
                assertTrue(version.startsWith("8.4."));
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT @@session.transaction_isolation")) {
                    assertTrue(rows.next());
                    serverIsolation = rows.getString(1);
                }
                assertEquals("READ-COMMITTED", serverIsolation);
            }
            return Map.of("check", "connection-isolation", "engine", engine, "version", version,
                "jdbcIsolation", isolation, "autoCommit", connection.getAutoCommit(), "serverIsolation", serverIsolation);
        }));
        assertNotNull(proof);
        System.out.println(PROOF_PREFIX + json.write(proof));
    }

    private record Observation(String before, String after, int jdbcIsolation) {}

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void readAfterOwnerLockSeesCommittedWorkspaceChange() throws Exception {
        String repeatableReadControl = "NOT_RUN_H2";
        if (MYSQL) {
            // A separate template supplies the negative control; the shared bean is never mutated.
            var control = new TransactionTemplate(Objects.requireNonNull(tx.getTransactionManager()));
            control.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            Observation stale = runLockHandoff(control);
            assertEquals(Connection.TRANSACTION_REPEATABLE_READ, stale.jdbcIsolation());
            assertEquals("before", stale.before());
            assertEquals("before", stale.after(), "Negative control must reproduce the stale MySQL read view");
            repeatableReadControl = stale.after();
        }
        Observation current = runLockHandoff(tx);
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, current.jdbcIsolation());
        assertEquals("before", current.before());
        assertEquals("after", current.after(), "Ordinary reads after the owner lock must see the previous holder's commit");
        System.out.println(PROOF_PREFIX + json.write(Map.of("check", "read-after-owner-lock",
            "engine", MYSQL ? "MySQL" : "H2", "initial", current.before(), "afterLock", current.after(),
            "jdbcIsolation", current.jdbcIsolation(), "repeatableReadControl", repeatableReadControl,
            "writerCommitted", true)));
    }

    private Observation runLockHandoff(TransactionTemplate readerTransactions) throws Exception {
        tx.executeWithoutResult(status -> jdbc.update("UPDATE learning_workspace SET title='before' WHERE id=?", workspace.toString()));
        var firstRead = new CountDownLatch(1);
        var writerHoldsLock = new CountDownLatch(1);
        var readerAtLock = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Future<?> writer = null;
        Future<Observation> reader = null;
        try {
            reader = workers.submit(() -> readerTransactions.execute(status -> {
                Integer isolation = jdbc.execute((ConnectionCallback<Integer>) Connection::getTransactionIsolation);
                String before = title(); // Establishes MySQL's old read view under the negative-control RR setting.
                firstRead.countDown();
                await(writerHoldsLock, "writer must hold owner lock after the reader's first ordinary read");
                readerAtLock.countDown();
                jdbc.queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE", String.class, owner.toString());
                // The lock handoff orders this read after the writer commit. No sleep duration is involved.
                return new Observation(before, title(), Objects.requireNonNull(isolation));
            }));
            writer = workers.submit(() -> tx.executeWithoutResult(status -> {
                await(firstRead, "reader must establish its earlier ordinary read");
                jdbc.queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE", String.class, owner.toString());
                jdbc.update("UPDATE learning_workspace SET title='after' WHERE id=?", workspace.toString());
                writerHoldsLock.countDown();
                await(readerAtLock, "reader must reach the lock handoff before the writer commits");
            }));
            writer.get(15, TimeUnit.SECONDS); // Future completion is after TransactionTemplate committed.
            Observation observation = reader.get(15, TimeUnit.SECONDS);
            assertNotNull(observation);
            return observation;
        } finally {
            firstRead.countDown();
            writerHoldsLock.countDown();
            readerAtLock.countDown();
            if (writer != null) writer.cancel(true);
            if (reader != null) reader.cancel(true);
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS), "Concurrent fixture workers must finish before cleanup");
        }
    }

    private String title() {
        return jdbc.queryForObject("SELECT title FROM learning_workspace WHERE id=?", String.class, workspace.toString());
    }

    private static void await(CountDownLatch latch, String reason) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), reason);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(reason, interrupted);
        }
    }
}

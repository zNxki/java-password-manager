package dev.znxki.database;

import dev.znxki.models.PasswordEntity;
import lombok.NonNull;
import org.intellij.lang.annotations.Language;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class DatabaseManager {
    private static final Path DEFAULT_DB_PATH = Path.of("storage", "database.db");
    private static final int READER_POOL_SIZE = 4;
    private static final int ACQUIRE_TIMEOUT_MS = 10_000;

    private final Connection writer;
    private final ReentrantLock writeLock = new ReentrantLock(true);
    private final BlockingQueue<Connection> readers = new ArrayBlockingQueue<>(READER_POOL_SIZE);
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private boolean closed;

    @Language("SQL")
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS accounts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                username TEXT,
                password TEXT NOT NULL,
                url TEXT,
                notes TEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
            );
            """;

    @Language("SQL")
    private static final String INSERT_ACCOUNT =
            "INSERT INTO accounts (name, username, password, url, notes) " +
                    "VALUES (?, ?, ?, ?, ?)";

    @Language("SQL")
    private static final String ACCOUNT_EXISTS =
            "SELECT 1 FROM accounts WHERE name = ? COLLATE NOCASE LIMIT 1";

    @Language("SQL")
    private static final String GET_PASSWORD_BY_ID =
            "SELECT * FROM accounts WHERE id = ?";

    @Language("SQL")
    private static final String GET_PASSWORD_BY_NAME =
            "SELECT * FROM accounts WHERE name = ? COLLATE NOCASE ORDER BY id LIMIT 1";

    @Language("SQL")
    private static final String GET_ACCOUNTS =
            "SELECT * FROM accounts ORDER BY name COLLATE NOCASE, id";

    @Language("SQL")
    private static final String SEARCH_ACCOUNTS =
            "SELECT * FROM accounts " +
                    "WHERE name LIKE ? ESCAPE '\\' OR username LIKE ? ESCAPE '\\' OR url LIKE ? ESCAPE '\\' " +
                    "ORDER BY name COLLATE NOCASE, id";

    @Language("SQL")
    private static final String DELETE_ACCOUNT =
            "DELETE FROM accounts WHERE id = ?";

    public DatabaseManager() {
        this(DEFAULT_DB_PATH);
    }

    public DatabaseManager(@NotNull Path path) {
        Path file = path.toAbsolutePath();
        String url = "jdbc:sqlite:" + file;
        Connection opened = null;

        try {
            Files.createDirectories(file.getParent());
            createPrivateFile(file);

            opened = open(url, false);
            try (Statement stmt = opened.createStatement()) {
                stmt.execute("PRAGMA secure_delete = ON");
                stmt.execute(CREATE_TABLE);
            }

            for (int i = 0; i < READER_POOL_SIZE; i++)
                readers.add(open(url, true));
        } catch (SQLException | IOException e) {
            closeQuietly(opened);
            readers.forEach(DatabaseManager::closeQuietly);
            executor.close();
            throw new RuntimeException("Failed to initialize database", e);
        }

        writer = opened;
    }

    public CompletableFuture<Void> addAccount(@NonNull PasswordEntity entity) {
        return transaction(connection -> {
            if (runQueryOne(connection, ACCOUNT_EXISTS, _ -> true, entity.getName()) != null)
                throw new IllegalArgumentException("Account already exists: " + entity.getName());

            runUpdate(connection, INSERT_ACCOUNT,
                    entity.getName(), entity.getUsername(), entity.getPassword(), entity.getUrl(), entity.getNotes());
            return null;
        });
    }

    public CompletableFuture<Boolean> deleteAccount(int id) {
        return update(DELETE_ACCOUNT, id).thenApply(count -> count > 0);
    }

    public CompletableFuture<PasswordEntity> getAccountById(int id) {
        return queryOne(GET_PASSWORD_BY_ID, PasswordEntity::sqlMapper, id);
    }

    public CompletableFuture<PasswordEntity> getAccountByName(@NotNull String name) {
        return queryOne(GET_PASSWORD_BY_NAME, PasswordEntity::sqlMapper, name);
    }

    public CompletableFuture<List<PasswordEntity>> getAccounts() {
        return query(GET_ACCOUNTS, PasswordEntity::sqlMapper);
    }

    public CompletableFuture<List<PasswordEntity>> searchAccounts(@NonNull String term) {
        String escaped = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String pattern = "%" + escaped + "%";
        return query(SEARCH_ACCOUNTS, PasswordEntity::sqlMapper, pattern, pattern, pattern);
    }

    public CompletableFuture<Void> execute(String sql, Object... parameters) {
        return write(connection -> {
            runUpdate(connection, sql, parameters);
            return null;
        });
    }

    public CompletableFuture<Integer> update(String sql, Object... parameters) {
        return write(connection -> runUpdate(connection, sql, parameters));
    }

    public <T> CompletableFuture<List<T>> query(String sql, RowMapper<T> mapper, Object... parameters) {
        return read(connection -> runQuery(connection, sql, mapper, parameters));
    }

    public <T> CompletableFuture<T> queryOne(String sql, RowMapper<T> mapper, Object... parameters) {
        return read(connection -> runQueryOne(connection, sql, mapper, parameters));
    }

    public synchronized void shutdown() {
        if (closed) return;
        closed = true;

        executor.close();

        List<Connection> connections = new ArrayList<>(readers);
        readers.clear();
        connections.add(writer);

        RuntimeException failure = null;
        writeLock.lock();
        try {
            for (Connection connection : connections) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    if (failure == null) failure = new RuntimeException(e);
                    else failure.addSuppressed(e);
                }
            }
        } finally {
            writeLock.unlock();
        }

        if (failure != null) throw failure;
    }

    private <T> CompletableFuture<T> read(SqlWork<T> work) {
        return submit(() -> withReader(work));
    }

    private <T> CompletableFuture<T> write(SqlWork<T> work) {
        return submit(() -> withWriter(work));
    }

    private <T> CompletableFuture<T> transaction(SqlWork<T> work) {
        return submit(() -> inTransaction(work));
    }

    private <T> CompletableFuture<T> submit(SqlTask<T> task) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return task.run();
                } catch (SQLException e) {
                    throw new CompletionException(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CompletionException(e);
                }
            }, executor);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(new IllegalStateException("Database is closed", e));
        }
    }

    private <T> T withReader(SqlWork<T> work) throws SQLException, InterruptedException {
        Connection connection = readers.poll(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (connection == null)
            throw new SQLTimeoutException("Timed out waiting for a reader connection");

        try {
            return work.apply(connection);
        } finally {
            readers.add(connection);
        }
    }

    private <T> T withWriter(SqlWork<T> work) throws SQLException, InterruptedException {
        if (!writeLock.tryLock(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            throw new SQLTimeoutException("Timed out waiting for the write lock!");

        try {
            return work.apply(writer);
        } finally {
            writeLock.unlock();
        }
    }

    private <T> T inTransaction(SqlWork<T> work) throws SQLException, InterruptedException {
        return withWriter(connection -> {
            connection.setAutoCommit(false);
            try {
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (Throwable t) {
                try {
                    connection.rollback();
                } catch (SQLException e) {
                    t.addSuppressed(e);
                }
                throw t;
            } finally {
                connection.setAutoCommit(true);
            }
        });
    }

    private static int runUpdate(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            bind(stmt, parameters);
            return stmt.executeUpdate();
        }
    }

    private static <T> @NotNull List<T> runQuery(
            Connection connection, String sql, RowMapper<T> mapper, Object... parameters
    ) throws SQLException {
        List<T> results = new ArrayList<>();

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            bind(stmt, parameters);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) results.add(mapper.map(rs));
            }
        }

        return results;
    }

    private static <T> @Nullable T runQueryOne(
            Connection connection, String sql, RowMapper<T> mapper, Object... parameters
    ) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            bind(stmt, parameters);

            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? mapper.map(rs) : null;
            }
        }
    }

    private static void bind(PreparedStatement stmt, Object @NotNull [] parameters) throws SQLException {
        for (int i = 0; i < parameters.length; i++)
            stmt.setObject(i + 1, parameters[i]);
    }

    private static Connection open(String url, boolean readOnly) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(ACQUIRE_TIMEOUT_MS);

        if (readOnly) {
            config.setReadOnly(true);
        } else {
            config.setJournalMode(SQLiteConfig.JournalMode.WAL);
            config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        }
        return config.createConnection(url);
    }

    private static void createPrivateFile(Path file) throws IOException {
        if (Files.exists(file)) return;

        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            return;
        }

        Files.createFile(file);
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) return;

        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T apply(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    private interface SqlTask<T> {
        T run() throws SQLException, InterruptedException;
    }
}
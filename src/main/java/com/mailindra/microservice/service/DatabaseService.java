package com.mailindra.microservice.service;

import com.mailindra.microservice.model.User;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class DatabaseService {
    private final HikariDataSource dataSource;
    private final Executor dbExecutor;

    public DatabaseService() {
        // Configure HikariCP for high performance
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getProperty("db.url", "jdbc:postgresql://localhost:5432/microservice"));
        config.setUsername(System.getProperty("db.username", "user"));
        config.setPassword(System.getProperty("db.password", "password"));
        config.setMaximumPoolSize(Integer.parseInt(System.getProperty("db.pool.size", "50")));
        config.setMinimumIdle(Integer.parseInt(System.getProperty("db.pool.min", "10")));
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        config.setLeakDetectionThreshold(60000);
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        this.dataSource = new HikariDataSource(config);
        this.dbExecutor = Executors.newFixedThreadPool(
                Integer.parseInt(System.getProperty("db.threads", "20"))
        );
    }

    public CompletableFuture<Optional<User>> findUserById(Long id) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT id, username, email, first_name, last_name, created_at, updated_at " +
                    "FROM users WHERE id = ?";

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setLong(1, id);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapResultSetToUser(rs));
                    }
                    return Optional.empty();
                }
            } catch (SQLException e) {
                throw new RuntimeException("Database error while finding user", e);
            }
        }, dbExecutor);
    }

    public CompletableFuture<User> saveUser(User user) {
        return CompletableFuture.supplyAsync(() -> {
            if (user.getId() == null) {
                return insertUser(user);
            } else {
                return updateUser(user);
            }
        }, dbExecutor);
    }

    private User insertUser(User user) {
        String sql = "INSERT INTO users (username, email, first_name, last_name, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?) RETURNING id";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            LocalDateTime now = LocalDateTime.now();
            stmt.setString(1, user.getUsername());
            stmt.setString(2, user.getEmail());
            stmt.setString(3, user.getFirstName());
            stmt.setString(4, user.getLastName());
            stmt.setTimestamp(5, Timestamp.valueOf(now));
            stmt.setTimestamp(6, Timestamp.valueOf(now));

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    user.setId(rs.getLong(1));
                    user.setCreatedAt(now);
                    user.setUpdatedAt(now);
                }
                return user;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Database error while inserting user", e);
        }
    }

    private User updateUser(User user) {
        String sql = "UPDATE users SET username = ?, email = ?, first_name = ?, last_name = ?, updated_at = ? " +
                "WHERE id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            LocalDateTime now = LocalDateTime.now();
            stmt.setString(1, user.getUsername());
            stmt.setString(2, user.getEmail());
            stmt.setString(3, user.getFirstName());
            stmt.setString(4, user.getLastName());
            stmt.setTimestamp(5, Timestamp.valueOf(now));
            stmt.setLong(6, user.getId());

            int rowsAffected = stmt.executeUpdate();
            if (rowsAffected == 0) {
                throw new RuntimeException("User not found: " + user.getId());
            }

            user.setUpdatedAt(now);
            return user;
        } catch (SQLException e) {
            throw new RuntimeException("Database error while updating user", e);
        }
    }

    public CompletableFuture<Boolean> deleteUser(Long id) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "DELETE FROM users WHERE id = ?";

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setLong(1, id);
                int rowsAffected = stmt.executeUpdate();
                return rowsAffected > 0;
            } catch (SQLException e) {
                throw new RuntimeException("Database error while deleting user", e);
            }
        }, dbExecutor);
    }

    private User mapResultSetToUser(ResultSet rs) throws SQLException {
        User user = new User();
        user.setId(rs.getLong("id"));
        user.setUsername(rs.getString("username"));
        user.setEmail(rs.getString("email"));
        user.setFirstName(rs.getString("first_name"));
        user.setLastName(rs.getString("last_name"));
        user.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        user.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
        return user;
    }

    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}

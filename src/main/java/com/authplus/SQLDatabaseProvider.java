package com.authplus;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.sql.*;

/**
 * SQL-based DatabaseProvider supporting SQLite, MySQL, MariaDB, PostgreSQL, and H2.
 * Handles minor dialect differences (auto-increment, connection strings) internally.
 */
public class SQLDatabaseProvider implements DatabaseProvider {

    public enum SQLBackend {
        SQLITE, MYSQL, MARIADB, POSTGRESQL, H2
    }

    private Connection connection;
    private final SQLBackend backend;
    private final String tableName;
    private final File dataFolder;
    private final FileConfiguration config;

    public SQLDatabaseProvider(SQLBackend backend, File dataFolder, FileConfiguration config) {
        this.backend = backend;
        this.dataFolder = dataFolder;
        this.config = config;
        this.tableName = config.getString("data.table-name", "authplus_players");

        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        connect();
        createTable();
    }

    // ========================
    // Connection
    // ========================

    private void connect() {
        try {
            String url;
            String address = config.getString("data.address", "localhost");
            String database = config.getString("data.database", "authplus");
            String username = config.getString("data.username", "root");
            String password = config.getString("data.password", "");

            switch (backend) {
                case SQLITE:
                    Class.forName("org.sqlite.JDBC");
                    File dbFile = new File(dataFolder, database + ".db");
                    url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
                    connection = DriverManager.getConnection(url);
                    break;

                case MYSQL:
                    Class.forName("com.mysql.cj.jdbc.Driver");
                    url = "jdbc:mysql://" + resolveAddress(address, 3306) + "/" + database
                            + "?useSSL=false&allowPublicKeyRetrieval=true&autoReconnect=true&characterEncoding=utf8";
                    connection = DriverManager.getConnection(url, username, password);
                    break;

                case MARIADB:
                    Class.forName("org.mariadb.jdbc.Driver");
                    url = "jdbc:mariadb://" + resolveAddress(address, 3306) + "/" + database
                            + "?autoReconnect=true&characterEncoding=utf8";
                    connection = DriverManager.getConnection(url, username, password);
                    break;

                case POSTGRESQL:
                    Class.forName("org.postgresql.Driver");
                    url = "jdbc:postgresql://" + resolveAddress(address, 5432) + "/" + database;
                    connection = DriverManager.getConnection(url, username, password);
                    break;

                case H2:
                    Class.forName("org.h2.Driver");
                    File h2File = new File(dataFolder, database);
                    url = "jdbc:h2:" + h2File.getAbsolutePath() + ";MODE=MySQL";
                    connection = DriverManager.getConnection(url, username, password);
                    break;

                default:
                    throw new IllegalStateException("Unknown SQL backend: " + backend);
            }

            Bukkit.getLogger().info("[AuthPlus] Connected to " + backend.name() + " database.");

        } catch (ClassNotFoundException e) {
            Bukkit.getLogger().severe("[AuthPlus] JDBC driver not found for " + backend.name() + "!");
            Bukkit.getLogger().severe("[AuthPlus] Make sure the driver JAR is included in the plugin.");
            e.printStackTrace();
        } catch (SQLException e) {
            Bukkit.getLogger().severe("[AuthPlus] Failed to connect to " + backend.name() + " database!");
            e.printStackTrace();
        }
    }

    /**
     * Parse address — supports "host:port" format. Falls back to default port.
     */
    private String resolveAddress(String address, int defaultPort) {
        if (address.contains(":")) {
            return address; // Already has port
        }
        int configPort = config.getInt("data.port", 0);
        int port = configPort > 0 ? configPort : defaultPort;
        return address + ":" + port;
    }

    // ========================
    // Table creation (with dialect handling)
    // ========================

    private void createTable() {
        String autoIncrement;
        String textType = "TEXT";

        switch (backend) {
            case MYSQL:
            case MARIADB:
                autoIncrement = "INT NOT NULL AUTO_INCREMENT";
                textType = "VARCHAR(255)";
                break;
            case POSTGRESQL:
                autoIncrement = "SERIAL";
                break;
            case H2:
                autoIncrement = "INT AUTO_INCREMENT";
                break;
            default: // SQLITE
                autoIncrement = "INTEGER PRIMARY KEY AUTOINCREMENT";
                break;
        }

        String primaryKey;
        if (backend == SQLBackend.SQLITE) {
            // SQLite: PRIMARY KEY is in the column definition
            primaryKey = "";
        } else {
            primaryKey = ", PRIMARY KEY (id)";
        }

        String sql = "CREATE TABLE IF NOT EXISTS " + tableName + " ("
                + "id " + autoIncrement + ", "
                + "uuid " + textType + " NOT NULL UNIQUE, "
                + "username " + textType + " NOT NULL, "
                + "password TEXT NOT NULL, "
                + "ip " + textType + ", "
                + "last_login BIGINT, "
                + "registered_at BIGINT, "
                + "is_premium " + (backend == SQLBackend.POSTGRESQL ? "INTEGER" : "INTEGER") + " DEFAULT 0, "
                + "premium_uuid " + textType
                + primaryKey
                + ");";

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
            migrateAddColumn("is_premium", "INTEGER DEFAULT 0");
            migrateAddColumn("premium_uuid", textType);
        } catch (SQLException e) {
            Bukkit.getLogger().severe("[AuthPlus] Failed to create table '" + tableName + "'!");
            e.printStackTrace();
        }
    }

    private void migrateAddColumn(String columnName, String columnType) {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnType + ";");
            Bukkit.getLogger().info("[AuthPlus] Migrated database: added column '" + columnName + "'");
        } catch (SQLException e) {
            // Column already exists — expected, ignore
        }
    }

    // ========================
    // Reconnection helper
    // ========================

    private void ensureConnection() {
        try {
            if (connection == null || connection.isClosed() || !connection.isValid(3)) {
                Bukkit.getLogger().warning("[AuthPlus] Database connection lost, reconnecting...");
                connect();
            }
        } catch (SQLException e) {
            connect();
        }
    }

    // ========================
    // DatabaseProvider implementation
    // ========================

    @Override
    public boolean isRegistered(String uuid) {
        ensureConnection();
        String sql = "SELECT 1 FROM " + tableName + " WHERE uuid = ?;";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid);
            ResultSet rs = ps.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public String getPasswordHash(String uuid) {
        ensureConnection();
        String sql = "SELECT password FROM " + tableName + " WHERE uuid = ?;";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("password");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public boolean registerPlayer(String uuid, String username, String hashedPassword, String ip) {
        ensureConnection();
        String sql = "INSERT INTO " + tableName + " (uuid, username, password, ip, last_login, registered_at) "
                + "VALUES (?, ?, ?, ?, ?, ?);";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            long now = System.currentTimeMillis();
            ps.setString(1, uuid);
            ps.setString(2, username);
            ps.setString(3, hashedPassword);
            ps.setString(4, ip);
            ps.setLong(5, now);
            ps.setLong(6, now);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public void updateLastLogin(String uuid, String ip) {
        ensureConnection();
        String sql = "UPDATE " + tableName + " SET last_login = ?, ip = ? WHERE uuid = ?;";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, ip);
            ps.setString(3, uuid);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean updatePassword(String uuid, String newHashedPassword) {
        ensureConnection();
        String sql = "UPDATE " + tableName + " SET password = ? WHERE uuid = ?;";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, newHashedPassword);
            ps.setString(2, uuid);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public boolean removePlayer(String uuid) {
        ensureConnection();
        String sql = "DELETE FROM " + tableName + " WHERE uuid = ?;";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public int getRegisteredCount() {
        ensureConnection();
        String sql = "SELECT COUNT(*) FROM " + tableName + ";";
        try (Statement stmt = connection.createStatement()) {
            ResultSet rs = stmt.executeQuery(sql);
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    @Override
    public boolean setPremium(String uuid, String premiumUuid) {
        ensureConnection();
        String sql = "UPDATE " + tableName + " SET is_premium = 1, premium_uuid = ? WHERE uuid = ?;";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, premiumUuid);
            ps.setString(2, uuid);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public boolean removePremium(String uuid) {
        ensureConnection();
        String sql = "UPDATE " + tableName + " SET is_premium = 0, premium_uuid = NULL WHERE uuid = ?;";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public boolean isPremium(String uuid) {
        ensureConnection();
        String sql = "SELECT is_premium FROM " + tableName + " WHERE uuid = ?;";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getInt("is_premium") == 1;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public String getPremiumUuid(String uuid) {
        ensureConnection();
        String sql = "SELECT premium_uuid FROM " + tableName + " WHERE uuid = ?;";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("premium_uuid");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public boolean isPremiumUuidTaken(String premiumUuid) {
        ensureConnection();
        String sql = "SELECT 1 FROM " + tableName + " WHERE premium_uuid = ?;";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, premiumUuid);
            ResultSet rs = ps.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public String getUuidByPremiumUuid(String premiumUuid) {
        ensureConnection();
        String sql = "SELECT uuid FROM " + tableName + " WHERE premium_uuid = ?;";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, premiumUuid);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("uuid");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public String getUsername(String uuid) {
        ensureConnection();
        String sql = "SELECT username FROM " + tableName + " WHERE uuid = ?;";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("username");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public String getUuidByUsername(String username) {
        ensureConnection();
        String sql = "SELECT uuid FROM " + tableName + " WHERE LOWER(username) = LOWER(?);";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("uuid");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                Bukkit.getLogger().info("[AuthPlus] " + backend.name() + " database connection closed.");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean isConnected() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }
}

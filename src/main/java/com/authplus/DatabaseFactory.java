package com.authplus;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;

/**
 * Factory that creates the appropriate DatabaseProvider based on config.
 */
public class DatabaseFactory {

    /**
     * Create a DatabaseProvider based on the "data.backend" setting in config.
     *
     * @param dataFolder Plugin's data folder (for local DB files)
     * @param config     Plugin configuration
     * @return The configured DatabaseProvider
     */
    public static DatabaseProvider create(File dataFolder, FileConfiguration config) {
        String backendName = config.getString("data.backend", "SQLITE").toUpperCase().trim();

        Bukkit.getLogger().info("[AuthPlus] Initializing database backend: " + backendName);

        switch (backendName) {
            case "MYSQL":
                return new SQLDatabaseProvider(SQLDatabaseProvider.SQLBackend.MYSQL, dataFolder, config);

            case "MARIADB":
                return new SQLDatabaseProvider(SQLDatabaseProvider.SQLBackend.MARIADB, dataFolder, config);

            case "POSTGRESQL":
            case "POSTGRES":
                return new SQLDatabaseProvider(SQLDatabaseProvider.SQLBackend.POSTGRESQL, dataFolder, config);

            case "H2":
                return new SQLDatabaseProvider(SQLDatabaseProvider.SQLBackend.H2, dataFolder, config);

            case "MONGODB":
            case "MONGO":
                return new MongoDBDatabaseProvider(config);

            case "SQLITE":
            default:
                if (!backendName.equals("SQLITE")) {
                    Bukkit.getLogger().warning("[AuthPlus] Unknown backend '" + backendName + "', falling back to SQLITE.");
                }
                return new SQLDatabaseProvider(SQLDatabaseProvider.SQLBackend.SQLITE, dataFolder, config);
        }
    }
}

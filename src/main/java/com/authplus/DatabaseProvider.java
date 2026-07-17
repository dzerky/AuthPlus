package com.authplus;

/**
 * Interface defining all database operations for AuthPlus.
 * Implemented by SQLDatabaseProvider (SQLite, MySQL, MariaDB, PostgreSQL, H2)
 * and MongoDBDatabaseProvider.
 */
public interface DatabaseProvider {

    boolean isRegistered(String uuid);

    String getPasswordHash(String uuid);

    boolean registerPlayer(String uuid, String username, String hashedPassword, String ip);

    void updateLastLogin(String uuid, String ip);

    boolean updatePassword(String uuid, String newHashedPassword);

    boolean removePlayer(String uuid);

    int getRegisteredCount();

    boolean setPremium(String uuid, String premiumUuid);

    boolean removePremium(String uuid);

    boolean isPremium(String uuid);

    String getPremiumUuid(String uuid);

    boolean isPremiumUuidTaken(String premiumUuid);

    String getUuidByPremiumUuid(String premiumUuid);

    /**
     * Get the username stored for a given UUID (for admin commands).
     */
    String getUsername(String uuid);

    /**
     * Find a player's UUID by their username (case-insensitive, for admin commands).
     */
    String getUuidByUsername(String username);

    void close();

    boolean isConnected();
}

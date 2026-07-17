package com.authplus;

import com.mongodb.client.*;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Updates;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * MongoDB-based DatabaseProvider for AuthPlus.
 */
public class MongoDBDatabaseProvider implements DatabaseProvider {

    private MongoClient client;
    private MongoDatabase mongoDatabase;
    private MongoCollection<Document> collection;
    private final String collectionName;

    public MongoDBDatabaseProvider(FileConfiguration config) {
        this.collectionName = config.getString("data.table-name", "authplus_players");

        String address = config.getString("data.address", "localhost");
        String database = config.getString("data.database", "authplus");
        String username = config.getString("data.username", "");
        String password = config.getString("data.password", "");
        int configPort = config.getInt("data.port", 0);
        int port = configPort > 0 ? configPort : 27017;

        try {
            String connectionString;
            if (username != null && !username.isEmpty()) {
                connectionString = "mongodb://" + username + ":" + password + "@"
                        + resolveAddress(address, port) + "/" + database
                        + "?authSource=" + database;
            } else {
                connectionString = "mongodb://" + resolveAddress(address, port) + "/" + database;
            }

            MongoClientSettings settings = MongoClientSettings.builder()
                    .applyConnectionString(new ConnectionString(connectionString))
                    .build();

            client = MongoClients.create(settings);
            mongoDatabase = client.getDatabase(database);
            collection = mongoDatabase.getCollection(collectionName);

            // Create unique index on uuid
            collection.createIndex(Indexes.ascending("uuid"), new IndexOptions().unique(true));

            Bukkit.getLogger().info("[AuthPlus] Connected to MongoDB database.");
        } catch (Exception e) {
            Bukkit.getLogger().severe("[AuthPlus] Failed to connect to MongoDB!");
            e.printStackTrace();
        }
    }

    private String resolveAddress(String address, int defaultPort) {
        if (address.contains(":")) {
            return address;
        }
        return address + ":" + defaultPort;
    }

    // ========================
    // DatabaseProvider implementation
    // ========================

    @Override
    public boolean isRegistered(String uuid) {
        try {
            return collection.find(Filters.eq("uuid", uuid)).first() != null;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public String getPasswordHash(String uuid) {
        try {
            Document doc = collection.find(Filters.eq("uuid", uuid)).first();
            return doc != null ? doc.getString("password") : null;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public boolean registerPlayer(String uuid, String username, String hashedPassword, String ip) {
        try {
            long now = System.currentTimeMillis();
            Document doc = new Document()
                    .append("uuid", uuid)
                    .append("username", username)
                    .append("password", hashedPassword)
                    .append("ip", ip)
                    .append("last_login", now)
                    .append("registered_at", now)
                    .append("is_premium", 0)
                    .append("premium_uuid", null);
            collection.insertOne(doc);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public void updateLastLogin(String uuid, String ip) {
        try {
            collection.updateOne(
                    Filters.eq("uuid", uuid),
                    Updates.combine(
                            Updates.set("last_login", System.currentTimeMillis()),
                            Updates.set("ip", ip)
                    )
            );
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean updatePassword(String uuid, String newHashedPassword) {
        try {
            return collection.updateOne(
                    Filters.eq("uuid", uuid),
                    Updates.set("password", newHashedPassword)
            ).getModifiedCount() > 0;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public boolean removePlayer(String uuid) {
        try {
            return collection.deleteOne(Filters.eq("uuid", uuid)).getDeletedCount() > 0;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public int getRegisteredCount() {
        try {
            return (int) collection.countDocuments();
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    @Override
    public boolean setPremium(String uuid, String premiumUuid) {
        try {
            return collection.updateOne(
                    Filters.eq("uuid", uuid),
                    Updates.combine(
                            Updates.set("is_premium", 1),
                            Updates.set("premium_uuid", premiumUuid)
                    )
            ).getModifiedCount() > 0;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public boolean removePremium(String uuid) {
        try {
            return collection.updateOne(
                    Filters.eq("uuid", uuid),
                    Updates.combine(
                            Updates.set("is_premium", 0),
                            Updates.set("premium_uuid", null)
                    )
            ).getModifiedCount() > 0;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public boolean isPremium(String uuid) {
        try {
            Document doc = collection.find(Filters.eq("uuid", uuid)).first();
            return doc != null && doc.getInteger("is_premium", 0) == 1;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public String getPremiumUuid(String uuid) {
        try {
            Document doc = collection.find(Filters.eq("uuid", uuid)).first();
            return doc != null ? doc.getString("premium_uuid") : null;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public boolean isPremiumUuidTaken(String premiumUuid) {
        try {
            return collection.find(Filters.eq("premium_uuid", premiumUuid)).first() != null;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public String getUuidByPremiumUuid(String premiumUuid) {
        try {
            Document doc = collection.find(Filters.eq("premium_uuid", premiumUuid)).first();
            return doc != null ? doc.getString("uuid") : null;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public String getUsername(String uuid) {
        try {
            Document doc = collection.find(Filters.eq("uuid", uuid)).first();
            return doc != null ? doc.getString("username") : null;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public String getUuidByUsername(String username) {
        try {
            // Case-insensitive search with regex
            Document doc = collection.find(
                    Filters.regex("username", "^" + java.util.regex.Pattern.quote(username) + "$", "i")
            ).first();
            return doc != null ? doc.getString("uuid") : null;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public void close() {
        try {
            if (client != null) {
                client.close();
                Bukkit.getLogger().info("[AuthPlus] MongoDB connection closed.");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean isConnected() {
        try {
            if (client == null) return false;
            // Quick ping
            mongoDatabase.runCommand(new Document("ping", 1));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

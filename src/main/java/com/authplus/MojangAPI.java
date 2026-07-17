package com.authplus;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Utility class for interacting with Mojang's API to verify premium accounts.
 *
 * Endpoints used:
 *   - GET https://api.mojang.com/users/profiles/minecraft/<username>
 *     Returns 200 + JSON {"id":"<uuid>","name":"<name>"} if the username exists as a paid account.
 *     Returns 404/204 if the username does not exist.
 */
public class MojangAPI {

    private static final String PROFILE_URL = "https://api.mojang.com/users/profiles/minecraft/";

    /**
     * Result of a Mojang API lookup.
     */
    public static class MojangProfile {
        private final String premiumUuid; // UUID without dashes, as returned by Mojang
        private final String name;        // Correct-cased username

        public MojangProfile(String premiumUuid, String name) {
            this.premiumUuid = premiumUuid;
            this.name = name;
        }

        public String getPremiumUuid() {
            return premiumUuid;
        }

        public String getName() {
            return name;
        }

        /**
         * Returns the premium UUID with dashes inserted (standard UUID format).
         */
        public String getFormattedUuid() {
            if (premiumUuid == null || premiumUuid.length() != 32) return premiumUuid;
            return premiumUuid.substring(0, 8) + "-"
                    + premiumUuid.substring(8, 12) + "-"
                    + premiumUuid.substring(12, 16) + "-"
                    + premiumUuid.substring(16, 20) + "-"
                    + premiumUuid.substring(20);
        }
    }

    /**
     * Look up a username on Mojang's API.
     *
     * @param username The Minecraft username to check
     * @return MojangProfile if the username belongs to a paid account, null otherwise
     */
    public static MojangProfile lookupUsername(String username) {
        try {
            URL url = new URL(PROFILE_URL + username);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int responseCode = conn.getResponseCode();

            if (responseCode == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                JsonObject json = JsonParser.parseString(response.toString()).getAsJsonObject();
                String id = json.get("id").getAsString();
                String name = json.get("name").getAsString();
                return new MojangProfile(id, name);
            }

            // 404 or 204 = username not found (not a premium account)
            return null;

        } catch (Exception e) {
            Bukkit.getLogger().warning("[AuthPlus] Failed to query Mojang API for '" + username + "': " + e.getMessage());
            return null;
        }
    }

    /**
     * Check if a username exists as a premium Minecraft account.
     */
    public static boolean isPremiumUsername(String username) {
        return lookupUsername(username) != null;
    }
}

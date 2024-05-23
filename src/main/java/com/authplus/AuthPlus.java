package com.authplus;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class AuthPlus extends JavaPlugin implements Listener {

    private Map<UUID, String> registeredPlayers = new HashMap<>();
    private Map<UUID, Long> authTimeouts = new HashMap<>();
    private Map<UUID, Boolean> frozenPlayers = new HashMap<>();
    private FileConfiguration config;
    private FileConfiguration messages;
    private FileConfiguration storage;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        config = getConfig();
        config.options().copyDefaults(true);
        saveConfig();

        // Create messages.yml file if it doesn't exist
        File messagesFile = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            try {
                messagesFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        messages = YamlConfiguration.loadConfiguration(messagesFile);

        // Set default messages if file is empty
        if (messages.getKeys(true).isEmpty()) {
            messages.set("register-prompt", "&ePlease register with /register <password>");
            messages.set("registered", "&aYou have successfully registered!");
            messages.set("login-prompt", "&ePlease log in with /login <password>");
            messages.set("login-success", "&aYou have successfully logged in!");
            messages.set("login-failed", "&cInvalid password!");
            messages.set("password-too-short", "&cPassword is too short!");
            messages.set("banned-password", "&cThis password is not allowed!");
            messages.set("already-registered", "&cYou are already registered!");
            messages.set("not-registered", "&cYou are not registered!");
            messages.set("timeout-kick", "&cYou didn't register or log in in time!");
            saveMessages();
        }

        // Create storage.yml file if it doesn't exist
        File storageFile= new File(getDataFolder(), "storage.yml");
        if (!storageFile.exists()) {
            try {
                storageFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        storage = YamlConfiguration.loadConfiguration(storageFile);

        // Vypíše zprávu do konzole
        Bukkit.getLogger().info("AuthPlus has been enabled!");
        Bukkit.getLogger().info("Developed by: dzerky_jerky");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (storage.contains(uuid.toString())) {
            String password = storage.getString(uuid.toString());
            registeredPlayers.put(uuid, password);
            player.sendMessage(colorize(messages.getString("login-prompt")));
            freezePlayer(player);
            player.setInvisible(true);
            authTimeouts.put(uuid, System.currentTimeMillis() + config.getInt("kick-timeout") * 1000);
            updateLevel(player, authTimeouts.get(uuid));
        } else {
            player.sendMessage(colorize(messages.getString("register-prompt")));
            freezePlayer(player);
            if (config.getBoolean("invisible-mode")) {
                player.setInvisible(true);
            }
            authTimeouts.put(uuid, System.currentTimeMillis() + config.getInt("kick-timeout") * 1000);
            updateLevel(player, authTimeouts.get(uuid));
        }
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String command = event.getMessage();
        UUID uuid = player.getUniqueId();

        if (command.startsWith("/register")) {
            if (registeredPlayers.containsKey(uuid)) {
                player.sendMessage(colorize(messages.getString("already-registered")));
                return;
            }
            String password = command.split(" ")[1];
            if (password.length() < config.getInt("min-password-length")) {
                player.sendMessage(colorize(messages.getString("password-too-short")));
                return;
            }
            if (isBannedPassword(password)) {
                player.sendMessage(colorize(messages.getString("banned-password")));
                return;
            }
            registeredPlayers.put(uuid, password);
            storage.set(uuid.toString(), password);
            saveStorage();
            player.sendMessage(colorize(messages.getString("registered")));
            unfreezePlayer(player);
            player.setInvisible(false);
            authTimeouts.remove(uuid);
            player.setLevel(0);
        } else if(command.startsWith("/login")) {
            if (!registeredPlayers.containsKey(uuid)) {
                player.sendMessage(colorize(messages.getString("not-registered")));
                return;
            }
            String password = command.split(" ")[1];
            if (registeredPlayers.get(uuid).equals(password)) {
                authTimeouts.remove(uuid);
                player.sendMessage(colorize(messages.getString("login-success")));
                unfreezePlayer(player);
                player.setInvisible(false);
                player.setLevel(0);
            } else {
                player.sendMessage(colorize(messages.getString("login-failed")));
            }
        } else if (command.startsWith("/premium")) {
            // todo: implement premium mode
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (frozenPlayers.containsKey(uuid) && frozenPlayers.get(uuid)) {
            event.setCancelled(true);
            if (System.currentTimeMillis() > authTimeouts.get(uuid)) {
                player.kickPlayer(colorize(messages.getString("timeout-kick")));
            }
        }
    }

    private boolean isBannedPassword(String password) {
        for (String bannedPassword : config.getStringList("banned-passwords")) {
            if (password.equalsIgnoreCase(bannedPassword)) {
                return true;
            }
        }
        return false;
    }

    private void freezePlayer(Player player) {
        UUID uuid = player.getUniqueId();
        frozenPlayers.put(uuid, true);
    }

    private void unfreezePlayer(Player player) {
        UUID uuid = player.getUniqueId();
        frozenPlayers.put(uuid, false);
    }

    private void updateLevel(Player player, long timeout) {
        int secondsLeft = (int) ((timeout - System.currentTimeMillis()) / 1000);
        if (secondsLeft < 0) {
            secondsLeft = 0;
        }
        player.setLevel(secondsLeft);
        Bukkit.getScheduler().runTaskLater(this, () -> updateLevel(player, timeout), 20);
    }

    private String colorize(String message){
        return message.replace("&", "§");
    }

    private void saveMessages() {
        try {
            messages.save(new File(getDataFolder(), "messages.yml"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void saveStorage() {
        try {
            storage.save(new File(getDataFolder(), "storage.yml"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void saveLoginData(UUID uuid, long loginTime) {
        File loginDataFile = new File(getDataFolder(), config.getString("login-data-file"));
        FileConfiguration loginData = YamlConfiguration.loadConfiguration(loginDataFile);

        loginData.set(uuid.toString() + ".login-time", loginTime);

        try {
            loginData.save(loginDataFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public long loadLoginData(UUID uuid) {
        File loginDataFile = new File(getDataFolder(), config.getString("login-data-file"));
        FileConfiguration loginData = YamlConfiguration.loadConfiguration(loginDataFile);

        return loginData.getLong(uuid.toString() + ".login-time");
    }
}

package com.authplus;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Sound;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class AuthPlus extends JavaPlugin implements Listener {

    private Set<UUID> authenticatedPlayers = new HashSet<>();
    private Map<UUID, Long> authTimeouts = new HashMap<>();
    private Map<UUID, Boolean> frozenPlayers = new HashMap<>();
    private Map<UUID, Location> preLoginLocations = new HashMap<>();
    private Set<UUID> pendingPremiumCheck = new HashSet<>();
    private FileConfiguration config;
    private FileConfiguration messages;
    private DatabaseProvider database;
    private PasswordHasher passwordHasher;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        config = getConfig();
        config.options().copyDefaults(true);
        saveConfig();

        // Initialize database from config (SQLite, MySQL, MariaDB, PostgreSQL, H2, MongoDB)
        database = DatabaseFactory.create(getDataFolder(), config);

        // Initialize password hasher from config
        passwordHasher = new PasswordHasher(config);
        Bukkit.getLogger().info("[AuthPlus] Using hash algorithm: " + passwordHasher.getAlgorithm().name());

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
            messages.set("setlogin-success", "&aLogin spawn location has been set!");
            messages.set("setlogin-no-permission", "&cYou don't have permission to do this!");
            messages.set("setlogin-console", "&cThis command can only be used in-game!");
            messages.set("premium-enabled", "&aYour account has been marked as premium! You will be auto-logged in on next join.");
            messages.set("premium-disabled", "&eYour premium status has been removed. You will need to use /login next time.");
            messages.set("premium-not-eligible", "&cYour username is not a premium Minecraft account!");
            messages.set("premium-must-login", "&cYou must be logged in to use this command!");
            messages.set("premium-already", "&eYou already have premium status enabled!");
            messages.set("premium-not-active", "&eYou don't have premium status active.");
            messages.set("premium-checking", "&eVerifying your account with Mojang...");
            messages.set("premium-error", "&cFailed to verify your account. Please try again later.");
            messages.set("premium-autologin", "&aWelcome back! You have been auto-logged in as a premium player.");
            messages.set("premium-spoofing", "&cPremium verification failed. Please log in with /login <password>");
            messages.set("changepassword-success", "&aYour password has been changed successfully!");
            messages.set("changepassword-wrong", "&cYour current password is incorrect!");
            messages.set("changepassword-usage", "&eUsage: /changepassword <current password> <new password>");
            messages.set("changepassword-must-login", "&cYou must be logged in to change your password!");
            messages.set("unregister-success", "&aYour account has been unregistered.");
            messages.set("unregister-admin-success", "&aPlayer %player% has been unregistered.");
            messages.set("unregister-not-found", "&cPlayer not found in the database!");
            messages.set("unregister-usage", "&eUsage: /unregister (from in-game) or /authplus:unregister <player> (from console)");
            messages.set("unregister-must-login", "&cYou must be logged in to unregister!");
            messages.set("unregister-no-permission", "&cYou don't have permission to unregister other players!");
            saveMessages();
        }

        Bukkit.getLogger().info("AuthPlus has been enabled!");
        Bukkit.getLogger().info("Developed by: dzerky_jerky");
    }

    @Override
    public void onDisable() {
        if (database != null) {
            database.close();
        }
        Bukkit.getLogger().info("AuthPlus has been disabled!");
    }

    // ========================
    // Player Join — Premium auto-login check
    // ========================

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String uuidStr = uuid.toString();

        // Save current location before teleporting
        preLoginLocations.put(uuid, player.getLocation().clone());

        // Teleport to login spawn if configured
        Location loginSpawn = getLoginSpawnLocation();
        if (loginSpawn != null) {
            player.teleport(loginSpawn);
        }

        if (database.isRegistered(uuidStr)) {
            // Check if player has premium status — try auto-login
            if (database.isPremium(uuidStr)) {
                freezePlayer(player);
                player.setInvisible(true);
                pendingPremiumCheck.add(uuid);
                player.sendMessage(colorize(messages.getString("premium-checking")));

                // Run Mojang check asynchronously
                Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                    String storedPremiumUuid = database.getPremiumUuid(uuidStr);
                    MojangAPI.MojangProfile profile = MojangAPI.lookupUsername(player.getName());

                    Bukkit.getScheduler().runTask(this, () -> {
                        pendingPremiumCheck.remove(uuid);
                        if (!player.isOnline()) return;

                        if (profile != null && profile.getPremiumUuid().equals(storedPremiumUuid)) {
                            String ip = getPlayerIp(player);
                            database.updateLastLogin(uuidStr, ip);
                            authenticatedPlayers.add(uuid);
                            player.sendMessage(colorize(messages.getString("premium-autologin")));
                            completeAuth(player, uuid);
                            playSound(player, config.getString("login-sound"));
                        } else {
                            player.sendMessage(colorize(messages.getString("premium-spoofing")));
                            player.sendMessage(colorize(messages.getString("login-prompt")));
                            startAuthTimeout(player, uuid);
                        }
                    });
                });
            } else {
                player.sendMessage(colorize(messages.getString("login-prompt")));
                freezePlayer(player);
                player.setInvisible(true);
                startAuthTimeout(player, uuid);
            }
        } else {
            player.sendMessage(colorize(messages.getString("register-prompt")));
            freezePlayer(player);
            if (config.getBoolean("invisible-mode")) {
                player.setInvisible(true);
            }
            startAuthTimeout(player, uuid);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        authenticatedPlayers.remove(uuid);
        authTimeouts.remove(uuid);
        frozenPlayers.remove(uuid);
        preLoginLocations.remove(uuid);
        pendingPremiumCheck.remove(uuid);
    }

    private void startAuthTimeout(Player player, UUID uuid) {
        authTimeouts.put(uuid, System.currentTimeMillis() + config.getInt("kick-timeout") * 1000L);
        player.setLevel((int) ((authTimeouts.get(uuid) - System.currentTimeMillis()) / 1000));
        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            if (authTimeouts.containsKey(uuid)) {
                long timeLeft = authTimeouts.get(uuid) - System.currentTimeMillis();
                if (timeLeft <= 0) {
                    player.kickPlayer(colorize(messages.getString("timeout-kick")));
                    authTimeouts.remove(uuid);
                } else {
                    player.setLevel((int) (timeLeft / 1000));
                }
            }
        }, 0, 20);
    }

    // ========================
    // Chat command handling (register, login, premium, cracked, changepassword, unregister)
    // ========================

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String command = event.getMessage();
        UUID uuid = player.getUniqueId();
        String uuidStr = uuid.toString();

        if (command.startsWith("/register")) {
            event.setCancelled(true);
            handleRegister(player, command, uuid, uuidStr);

        } else if (command.startsWith("/login")) {
            event.setCancelled(true);
            handleLogin(player, command, uuid, uuidStr);

        } else if (command.startsWith("/premium") || command.startsWith("/autologin")) {
            event.setCancelled(true);
            handlePremium(player, uuid, uuidStr);

        } else if (command.startsWith("/cracked") || command.startsWith("/unpremium")) {
            event.setCancelled(true);
            handleCracked(player, uuid, uuidStr);

        } else if (command.startsWith("/changepassword") || command.startsWith("/changepw")
                || command.startsWith("/authplus:changepassword")) {
            event.setCancelled(true);
            handleChangePassword(player, command, uuid, uuidStr);

        } else if (command.startsWith("/unregister") || command.startsWith("/authplus:unregister")) {
            event.setCancelled(true);
            handleUnregisterPlayer(player, command, uuid, uuidStr);

        } else {
            // Block all other commands if not authenticated
            if (!authenticatedPlayers.contains(uuid)
                    && (database.isRegistered(uuidStr) || frozenPlayers.getOrDefault(uuid, false))) {
                event.setCancelled(true);
            }
        }
    }

    private void handleRegister(Player player, String command, UUID uuid, String uuidStr) {
        if (database.isRegistered(uuidStr)) {
            player.sendMessage(colorize(messages.getString("already-registered")));
            return;
        }
        String[] parts = command.split(" ");
        if (parts.length < 2) {
            player.sendMessage(colorize(messages.getString("register-prompt")));
            return;
        }
        String password = parts[1];
        if (password.length() < config.getInt("min-password-length")) {
            player.sendMessage(colorize(messages.getString("password-too-short")));
            return;
        }
        if (isBannedPassword(password)) {
            player.sendMessage(colorize(messages.getString("banned-password")));
            return;
        }

        String hashedPassword = passwordHasher.hash(password);
        String ip = getPlayerIp(player);
        database.registerPlayer(uuidStr, player.getName(), hashedPassword, ip);

        authenticatedPlayers.add(uuid);
        player.sendMessage(colorize(messages.getString("registered")));
        completeAuth(player, uuid);
        playSound(player, config.getString("register-sound"));
    }

    private void handleLogin(Player player, String command, UUID uuid, String uuidStr) {
        if (!database.isRegistered(uuidStr)) {
            player.sendMessage(colorize(messages.getString("not-registered")));
            return;
        }
        String[] parts = command.split(" ");
        if (parts.length < 2) {
            player.sendMessage(colorize(messages.getString("login-prompt")));
            return;
        }
        String password = parts[1];

        String storedHash = database.getPasswordHash(uuidStr);
        if (storedHash == null || !passwordHasher.verify(password, storedHash)) {
            player.sendMessage(colorize(messages.getString("login-failed")));
            return;
        }

        String ip = getPlayerIp(player);
        database.updateLastLogin(uuidStr, ip);

        authenticatedPlayers.add(uuid);
        player.sendMessage(colorize(messages.getString("login-success")));
        completeAuth(player, uuid);
        playSound(player, config.getString("login-sound"));
    }

    private void handlePremium(Player player, UUID uuid, String uuidStr) {
        if (!authenticatedPlayers.contains(uuid)) {
            player.sendMessage(colorize(messages.getString("premium-must-login")));
            return;
        }
        if (database.isPremium(uuidStr)) {
            player.sendMessage(colorize(messages.getString("premium-already")));
            return;
        }

        player.sendMessage(colorize(messages.getString("premium-checking")));

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            MojangAPI.MojangProfile profile = MojangAPI.lookupUsername(player.getName());

            Bukkit.getScheduler().runTask(this, () -> {
                if (!player.isOnline()) return;

                if (profile == null) {
                    player.sendMessage(colorize(messages.getString("premium-not-eligible")));
                    return;
                }

                String existingOwner = database.getUuidByPremiumUuid(profile.getPremiumUuid());
                if (existingOwner != null && !existingOwner.equals(uuidStr)) {
                    database.removePremium(existingOwner);
                    Bukkit.getLogger().info("[AuthPlus] Removed stale premium status from UUID "
                            + existingOwner + " (Mojang name changed to " + player.getName() + ")");
                }

                database.setPremium(uuidStr, profile.getPremiumUuid());
                player.sendMessage(colorize(messages.getString("premium-enabled")));
                Bukkit.getLogger().info("[AuthPlus] Player " + player.getName()
                        + " enabled premium auto-login (Mojang UUID: " + profile.getFormattedUuid() + ")");
            });
        });
    }

    private void handleCracked(Player player, UUID uuid, String uuidStr) {
        if (!authenticatedPlayers.contains(uuid)) {
            player.sendMessage(colorize(messages.getString("premium-must-login")));
            return;
        }
        if (!database.isPremium(uuidStr)) {
            player.sendMessage(colorize(messages.getString("premium-not-active")));
            return;
        }

        database.removePremium(uuidStr);
        player.sendMessage(colorize(messages.getString("premium-disabled")));
        Bukkit.getLogger().info("[AuthPlus] Player " + player.getName() + " disabled premium auto-login.");
    }

    /**
     * /changepassword <current password> <new password>
     */
    private void handleChangePassword(Player player, String command, UUID uuid, String uuidStr) {
        if (!authenticatedPlayers.contains(uuid)) {
            player.sendMessage(colorize(messages.getString("changepassword-must-login")));
            return;
        }

        String[] parts = command.split(" ");
        if (parts.length < 3) {
            player.sendMessage(colorize(messages.getString("changepassword-usage")));
            return;
        }

        String currentPassword = parts[1];
        String newPassword = parts[2];

        // Verify current password
        String storedHash = database.getPasswordHash(uuidStr);
        if (storedHash == null || !passwordHasher.verify(currentPassword, storedHash)) {
            player.sendMessage(colorize(messages.getString("changepassword-wrong")));
            return;
        }

        // Validate new password
        if (newPassword.length() < config.getInt("min-password-length")) {
            player.sendMessage(colorize(messages.getString("password-too-short")));
            return;
        }
        if (isBannedPassword(newPassword)) {
            player.sendMessage(colorize(messages.getString("banned-password")));
            return;
        }

        // Hash and update
        String newHash = passwordHasher.hash(newPassword);
        database.updatePassword(uuidStr, newHash);
        player.sendMessage(colorize(messages.getString("changepassword-success")));
        Bukkit.getLogger().info("[AuthPlus] Player " + player.getName() + " changed their password.");
    }

    /**
     * /unregister — player unregisters themselves
     * /unregister <player> — requires authplus.unregister permission or OP
     */
    private void handleUnregisterPlayer(Player player, String command, UUID uuid, String uuidStr) {
        String[] parts = command.split(" ");

        if (parts.length >= 2) {
            // Admin unregister: /unregister <player>
            if (!player.hasPermission("authplus.unregister") && !player.isOp()) {
                player.sendMessage(colorize(messages.getString("unregister-no-permission")));
                return;
            }

            String targetName = parts[1];
            String targetUuid = database.getUuidByUsername(targetName);

            if (targetUuid == null || !database.isRegistered(targetUuid)) {
                player.sendMessage(colorize(messages.getString("unregister-not-found")));
                return;
            }

            database.removePlayer(targetUuid);
            // If target is online, kick them out of authenticated state
            kickFromAuthState(targetUuid);
            player.sendMessage(colorize(messages.getString("unregister-admin-success").replace("%player%", targetName)));
            Bukkit.getLogger().info("[AuthPlus] Admin " + player.getName() + " unregistered player: " + targetName);

        } else {
            // Self unregister
            if (!authenticatedPlayers.contains(uuid)) {
                player.sendMessage(colorize(messages.getString("unregister-must-login")));
                return;
            }

            database.removePlayer(uuidStr);
            authenticatedPlayers.remove(uuid);
            player.sendMessage(colorize(messages.getString("unregister-success")));
            Bukkit.getLogger().info("[AuthPlus] Player " + player.getName() + " unregistered themselves.");

            // Re-trigger registration flow
            player.sendMessage(colorize(messages.getString("register-prompt")));
            freezePlayer(player);
            if (config.getBoolean("invisible-mode")) {
                player.setInvisible(true);
            }
            startAuthTimeout(player, uuid);
        }
    }

    // ========================
    // Console commands: /authplus:setlogin, /authplus:unregister
    // ========================

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase();

        if (name.equals("setlogin")) {
            return handleSetLoginCommand(sender);
        }

        if (name.equals("unregister")) {
            return handleUnregisterCommand(sender, args);
        }

        return false;
    }

    private boolean handleSetLoginCommand(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(colorize(messages.getString("setlogin-console")));
            return true;
        }

        Player player = (Player) sender;

        if (!player.hasPermission("authplus.setlogin") && !player.isOp()) {
            player.sendMessage(colorize(messages.getString("setlogin-no-permission")));
            return true;
        }

        Location loc = player.getLocation();
        config.set("login-spawn.enabled", true);
        config.set("login-spawn.world", loc.getWorld().getName());
        config.set("login-spawn.x", loc.getX());
        config.set("login-spawn.y", loc.getY());
        config.set("login-spawn.z", loc.getZ());
        config.set("login-spawn.yaw", (double) loc.getYaw());
        config.set("login-spawn.pitch", (double) loc.getPitch());
        saveConfig();

        player.sendMessage(colorize(messages.getString("setlogin-success")));
        return true;
    }

    /**
     * Console command: /authplus:unregister <player>
     */
    private boolean handleUnregisterCommand(CommandSender sender, String[] args) {
        // If from player and no args — the chat handler takes care of it
        if (sender instanceof Player && args.length == 0) {
            return true; // Handled by PlayerCommandPreprocessEvent
        }

        // Console or player with args
        if (args.length < 1) {
            sender.sendMessage(colorize(messages.getString("unregister-usage")));
            return true;
        }

        if (sender instanceof Player) {
            Player player = (Player) sender;
            if (!player.hasPermission("authplus.unregister") && !player.isOp()) {
                player.sendMessage(colorize(messages.getString("unregister-no-permission")));
                return true;
            }
        }

        String targetName = args[0];
        String targetUuid = database.getUuidByUsername(targetName);

        if (targetUuid == null || !database.isRegistered(targetUuid)) {
            sender.sendMessage(colorize(messages.getString("unregister-not-found")));
            return true;
        }

        database.removePlayer(targetUuid);
        kickFromAuthState(targetUuid);
        sender.sendMessage(colorize(messages.getString("unregister-admin-success").replace("%player%", targetName)));
        Bukkit.getLogger().info("[AuthPlus] " + sender.getName() + " unregistered player: " + targetName);
        return true;
    }

    /**
     * If a player is online and gets unregistered by admin/console,
     * remove them from authenticated state and restart auth flow.
     */
    private void kickFromAuthState(String uuidStr) {
        try {
            UUID targetUuid = UUID.fromString(uuidStr);
            Player target = Bukkit.getPlayer(targetUuid);
            if (target != null && target.isOnline()) {
                authenticatedPlayers.remove(targetUuid);
                target.sendMessage(colorize(messages.getString("register-prompt")));
                freezePlayer(target);
                if (config.getBoolean("invisible-mode")) {
                    target.setInvisible(true);
                }
                startAuthTimeout(target, targetUuid);
            }
        } catch (Exception e) {
            // UUID parse failed — player not online, that's fine
        }
    }

    // ========================
    // Auth completion
    // ========================

    private void completeAuth(Player player, UUID uuid) {
        unfreezePlayer(player);
        player.setInvisible(false);
        authTimeouts.remove(uuid);
        player.setLevel(0);
        player.setExp(0);

        if (preLoginLocations.containsKey(uuid)) {
            Location original = preLoginLocations.remove(uuid);
            if (original != null && original.getWorld() != null) {
                player.teleport(original);
            }
        }
    }

    // ========================
    // Movement blocking
    // ========================

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (frozenPlayers.containsKey(uuid) && frozenPlayers.get(uuid)) {
            event.setCancelled(true);
        }
    }

    // ========================
    // Login spawn location
    // ========================

    private Location getLoginSpawnLocation() {
        if (!config.getBoolean("login-spawn.enabled", false)) {
            return null;
        }

        String worldName = config.getString("login-spawn.world");
        if (worldName == null) return null;

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            Bukkit.getLogger().warning("[AuthPlus] Login spawn world '" + worldName + "' not found!");
            return null;
        }

        double x = config.getDouble("login-spawn.x");
        double y = config.getDouble("login-spawn.y");
        double z = config.getDouble("login-spawn.z");
        float yaw = (float) config.getDouble("login-spawn.yaw", 0);
        float pitch = (float) config.getDouble("login-spawn.pitch", 0);

        return new Location(world, x, y, z, yaw, pitch);
    }

    // ========================
    // Utility methods
    // ========================

    private void freezePlayer(Player player) {
        frozenPlayers.put(player.getUniqueId(), true);
    }

    private void unfreezePlayer(Player player) {
        frozenPlayers.put(player.getUniqueId(), false);
    }

    private boolean isBannedPassword(String password) {
        for (String bannedPassword : config.getStringList("banned-passwords")) {
            if (password.equalsIgnoreCase(bannedPassword)) {
                return true;
            }
        }
        return false;
    }

    private String getPlayerIp(Player player) {
        return player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : "unknown";
    }

    private void saveMessages() {
        try {
            messages.save(new File(getDataFolder(), "messages.yml"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String colorize(String message) {
        return message.replace("&", "§");
    }

    private void playSound(Player player, String sound) {
        try {
            Sound soundEffect = Sound.valueOf(sound.toUpperCase());
            player.playSound(player.getLocation(), soundEffect, 1, 1);
        } catch (Exception e) {
            // Ignore invalid sound names
        }
    }
}

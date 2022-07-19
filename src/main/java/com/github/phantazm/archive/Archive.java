package com.github.phantazm.archive;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Format;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class Archive extends JavaPlugin implements Listener {
    public static final String NAME = "Archive";

    private static final int WAIT_TIMEOUT_MS = 10000;
    private static final int MS_PER_SECOND = 1000;

    private static final Format DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd-hh-mm-ss");

    public static final String RECEIVE_BROADCASTS_PERMISSION = "archive.receive_broadcasts";
    public static final String BACKUP_PERMISSION = "archive.backup";

    //necessary variables
    private Thread backup;
    private volatile long lastInteraction;
    private Path backupDirectory;
    private String serverName;
    private Path serverDirectory;

    //configuration parameters
    private long backupIntervalSeconds;
    private long lastInteractionThresholdSeconds;
    private boolean broadcastMessages;
    private Component skipBackupMessage;
    private Component backupStartedMessage;
    private Component backupSucceededMessage;
    private Component backupFailedMessage;
    private List<Pattern> fileSkipRegexes;
    private List<Pattern> directorySkipRegexes;

    @Override
    public void onDisable() {
        joinBackupThread();
    }

    @Override
    public void onEnable() {
        Logger logger = getLogger();
        PluginManager manager = Bukkit.getPluginManager();

        serverDirectory = getServer().getPluginsFolder().toPath().getParent();
        serverName = serverDirectory.getFileName().toString();

        try {
            initConfig();
        }
        catch (Exception e) {
            logger.warning("Exception when initializing config: " + e);
            manager.disablePlugin(this);
            return;
        }

        if(!initBackupDir()) {
            logger.warning("Plugin failed to enable due to missing the backup directory.");
            manager.disablePlugin(this);
            return;
        }

        lastInteraction = System.currentTimeMillis();
        manager.registerEvents(this, this);

        if(joinBackupThread()) {
            initBackupThread();
        }
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private synchronized boolean initBackupDir() {
        Path dataPath = getDataFolder().toPath();
        Path newDirectory = dataPath.resolve(serverName);

        if(!newDirectory.equals(backupDirectory) || !Files.exists(backupDirectory)) {
            backupDirectory = newDirectory;

            try {
                Files.createDirectories(backupDirectory);
            }
            catch (IOException e) {
                getLogger().warning("Failed to create necessary backup directories");
                return false;
            }
        }

        return true;
    }

    private void initConfig() {
        FileConfiguration configuration = getConfig();
        MiniMessage miniMessage = MiniMessage.miniMessage();

        reloadConfig();
        saveDefaultConfig();

        Configuration defaults = new MemoryConfiguration();
        defaults.addDefault("backupIntervalSeconds", 1800L);
        defaults.addDefault("lastInteractionThresholdSeconds", 600L);
        defaults.addDefault("broadcastMessages", true);
        defaults.addDefault("skipBackupMessage", "Skipped backup due to no player activity.");
        defaults.addDefault("backupStartedMessage", "Started backup...");
        defaults.addDefault("backupSucceededMessage", "Backup complete.");
        defaults.addDefault("backupFailedMessage", "Backup failed. Check the server logs for more details.");
        defaults.addDefault("fileSkipRegexes", List.of("\\.jar$"));
        defaults.addDefault("directorySkipRegexes", List.of("logs", "cache", "libraries"));

        configuration.setDefaults(defaults);

        backupIntervalSeconds = configuration.getLong("backupIntervalSeconds");
        lastInteractionThresholdSeconds = configuration.getLong("lastInteractionThresholdSeconds");
        broadcastMessages = configuration.getBoolean("broadcastMessages");
        skipBackupMessage = miniMessage.deserialize(Objects.requireNonNull(configuration
                .getString("skipBackupMessage")));
        backupStartedMessage = miniMessage.deserialize(Objects.requireNonNull(configuration
                .getString("backupStartedMessage")));
        backupSucceededMessage = miniMessage.deserialize(Objects.requireNonNull(configuration
                .getString("backupSucceededMessage")));
        backupFailedMessage = miniMessage.deserialize(Objects.requireNonNull(configuration
                .getString("backupFailedMessage")));
        fileSkipRegexes = compilePatterns(configuration.getStringList("fileSkipRegexes"));
        directorySkipRegexes = compilePatterns(configuration.getStringList("directorySkipRegexes"));
    }

    private List<Pattern> compilePatterns(List<String> regexes) {
        List<Pattern> patterns = new ArrayList<>(regexes.size());
        for(String regex : regexes) {
            try {
                patterns.add(Pattern.compile(regex));
            }
            catch (PatternSyntaxException e) {
                getLogger().warning("Invalid regex in configuration: " + e);
            }
        }

        return patterns;
    }

    private void initBackupThread() {
        backup = new Thread(this::backup, "Archive Backup Thread");
        backup.start();
    }

    private boolean joinBackupThread() {
        Logger logger = getLogger();
        if(backup != null) {
            logger.info("Interrupting backup thread...");
            backup.interrupt();

            try {
                backup.join(WAIT_TIMEOUT_MS);
                backup = null;
                logger.info("Successfully interrupted backup thread.");
            }
            catch (InterruptedException e) {
                logger.warning("Interrupted when waiting for backup thread to die.");

                if(backup.isAlive()) {
                    logger.warning("Backup thread is still alive.");
                    Bukkit.getPluginManager().disablePlugin(this);
                    return false;
                }

                logger.info("Backup thread is not alive.");
                backup = null;
            }
        }

        return true;
    }

    @EventHandler
    private void onPlayerEvent(@NotNull PlayerEvent event) {
        lastInteraction = System.currentTimeMillis();
    }

    private void backup() {
        try {
            while(true) {
                //noinspection BusyWait
                Thread.sleep(backupIntervalSeconds * MS_PER_SECOND);

                //backups can be skipped if players have been inactive for long enough
                if((this.lastInteraction / MS_PER_SECOND) + backupIntervalSeconds < lastInteractionThresholdSeconds) {
                    doBackup();
                }
                else if(broadcastMessages) {
                    broadcastMessage(skipBackupMessage);
                }
            }
        }
        catch (InterruptedException ignored) {
            getLogger().info("Backup thread interrupted.");
        }
    }

    public void doBackup() {
        Logger logger = getLogger();
        if(!initBackupDir()) {
            logger.warning("Backup skipped due to failure to create the backup directory.");
            return;
        }

        broadcastMessage(backupStartedMessage);

        try {
            List<Path> backupTargets = new ArrayList<>();
            Files.walkFileTree(serverDirectory, new FileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    for(Pattern pattern : directorySkipRegexes) {
                        if(pattern.matcher(dir.getFileName().toString()).matches()) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    for(Pattern pattern : fileSkipRegexes) {
                        if(pattern.matcher(file.getFileName().toString()).matches()) {
                            return FileVisitResult.CONTINUE;
                        }
                    }

                    backupTargets.add(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    logger.warning("IOException when visiting " + file + ": " + exc);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });

            Path archive = backupDirectory.resolve(serverName + "_" + DATE_FORMAT.format(new Date()) + ".zip");
            try(ZipOutputStream outputStream = new ZipOutputStream(Files.newOutputStream(archive))) {
                for(Path path : backupTargets) {
                    Path relative = path.relativize(serverDirectory);

                    ZipEntry entry = new ZipEntry(relative.toString());
                    outputStream.putNextEntry(entry);
                    try(InputStream stream = Files.newInputStream(relative)) {
                        stream.transferTo(outputStream);
                    }
                    outputStream.closeEntry();
                }
            }

            broadcastMessage(backupSucceededMessage);
        } catch (IOException e) {
            logger.warning("Uncaught IOException when backing up files: " + e);
            broadcastMessage(backupFailedMessage);
        }
    }

    private void broadcastMessage(Component component) {
        if(Bukkit.isPrimaryThread()) {
            broadcastMessageUnsafe(component);
        }
        else {
            Bukkit.getScheduler().callSyncMethod(this, () -> {
                broadcastMessageUnsafe(component);
                return null;
            });
        }
    }

    private static void broadcastMessageUnsafe(Component component) {
        Bukkit.getServer().getOnlinePlayers().forEach(player -> {
            if(player.hasPermission(RECEIVE_BROADCASTS_PERMISSION)) {
                player.sendMessage(component);
            }
        });
    }
}

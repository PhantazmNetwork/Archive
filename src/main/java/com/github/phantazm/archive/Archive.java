package com.github.phantazm.archive;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.*;
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
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Main plugin class for Archive.
 */
public class Archive extends JavaPlugin implements Listener {
    /**
     * The name of the permission node which controls who can receive Archive broadcast messages.
     */
    public static final String RECEIVE_BROADCASTS_PERMISSION = "archive.receive_broadcasts";

    /**
     * The name of the permission node which controls who can trigger manual backups using the backup command.
     */
    public static final String BACKUP_PERMISSION = "archive.backup";

    private static final int WAIT_TIMEOUT_MS = 10000;
    private static final int MS_PER_SECOND = 1000;

    private static final Format DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd-hh-mm-ss");

    //necessary variables
    private Lock backupLock;
    private Thread backup;
    private volatile long lastInteraction;
    private Path backupDirectory;
    private String serverName;
    private Path serverDirectory;

    //configuration parameters
    private long backupIntervalSeconds;
    private long backupDeletionThresholdSeconds;
    private int compressionLevel;
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
        backup = null;
        backupLock = null;
        backupDirectory = null;
        serverName = null;
        serverDirectory = null;
    }

    @Override
    public void onEnable() {
        Logger logger = getLogger();
        PluginManager manager = Bukkit.getPluginManager();

        serverDirectory = getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
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

        Objects.requireNonNull(getCommand("backup")).setExecutor(new BackupCommand(this));

        backupLock = new ReentrantLock();
        if(joinBackupThread()) {
            backup = new Thread(this::automaticBackupProcess, "Archive Backup Thread");
            backup.start();
        }
    }

    @EventHandler
    private void onPlayerMove(@NotNull PlayerMoveEvent event) {
        onInteraction();
    }

    @EventHandler
    private void onPlaceBlock(@NotNull BlockPlaceEvent event) {
        onInteraction();
    }

    @EventHandler
    private void onBreakBlock(@NotNull BlockBreakEvent event) {
        onInteraction();
    }

    @EventHandler
    private void onCommandRun(@NotNull PlayerCommandPreprocessEvent event) {
        onInteraction();
    }

    @EventHandler
    private void onPlayerInteract(@NotNull PlayerInteractEvent event) {
        onInteraction();
    }

    private void onInteraction() {
        lastInteraction = System.currentTimeMillis();
    }

    private void initConfig() {
        FileConfiguration configuration = getConfig();
        MiniMessage miniMessage = MiniMessage.miniMessage();

        reloadConfig();
        saveDefaultConfig();

        Configuration defaults = new MemoryConfiguration();
        defaults.addDefault("backupIntervalSeconds", 1800L);
        defaults.addDefault("backupDeletionThresholdSeconds", 86400L);
        defaults.addDefault("compressionLevel", 9);
        defaults.addDefault("broadcastMessages", true);
        defaults.addDefault("skipBackupMessage", "Skipped backup due to no player activity.");
        defaults.addDefault("backupStartedMessage", "Started backup...");
        defaults.addDefault("backupSucceededMessage", "Backup complete.");
        defaults.addDefault("backupFailedMessage", "Backup failed. Check the server logs for more details.");
        defaults.addDefault("fileSkipRegexes", List.of("\\.jar$", "\\.zip$"));
        defaults.addDefault("directorySkipRegexes", List.of("logs", "cache", "version", "libraries"));

        configuration.setDefaults(defaults);

        backupIntervalSeconds = configuration.getLong("backupIntervalSeconds");
        backupDeletionThresholdSeconds = configuration.getLong("backupDeletionThresholdSeconds");
        compressionLevel = configuration.getInt("compressionLevel");
        if(compressionLevel < -1 || compressionLevel > 9) {
            getLogger().warning("Invalid compression level " + compressionLevel + ", defaulting to 9");
            compressionLevel = 9;
        }
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
        Logger logger = getLogger();
        for(String regex : regexes) {
            try {
                logger.finer("Compiling regex " + regex);
                patterns.add(Pattern.compile(regex));
            }
            catch (PatternSyntaxException e) {
                logger.warning("Invalid regex in configuration: " + e);
            }
        }

        return patterns;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private synchronized boolean initBackupDir() {
        Path dataPath = getDataFolder().toPath().toAbsolutePath().normalize();
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
                    return false;
                }

                logger.info("Backup thread is not alive.");
                backup = null;
            }
        }

        return true;
    }

    private void automaticBackupProcess() {
        try {
            while(true) {
                //noinspection BusyWait
                Thread.sleep(backupIntervalSeconds * MS_PER_SECOND);

                //only backup if there has been an interaction since the last backup time
                if(System.currentTimeMillis() - (backupIntervalSeconds * MS_PER_SECOND) < this.lastInteraction) {
                    doBackup();
                }
                else if(broadcastMessages) {
                    broadcastMessage(skipBackupMessage);
                }
            }
        }
        catch (InterruptedException ignored) {}
    }

    /**
     * Runs the backup, if one is not currently ongoing. If there is an ongoing backup, this method immediately returns.
     */
    public void doBackup() {
        //if we're already backing up, immediately return (don't wait on acquiring the lock)
        if(!backupLock.tryLock()) {
            return;
        }

        try {
            Logger logger = getLogger();
            if(!initBackupDir()) {
                logger.warning("Backup skipped due to failure to create the backup directory.");
                return;
            }

            broadcastMessage(backupStartedMessage);

            Path archive = null;
            try {
                List<Path> backupTargets = new ArrayList<>();
                Files.walkFileTree(serverDirectory, new FileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        String name = serverDirectory.relativize(dir).toString();
                        for(Pattern pattern : directorySkipRegexes) {
                            if(pattern.matcher(name).find()) {
                                logger.fine("Skipping subtree starting at " + dir);
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                        }

                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String name = serverDirectory.relativize(file).toString();
                        for(Pattern pattern : fileSkipRegexes) {
                            if(pattern.matcher(name).find()) {
                                logger.fine("Skipping file " + file);
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
                try(Stream<Path> paths = Files.walk(backupDirectory, 1)) {
                    paths.filter(path -> path.getFileName().toString().endsWith(".zip")).forEach(path -> {
                        try {
                            long age = System.currentTimeMillis() - Files.getLastModifiedTime(path).toMillis();

                            if(age / MS_PER_SECOND > backupDeletionThresholdSeconds) {
                                Files.delete(path);
                            }
                        } catch (IOException e) {
                            logger.warning("IOException when attempting to delete old backup file " + path + ": "
                                    + e);
                        }
                    });
                }

                archive = backupDirectory.resolve(serverName + "_" + DATE_FORMAT.format(new Date()) + ".zip");
                logger.info("Creating archive... " + archive);
                try(ZipOutputStream outputStream = new ZipOutputStream(Files.newOutputStream(archive))) {
                    outputStream.setLevel(compressionLevel);

                    for(Path path : backupTargets) {
                        logger.fine("Compressing " + path);
                        Path relative = serverDirectory.relativize(path);

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
                logger.warning("backupDirectory: " + backupDirectory);
                logger.warning("serverDirectory: " + serverDirectory);
                logger.warning("serverName: " + serverName);
                logger.warning("archive: " + archive);
                broadcastMessage(backupFailedMessage);
            }
        }
        finally {
            backupLock.unlock();
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

        Bukkit.getConsoleSender().sendMessage(component);
    }
}
package com.github.phantazmnetwork.archive;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * The command used to trigger manual backups.
 */
public class BackupCommand implements CommandExecutor {
    private final Archive plugin;
    private CompletableFuture<Void> backupTask;

    /**
     * Creates a new instance of this command bound to the specified {@link Archive} instance.
     * @param plugin the plugin instance this command is bound to
     */
    public BackupCommand(@NotNull Archive plugin) {
        this.plugin = Objects.requireNonNull(plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        if(sender.hasPermission(Archive.BACKUP_PERMISSION)) {
            /*
            if backupTask is not null, this means we're still waiting on a previous manual backup return from the
            command. do not call doBackup, even though it would immediately return, to prevent creating unnecessary
            tasks in the common task pool
             */
            if(plugin.isEnabled() && backupTask == null) {
                backupTask = CompletableFuture.runAsync(plugin::doBackup).whenComplete((a, b) -> backupTask = null);
                return true;
            }
        }

        return false;
    }
}
package com.github.phantazm.archive;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public class BackupCommand implements CommandExecutor {
    private final Archive plugin;

    public BackupCommand(@NotNull Archive plugin) {
        this.plugin = Objects.requireNonNull(plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        if(sender.hasPermission(Archive.BACKUP_PERMISSION)) {
            if(plugin.isEnabled()) {
                plugin.doBackup();
                return true;
            }
        }

        return false;
    }
}

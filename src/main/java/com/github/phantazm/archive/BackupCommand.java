package com.github.phantazm.archive;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

public class BackupCommand extends Command {
    protected BackupCommand() {
        super("backup");
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String[] args) {
        if(sender.hasPermission(Archive.BACKUP_PERMISSION)) {
            Archive archive = (Archive)Bukkit.getPluginManager().getPlugin(Archive.NAME);

            if(archive != null && archive.isEnabled()) {
                archive.doBackup();
                return true;
            }
        }

        return false;
    }
}

package io.github.emanuelececcon.sidestairs;

import java.util.List;
import java.util.Locale;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

/** /sidestairs status | repair | reload */
final class SideStairsCommand implements TabExecutor {

    private final SideStairsPlugin plugin;

    SideStairsCommand(SideStairsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> status(sender);
            case "repair" -> {
                int fixed = plugin.repairLoadedChunks();
                sender.sendMessage("SideStairs: checked the loaded chunks, fixed " + fixed + " displays or records.");
            }
            case "reload" -> {
                plugin.reloadSettings();
                int fixed = plugin.repairLoadedChunks();
                sender.sendMessage("SideStairs config reloaded" + (fixed > 0 ? ", fixed " + fixed + " displays or records." : "."));
                status(sender);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void status(CommandSender sender) {
        sender.sendMessage("SideStairs " + plugin.getPluginMeta().getVersion() + " - hold " + plugin.settings().triggerNames()
                + " in the off-hand to place stairs sideways; view range " + plugin.settings().viewRange());
        sender.sendMessage("Sideways stairs in loaded chunks: " + plugin.loadedCount() + ", being mined now: " + plugin.miner().active());
        sender.sendMessage("Since start: " + plugin.placed + " placed, " + plugin.broken + " broken, " + plugin.repaired + " repairs");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return List.of("status", "repair", "reload").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }
}

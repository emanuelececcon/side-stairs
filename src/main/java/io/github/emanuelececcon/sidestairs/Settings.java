package io.github.emanuelececcon.sidestairs;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

/** config.yml */
record Settings(Set<Material> triggers, float viewRange) {

    static Settings load(FileConfiguration config, Logger logger) {
        Set<Material> triggers = EnumSet.noneOf(Material.class);
        for (String name : config.getStringList("trigger-items")) {
            Material material = Material.matchMaterial(name);
            if (material == null || !material.isItem() || material.isAir()) {
                logger.warning("trigger-items: unknown item '" + name + "', ignored");
            } else {
                triggers.add(material);
            }
        }
        if (triggers.isEmpty()) {
            logger.warning("trigger-items is empty: using stick");
            triggers.add(Material.STICK);
        }
        float viewRange = (float) Math.max(0.1, Math.min(16.0, config.getDouble("view-range", 2.0)));
        return new Settings(triggers, viewRange);
    }

    boolean isTrigger(ItemStack item) {
        return item != null && triggers.contains(item.getType());
    }

    String triggerNames() {
        return triggers.stream().map(m -> m.getKey().getKey().toLowerCase(Locale.ROOT)).sorted().collect(Collectors.joining(", "));
    }
}

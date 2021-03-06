package io.github.battlepass.quests.quests.internal;

import io.github.battlepass.BattlePlugin;
import io.github.battlepass.quests.QuestExecutor;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.projectiles.ProjectileSource;

public class ProjectileQuest extends QuestExecutor {

    public ProjectileQuest(BattlePlugin plugin) {
        super(plugin);
    }

    @EventHandler(ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        ProjectileSource source = event.getEntity().getShooter();
        EntityType entityType = event.getEntityType();

        if (source instanceof Player) {
            this.execute("throw-projectile", (Player) source, result -> result.root(entityType.toString()));
        }
    }
}

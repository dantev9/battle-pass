package io.github.battlepass.quests.workers.pipeline.steps;

import com.google.common.collect.Sets;
import io.github.battlepass.BattlePlugin;
import io.github.battlepass.api.BattlePassApi;
import io.github.battlepass.api.events.user.UserQuestProgressionEvent;
import io.github.battlepass.cache.QuestCache;
import io.github.battlepass.controller.QuestController;
import io.github.battlepass.enums.Category;
import io.github.battlepass.logger.DebugLogger;
import io.github.battlepass.logger.containers.LogContainer;
import io.github.battlepass.objects.quests.Quest;
import io.github.battlepass.objects.quests.variable.QuestResult;
import io.github.battlepass.objects.quests.variable.Variable;
import io.github.battlepass.objects.user.User;
import me.hyfe.simplespigot.config.Config;
import me.hyfe.simplespigot.service.Locks;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.math.BigInteger;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

public class QuestValidationStep {
    private final CompletionStep completionStep;
    private final BattlePlugin plugin;
    private final BattlePassApi api;
    private final QuestController controller;
    private final QuestCache questCache;
    private final DebugLogger debugLogger;
    private final Set<String> whitelistedWorlds;
    private final Set<String> blacklistedWorlds;
    private final boolean lockPreviousWeeks;
    private final boolean requirePreviousCompletion;
    private final boolean disableDailiesOnSeasonEnd;
    private final boolean disableNormalsOnSeasonEnd;
    private final ReentrantLock questLock = Locks.newReentrantLock();

    public QuestValidationStep(BattlePlugin plugin) {
        Config settings = plugin.getConfig("settings");
        this.completionStep = new CompletionStep(plugin);
        this.plugin = plugin;
        this.api = plugin.getLocalApi();
        this.controller = plugin.getQuestController();
        this.questCache = plugin.getQuestCache();
        this.debugLogger = plugin.getDebugLogger();
        this.whitelistedWorlds = Sets.newHashSet(settings.stringList("whitelisted-worlds"));
        this.blacklistedWorlds = Sets.newHashSet(settings.stringList("blacklisted-worlds"));
        this.lockPreviousWeeks = settings.bool("current-season.unlocks.lock-previous-weeks");
        this.requirePreviousCompletion = settings.bool("current-season.unlocks.require-previous-completion");
        this.disableDailiesOnSeasonEnd = settings.bool("season-finished.stop-daily-quests");
        this.disableNormalsOnSeasonEnd = settings.bool("season-finished.stop-other-quests");
    }

    public void process(Player player, User user, String name, int progress, QuestResult questResult, Collection<Quest> quests, boolean overrideUpdate) {
        String playerWorld = player.getWorld().getName();
        boolean seasonEnded = this.api.hasSeasonEnded();
        if (seasonEnded && this.disableDailiesOnSeasonEnd && this.disableNormalsOnSeasonEnd) {
            return;
        }
        if ((!this.whitelistedWorlds.isEmpty() && !this.whitelistedWorlds.contains(playerWorld)) || this.blacklistedWorlds.contains(playerWorld)) {
            return;
        }
        for (Quest quest : quests) {
            this.applyAntiAbuseMeasures(player, user, quest, name, progress, questResult);
            if (!name.equalsIgnoreCase(quest.getType())) {
                continue;
            }
            if (seasonEnded && (quest.getCategoryId().contains("daily") && this.disableDailiesOnSeasonEnd) || (quest.getCategoryId().contains("week") && this.disableNormalsOnSeasonEnd)) {
                continue;
            }
            Set<String> questWhitelistedWorlds = quest.getWhitelistedWorlds();
            if ((!questWhitelistedWorlds.isEmpty() && !questWhitelistedWorlds.contains(playerWorld)) || quest.getBlacklistedWorlds().contains(playerWorld)) {
                continue;
            }
            this.questLock.lock();
            try {
                this.proceed(player, user, quest, progress, questResult, overrideUpdate);
            } finally {
                this.questLock.unlock();
            }
        }
    }

    private void proceed(Player player, User user, Quest quest, int progress, QuestResult questResult, boolean overrideUpdate) {
        this.proceed(player, user, quest, BigInteger.valueOf(progress), questResult, overrideUpdate);
    }

    private void proceed(Player player, User user, Quest quest, BigInteger progress, QuestResult questResult, boolean overrideUpdate) {
        BigInteger originalProgress = this.controller.getQuestProgress(user, quest);
        if (overrideUpdate && originalProgress.compareTo(progress) == 0) {
            return;
        }
        Variable subVariable = quest.getVariable();
        if (!this.controller.isQuestDone(user, quest) && questResult.isEligible(player, subVariable)) {
            String exclusiveTo = quest.getExclusiveTo();
            if (exclusiveTo != null && !exclusiveTo.equalsIgnoreCase(user.getPassId())) {
                return;
            }

            int week = Category.stripWeek(quest.getCategoryId());
            boolean isDaily = week == 0;
            if (!isDaily && !user.bypassesLockedWeeks() && (week > this.api.currentWeek() || (this.lockPreviousWeeks && week < this.api.currentWeek()))) {
                return;
            }
            if (this.requirePreviousCompletion && !isDaily && !user.bypassesLockedWeeks()) {
                String targetedCategoryId = "week-" + (week - 1);
                if (this.questCache.keySet().contains(targetedCategoryId)) {
                    boolean failed = false;
                    for (Quest requiredQuest : this.questCache.getQuests(targetedCategoryId).values()) {
                        if (!this.controller.isQuestDone(user, requiredQuest)) {
                            failed = true;
                            break;
                        }
                    }
                    if (failed) {
                        return;
                    }
                }
            }
            UserQuestProgressionEvent event = new UserQuestProgressionEvent(user, quest, progress);
            this.plugin.runSync(() -> {
                Bukkit.getPluginManager().callEvent(event);
            });
            event.ifNotCancelled(eventConsumer -> this.completionStep.process(user, quest, originalProgress, eventConsumer.getAddedProgress(), overrideUpdate));
        }
    }

    public void isQuestValid(Player player, User user, Quest quest, BigInteger progress, boolean overrideUpdate) {
        String playerWorld = player.getWorld().getName();
        boolean seasonEnded = this.api.hasSeasonEnded();
        if (seasonEnded && this.disableDailiesOnSeasonEnd && this.disableNormalsOnSeasonEnd) {
            return;
        }
        if ((!this.whitelistedWorlds.isEmpty() && !this.whitelistedWorlds.contains(playerWorld)) || this.blacklistedWorlds.contains(playerWorld)) {
            return;
        }
        if (seasonEnded) {
            if ((quest.getCategoryId().contains("daily") && this.disableDailiesOnSeasonEnd) || (quest.getCategoryId().contains("week") && this.disableNormalsOnSeasonEnd)) {
                return;
            }
        }
        Set<String> questWhitelistedWorlds = quest.getWhitelistedWorlds();
        if ((!questWhitelistedWorlds.isEmpty() && !questWhitelistedWorlds.contains(playerWorld)) || quest.getBlacklistedWorlds().contains(playerWorld)) {
            return;
        }
        BigInteger originalProgress = this.controller.getQuestProgress(user, quest);
        if (overrideUpdate && originalProgress.compareTo(progress) == 0) {
            return;
        }
        if (!this.controller.isQuestDone(user, quest)) {
            String exclusiveTo = quest.getExclusiveTo();
            if (exclusiveTo != null && !exclusiveTo.equalsIgnoreCase(user.getPassId())) {
                return;
            }

            int week = Category.stripWeek(quest.getCategoryId());
            boolean isDaily = week == 0;
            if (!isDaily && !user.bypassesLockedWeeks() && (week > this.api.currentWeek() || (this.lockPreviousWeeks && week < this.api.currentWeek()))) {
                return;
            }
            if (this.requirePreviousCompletion && !isDaily && !user.bypassesLockedWeeks()) {
                String targetedCategoryId = "week-" + (week - 1);
                if (this.questCache.keySet().contains(targetedCategoryId)) {
                    boolean failed = false;
                    for (Quest requiredQuest : this.questCache.getQuests(targetedCategoryId).values()) {
                        if (!this.controller.isQuestDone(user, requiredQuest)) {
                            failed = true;
                            break;
                        }
                    }
                    if (failed) {
                        return;
                    }
                }
            }
            UserQuestProgressionEvent event = new UserQuestProgressionEvent(user, quest, progress);
            this.plugin.runSync(() -> {
                Bukkit.getPluginManager().callEvent(event);
            });
        }
    }

    private void applyAntiAbuseMeasures(Player player, User user, Quest quest, String currentType, int progress, QuestResult questResult) {
        this.applyAntiAbuseMeasures(player, user, quest, currentType, BigInteger.valueOf(progress), questResult);
    }

    private void applyAntiAbuseMeasures(Player player, User user, Quest quest, String currentType, BigInteger progress, QuestResult questResult) {
        if (this.controller.isQuestDone(user, quest)
                || (!currentType.equals("block-break") && !currentType.equals("block-place"))
                || currentType.equalsIgnoreCase(quest.getType())
                || !quest.isAntiAbuse()
                || !questResult.isEligible(player, quest.getVariable())) {
            return;
        }
        this.debugLogger.log(LogContainer.of("Anti abuse measures applied for player %s on quest " + quest.getCategoryId() + ":" + quest.getId(), player));
        BigInteger currentProgress = this.controller.getQuestProgress(user, quest);
        if (currentProgress.compareTo(BigInteger.ZERO) > 0) {
            this.controller.setQuestProgress(user, quest, currentProgress.subtract(progress).max(BigInteger.ZERO));
        }
    }
}
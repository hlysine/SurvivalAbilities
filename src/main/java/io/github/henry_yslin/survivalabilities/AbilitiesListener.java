package io.github.henry_yslin.survivalabilities;

import io.github.henry_yslin.enderpearlabilities.EnderPearlAbilities;
import io.github.henry_yslin.enderpearlabilities.abilities.Ability;
import io.github.henry_yslin.enderpearlabilities.abilities.AbilityInfo;
import io.github.henry_yslin.enderpearlabilities.abilities.ActivationHand;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class AbilitiesListener implements Listener {

    private final Plugin plugin;
    private final ConfigManager config;

    public AbilitiesListener(Plugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean checkStructure(Block hopperBlock) {
        return hopperBlock.getType() == Material.HOPPER
                && hopperBlock.getRelative(0, 1, 0).getType() == Material.SOUL_SOIL
                && hopperBlock.getRelative(0, 2, 0).getType() == Material.SOUL_FIRE;
    }

    private boolean spendXp(Player player) {
        if (player.getLevel() < config.getXpCost()) {
            player.sendMessage(ChatColor.RED + "You need " + config.getXpCost() + " levels to get a new ability.");
            return false;
        }
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1, 1);
        player.setLevel(player.getLevel() - config.getXpCost());
        return true;
    }

    private List<String> generateAbilityChoices(Player player) {
        EnderPearlAbilities abilities = EnderPearlAbilities.getInstance();
        List<Ability<?>> ownedAbilities = abilities.getAbilities().stream()
                .filter(ability -> ability.getOwnerName().equals(player.getName()))
                .collect(Collectors.toList());
        List<String> exclusions = ownedAbilities.stream()
                .map(a -> a.getInfo().getCodeName())
                .distinct()
                .collect(Collectors.toList());

        List<ActivationHand> occupiedHands;
        if (ownedAbilities.size() == 0) {
            occupiedHands = List.of(ActivationHand.MainHand);
        } else {
            occupiedHands = ownedAbilities.stream()
                    .map(ability -> ability.getInfo().getActivation())
                    .distinct()
                    .collect(Collectors.toList());
        }

        List<String> availableChoices;
        if (occupiedHands.size() == 1) {
            availableChoices = abilities.getAbilityInfos().stream()
                    .filter(info -> info.getActivation() != occupiedHands.get(0))
                    .map(AbilityInfo::getCodeName)
                    .collect(Collectors.toList());
        } else {
            availableChoices = abilities.getAbilityInfos().stream()
                    .map(AbilityInfo::getCodeName)
                    .collect(Collectors.toList());
        }

        availableChoices.removeAll(exclusions);
        Collections.shuffle(availableChoices);
        return availableChoices.stream().limit(5).toList();
    }

    private void startAbilitySelection(Player player, Block hopper) {
        List<String> choices = config.getAbilityChoices(player.getName());
        if (choices.size() == 0) {
            choices = generateAbilityChoices(player);
            config.setAbilityChoices(player.getName(), choices);
        }

        new AbilitySelectionUI(plugin, choices, ability -> {
            if (hopper != null && !checkStructure(hopper)) {
                player.sendMessage(ChatColor.RED + "The structure has been destroyed.");
                return;
            }
            EnderPearlAbilities abilities = EnderPearlAbilities.getInstance();
            Optional<AbilityInfo> info = abilities.getAbilityInfos().stream()
                    .filter(inf -> inf.getCodeName().equals(ability))
                    .findFirst();
            if (info.isEmpty()) return;
            config.removeNewPlayer(player.getName());
            config.clearChoices(player.getName());
            AbilityInfo abilityInfo = info.get();
            List<String> removed = new ArrayList<>();
            abilities.getAbilities().stream()
                    .filter(a -> a.getOwnerName().equals(player.getName()) && a.getInfo().getActivation() == abilityInfo.getActivation())
                    .collect(Collectors.toList())
                    .forEach(a -> {
                        abilities.removeAbility(a);
                        removed.add(a.getInfo().getCodeName());
                    });
            abilities.addAbility(abilityInfo, player.getName());
            player.sendMessage("You now have the ability " + abilityInfo.getCodeName() + ".");
            if (removed.size() > 0) {
                player.sendMessage("You no longer have the ability " + String.join(", ", removed) + ".");
            }
            player.getWorld().spawnParticle(Particle.VILLAGER_HAPPY, player.getEyeLocation(), 20, 1, 1, 1, 0.1, null, true);
            player.getWorld().playSound(player.getLocation(), Sound.BLOCK_END_PORTAL_SPAWN, 1, 2);

            if (hopper != null) {
                hopper.getWorld().spawnParticle(Particle.SOUL, hopper.getLocation(), 20, 1, 1, 1, 0.01, null, true);
                if (hopper.getRelative(0, 2, 0).getType() == Material.SOUL_FIRE)
                    hopper.getRelative(0, 2, 0).setType(Material.AIR);
                if (hopper.getRelative(0, 1, 0).getType() == Material.SOUL_SOIL)
                    hopper.getRelative(0, 1, 0).setType(Material.NETHERRACK);
            }
        }, () -> {
        }).openInventory(player);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (config.isPlayerNew(player.getName())) {
            config.addNewPlayer(player.getName());
        }
        if (!config.promptOnJoin(player.getName())) return;
        startAbilitySelection(player, null);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getItem() != null && !event.getItem().getType().isAir()) return;
        if (event.getClickedBlock() == null) return;
        if (!checkStructure(event.getClickedBlock())) return;
        event.setCancelled(true);
        if (!config.promptOnJoin(player.getName())) {
            if (config.getAbilityChoices(player.getName()).size() == 0) {
                if (!spendXp(player)) return;
            }
        }
        startAbilitySelection(player, event.getClickedBlock());
    }
}

package com.wafflegod.wardenbeam;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class WardenBeamPlugin extends JavaPlugin implements TabExecutor, Listener {

    private static final long CHARGE_TICKS = 34L; // command-mode charge-up, same as a real warden

    private NamespacedKey rodKey;
    private NamespacedKey recipeKey;
    private final Map<UUID, Long> lastUse = new HashMap<>();

    private double damage;
    private double range;
    private long cooldownMs;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        damage = getConfig().getDouble("damage", 10.0);
        range = getConfig().getDouble("range", 20.0);
        cooldownMs = (long) (getConfig().getDouble("cooldown-seconds", 3.0) * 1000);

        rodKey = new NamespacedKey(this, "warden_beam_rod");
        recipeKey = new NamespacedKey(this, "warden_beam_rod_recipe");
        registerRecipe();

        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("wardenbeam");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        for (Player p : Bukkit.getOnlinePlayers()) p.discoverRecipe(recipeKey);
    }

    @Override
    public void onDisable() {
        Bukkit.removeRecipe(recipeKey);
    }

    // ------------------------------------------------------------------ item + recipe

    private ItemStack createRod() {
        ItemStack item = new ItemStack(Material.FISHING_ROD);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(Component.text("Warden Beam Rod", NamedTextColor.DARK_AQUA, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Right-click to fire a sonic boom.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Armor won't save you.", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        meta.setUnbreakable(true);
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(rodKey, PersistentDataType.BYTE, (byte) 1);

        item.setItemMeta(meta);
        return item;
    }

    private boolean isRod(ItemStack item) {
        return item != null
                && item.getType() == Material.FISHING_ROD
                && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(rodKey, PersistentDataType.BYTE);
    }

    private void registerRecipe() {
        // S C S      S = Sculk            C = Sculk Catalyst
        // T N H      T = Sculk Sensor     N = Nether Star     H = Sculk Shrieker
        // S R S      R = Fishing Rod
        ShapedRecipe recipe = new ShapedRecipe(recipeKey, createRod());
        recipe.shape("SCS", "TNH", "SRS");
        recipe.setIngredient('S', Material.SCULK);
        recipe.setIngredient('C', Material.SCULK_CATALYST);
        recipe.setIngredient('T', Material.SCULK_SENSOR);
        recipe.setIngredient('H', Material.SCULK_SHRIEKER);
        recipe.setIngredient('N', Material.NETHER_STAR);
        recipe.setIngredient('R', Material.FISHING_ROD);
        Bukkit.addRecipe(recipe);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        e.getPlayer().discoverRecipe(recipeKey);
    }

    // ------------------------------------------------------------------ rod usage

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        if (!isRod(e.getItem())) return;

        e.setCancelled(true); // don't cast the bobber
        Player p = e.getPlayer();
        if (!p.hasPermission("wardenbeam.item")) return;

        long now = System.currentTimeMillis();
        Long last = lastUse.get(p.getUniqueId());
        if (last != null && now - last < cooldownMs) return;
        lastUse.put(p.getUniqueId(), now);

        fireForward(p);
    }

    @EventHandler
    public void onFish(PlayerFishEvent e) {
        Player p = e.getPlayer();
        if (isRod(p.getInventory().getItemInMainHand()) || isRod(p.getInventory().getItemInOffHand())) {
            e.setCancelled(true);
        }
    }

    private void fireForward(Player p) {
        World world = p.getWorld();
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();

        RayTraceResult hit = world.rayTrace(eye, dir, range, FluidCollisionMode.NEVER, true, 0.6,
                en -> en instanceof LivingEntity
                        && !en.equals(p)
                        && !(en instanceof ArmorStand)
                        && !(en instanceof Player other && other.getGameMode() == GameMode.SPECTATOR));

        Location end;
        LivingEntity victim = null;
        if (hit != null) {
            end = hit.getHitPosition().toLocation(world);
            if (hit.getHitEntity() instanceof LivingEntity le) victim = le;
        } else {
            end = eye.clone().add(dir.clone().multiply(range));
        }

        Location start = eye.clone().add(dir.clone().multiply(1.0)).subtract(0, 0.3, 0);
        drawBeam(world, start, end);
        world.playSound(start, Sound.ENTITY_WARDEN_SONIC_BOOM, 3.0f, 1.0f);

        if (victim != null) {
            DamageSource source = DamageSource.builder(DamageType.SONIC_BOOM)
                    .withCausingEntity(p)
                    .withDirectEntity(p)
                    .build();
            victim.damage(damage, source);
            victim.setVelocity(dir.clone().multiply(2.0).setY(0.5));
        }
    }

    private void drawBeam(World world, Location start, Location end) {
        Vector line = end.toVector().subtract(start.toVector());
        double length = line.length();
        if (length < 0.01) return;
        line.normalize();
        for (double d = 0; d <= length; d += 1.0) {
            world.spawnParticle(Particle.SONIC_BOOM, start.clone().add(line.clone().multiply(d)), 1, 0, 0, 0, 0);
        }
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        // /wardenbeam give [player]
        if (args.length >= 1 && args[0].equalsIgnoreCase("give")) {
            if (!sender.hasPermission("wardenbeam.give")) {
                sender.sendMessage("§cYou don't have permission to do that.");
                return true;
            }
            Player recipient;
            if (args.length >= 2) {
                recipient = Bukkit.getPlayerExact(args[1]);
                if (recipient == null) {
                    sender.sendMessage("§cPlayer not found: " + args[1]);
                    return true;
                }
            } else if (sender instanceof Player self) {
                recipient = self;
            } else {
                sender.sendMessage("§cConsole must specify a player: /wardenbeam give <player>");
                return true;
            }
            recipient.getInventory().addItem(createRod()).values()
                    .forEach(left -> recipient.getWorld().dropItemNaturally(recipient.getLocation(), left));
            sender.sendMessage("§3Gave a Warden Beam Rod to " + recipient.getName() + ".");
            return true;
        }

        // /wardenbeam [player] [damage]  -> charged beam from in front of the target
        Player target;
        if (args.length >= 1) {
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage("§cPlayer not found: " + args[0]);
                return true;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            sender.sendMessage("§cConsole must specify a player: /wardenbeam <player> [damage]");
            return true;
        }

        double dmg = damage;
        if (args.length >= 2) {
            try {
                dmg = Math.max(0, Double.parseDouble(args[1]));
            } catch (NumberFormatException ex) {
                sender.sendMessage("§cDamage must be a number.");
                return true;
            }
        }

        fireAtPlayer(target, dmg);
        return true;
    }

    private void fireAtPlayer(Player target, double dmg) {
        World world = target.getWorld();

        Vector flat = target.getLocation().getDirection().setY(0);
        if (flat.lengthSquared() < 1.0E-4) flat = new Vector(1, 0, 0);
        flat.normalize().multiply(14.0);
        Location start = target.getLocation().add(flat).add(0, 2.5, 0);

        world.playSound(start, Sound.ENTITY_WARDEN_SONIC_CHARGE, 3.0f, 1.0f);

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!target.isOnline() || target.isDead() || !target.getWorld().equals(world)) return;

            Location end = target.getLocation().add(0, 1.0, 0);
            Vector dir = end.toVector().subtract(start.toVector());
            if (dir.length() < 0.001) return;
            dir.normalize();

            drawBeam(world, start, end);
            world.playSound(start, Sound.ENTITY_WARDEN_SONIC_BOOM, 3.0f, 1.0f);
            world.playSound(end, Sound.ENTITY_WARDEN_SONIC_BOOM, 3.0f, 1.0f);

            target.damage(dmg, DamageSource.builder(DamageType.SONIC_BOOM).build());
            target.setVelocity(dir.clone().multiply(2.0).setY(0.5));
        }, CHARGE_TICKS);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return java.util.stream.Stream.concat(
                            java.util.stream.Stream.of("give"),
                            Bukkit.getOnlinePlayers().stream().map(Player::getName))
                    .filter(s -> s.toLowerCase().startsWith(prefix))
                    .toList();
        }
        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("give")) {
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                        .toList();
            }
            return List.of("10", "20", "0");
        }
        return List.of();
    }
}

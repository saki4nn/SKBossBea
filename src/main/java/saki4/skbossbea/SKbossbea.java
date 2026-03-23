package saki4.skbossbea;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.world.block.BlockTypes;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Bee;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.FileInputStream;
import java.time.Duration;
import java.time.LocalTime;
import java.util.*;

public class SKbossbea extends JavaPlugin implements Listener, CommandExecutor {

    private final List<Bee> activeBees = new ArrayList<>();
    private Location eventLocation;
    private BukkitTask timeoutTask;
    private final String REGION_NAME = "bossbea";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveDefaultResources();
        Objects.requireNonNull(getCommand("bossbea")).setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);

        // Таймер для автоматического запуска
        new BukkitRunnable() {
            @Override
            public void run() {
                String now = String.format("%02d:%02d", LocalTime.now().getHour(), LocalTime.now().getMinute());
                if (getConfig().getStringList("event-times").contains(now)) startEvent();
            }
        }.runTaskTimer(this, 0L, 1200L);

        // Контроль перемещения пчел
        new BukkitRunnable() {
            @Override
            public void run() {
                if (eventLocation == null || activeBees.isEmpty()) return;
                activeBees.removeIf(bee -> {
                    if (!bee.isValid() || bee.isDead()) return true;
                    if (!bee.getWorld().equals(eventLocation.getWorld()) || bee.getLocation().distance(eventLocation) > 20) {
                        bee.teleport(eventLocation.clone().add(0, 2, 0));
                    }
                    return false;
                });
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    @Override
    public void onDisable() {
        stopEvent(false);
    }

    @EventHandler
    public void onBeeAttack(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Bee bee && activeBees.contains(bee)) {
            if (e.getEntity() instanceof Player p) {
                p.setVelocity(new Vector(0, 0.8, 0));

                // Установка урона из конфига
                e.setDamage(getConfig().getDouble("bee.damage", 8.0));

                // Наложение эффектов
                if (getConfig().contains("bee.effects")) {
                    for (String effectName : getConfig().getConfigurationSection("bee.effects").getKeys(false)) {
                        PotionEffectType type = PotionEffectType.getByName(effectName.toUpperCase());
                        if (type != null) {
                            int level = getConfig().getInt("bee.effects." + effectName + ".level", 1) - 1;
                            int duration = getConfig().getInt("bee.effects." + effectName + ".duration", 100);
                            p.addPotionEffect(new PotionEffect(type, duration, Math.max(0, level)));
                        }
                    }
                }
            }
        }
    }

    public void startEvent() {
        determineEventLocation();
        if (eventLocation == null) return;

        stopEvent(false);
        pasteSchematic(false);
        spawnBees();
        broadcast("messages.start");

        timeoutTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!activeBees.isEmpty()) {
                    broadcast("messages.timeout");
                    stopEvent(false);
                }
            }
        }.runTaskLater(this, 36000L); // 30 минут
    }

    private void determineEventLocation() {
        String worldName = getConfig().getString("spawn.world", "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) return;

        if (getConfig().getBoolean("spawn.static", true)) {
            eventLocation = new Location(world,
                    getConfig().getDouble("spawn.x"),
                    getConfig().getDouble("spawn.y"),
                    getConfig().getDouble("spawn.z"));
        } else {
            int radius = getConfig().getInt("spawn.random-radius", 1000);
            int x = (int) ((Math.random() - 0.5) * 2 * radius);
            int z = (int) ((Math.random() - 0.5) * 2 * radius);
            int y = world.getHighestBlockYAt(x, z);
            eventLocation = new Location(world, x, y + 1, z);
        }
    }

    public void stopEvent(boolean success) {
        if (timeoutTask != null) {
            timeoutTask.cancel();
            timeoutTask = null;
        }

        // Если ивент пропадает (таймаут или стоп), убиваем пчел
        List<Bee> beesToKill = new ArrayList<>(activeBees);
        activeBees.clear();

        for (Bee bee : beesToKill) {
            if (bee.isValid() && !bee.isDead()) {
                bee.setHealth(0.0); // Убиваем пчелу (анимация смерти)
            }
        }

        pasteSchematic(true);
        removeRegion();
        if (success) broadcast("messages.stop");
    }

    private void pasteSchematic(boolean clear) {
        File file = new File(getDataFolder(), "schematics/bossbea.schem");
        if (!file.exists() || eventLocation == null) return;
        ClipboardFormat format = ClipboardFormats.findByFile(file);
        if (format == null) return;

        try (ClipboardReader reader = format.getReader(new FileInputStream(file))) {
            Clipboard clipboard = reader.read();
            try (EditSession editSession = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(eventLocation.getWorld()))) {
                BlockVector3 center = BlockVector3.at(eventLocation.getBlockX(), eventLocation.getBlockY(), eventLocation.getBlockZ());
                BlockVector3 min = clipboard.getMinimumPoint().subtract(clipboard.getOrigin()).add(center);
                BlockVector3 max = clipboard.getMaximumPoint().subtract(clipboard.getOrigin()).add(center);

                if (clear) {
                    CuboidRegion region = new CuboidRegion(BukkitAdapter.adapt(eventLocation.getWorld()), min, max);
                    editSession.setBlocks(region, BlockTypes.AIR.getDefaultState());
                } else {
                    Operation operation = new ClipboardHolder(clipboard).createPaste(editSession).to(center).ignoreAirBlocks(false).build();
                    Operations.complete(operation);
                    createRegion(min, max);
                }
            }
        } catch (Exception ex) { ex.printStackTrace(); }
    }

    private void createRegion(BlockVector3 min, BlockVector3 max) {
        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        RegionManager regions = container.get(BukkitAdapter.adapt(eventLocation.getWorld()));
        if (regions == null) return;

        ProtectedCuboidRegion region = new ProtectedCuboidRegion(REGION_NAME, min, max);
        region.setPriority(100);
        region.setFlag(Flags.BUILD, StateFlag.State.DENY);
        region.setFlag(Flags.BLOCK_BREAK, StateFlag.State.DENY);
        region.setFlag(Flags.BLOCK_PLACE, StateFlag.State.DENY);
        regions.addRegion(region);
    }

    private void removeRegion() {
        if (eventLocation == null) return;
        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        RegionManager regions = container.get(BukkitAdapter.adapt(eventLocation.getWorld()));
        if (regions != null && regions.hasRegion(REGION_NAME)) {
            regions.removeRegion(REGION_NAME);
        }
    }

    private void spawnBees() {
        int amount = getConfig().getInt("bee.amount", 5);
        double health = getConfig().getDouble("bee.health", 150.0);
        String name = getConfig().getString("bee.name", "&6Пчела-Босс");

        for (int i = 0; i < amount; i++) {
            Bee bee = (Bee) eventLocation.getWorld().spawnEntity(eventLocation.clone().add(0, 2, 0), EntityType.BEE);
            AttributeInstance attr = bee.getAttribute(Attribute.GENERIC_MAX_HEALTH);
            if (attr != null) {
                attr.setBaseValue(health);
                bee.setHealth(health);
            }
            bee.setAnger(99999);
            bee.setCustomName(color(name));
            bee.setCustomNameVisible(true);
            activeBees.add(bee);
        }
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String lb, String[] args) {
        if (args.length == 0) return false;

        if (args[0].equalsIgnoreCase("delay")) {
            if (s.hasPermission("bossbea.delay")) s.sendMessage(getDelayMessage());
            else s.sendMessage(color("&cУ вас нет прав!"));
            return true;
        }

        if (!s.hasPermission("bossbea.admin")) {
            s.sendMessage(color("&cУ вас нет прав администратора!"));
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            s.sendMessage(color("&aКонфиг перезагружен!"));
            return true;
        }

        if (args[0].equalsIgnoreCase("start")) {
            startEvent();
            return true;
        }

        // Новая команда для принудительной остановки
        if (args[0].equalsIgnoreCase("stop")) {
            stopEvent(false);
            s.sendMessage(color(getConfig().getString("messages.prefix") + " &cИвент принудительно остановлен!"));
            return true;
        }

        if (args[0].equalsIgnoreCase("setspawn") && s instanceof Player p) {
            saveLocation(p.getLocation());
            p.sendMessage(color(getConfig().getString("messages.prefix") + " &aТочка установлена!"));
            return true;
        }

        return true;
    }

    private String getDelayMessage() {
        List<String> times = getConfig().getStringList("event-times");
        if (times.isEmpty()) return color("&cВремя не указано!");
        LocalTime now = LocalTime.now();
        List<LocalTime> parsed = new ArrayList<>();
        for (String t : times) parsed.add(LocalTime.parse(t));
        Collections.sort(parsed);
        LocalTime next = parsed.stream().filter(t -> t.isAfter(now)).findFirst().orElse(parsed.get(0));
        Duration d = Duration.between(now, next);
        if (d.isNegative()) d = d.plusDays(1);
        return color(getConfig().getString("messages.delay")
                .replace("%h%", String.valueOf(d.toHours()))
                .replace("%m%", String.valueOf(d.toMinutes() % 60)));
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        if (e.getEntity() instanceof Bee bee && activeBees.contains(bee)) {
            activeBees.remove(bee);
            e.getDrops().clear();
            Player killer = bee.getKiller();

            if (killer != null) {
                List<String> commands = getConfig().getStringList("rewards");
                for (String c : commands) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("%player%", killer.getName()));
                }
                killer.sendMessage(color(getConfig().getString("messages.reward")));
            }
            if (activeBees.isEmpty()) stopEvent(true);
        }
    }

    private void broadcast(String path) {
        String msg = getConfig().getString(path);
        if (msg == null) return;
        String loc = (eventLocation != null) ? eventLocation.getBlockX() + " " + eventLocation.getBlockY() + " " + eventLocation.getBlockZ() : "---";
        Bukkit.broadcastMessage(color(getConfig().getString("messages.prefix") + " " + msg.replace("%loc%", loc)));
    }

    private String color(String s) { return ChatColor.translateAlternateColorCodes('&', s); }

    private void saveLocation(Location loc) {
        getConfig().set("spawn.world", loc.getWorld().getName());
        getConfig().set("spawn.x", loc.getX());
        getConfig().set("spawn.y", loc.getY());
        getConfig().set("spawn.z", loc.getZ());
        getConfig().set("spawn.static", true);
        saveConfig();
    }

    private void saveDefaultResources() {
        File schemFolder = new File(getDataFolder(), "schematics");
        if (!schemFolder.exists()) schemFolder.mkdirs();
        File schemFile = new File(schemFolder, "bossbea.schem");
        if (!schemFile.exists()) saveResource("schematics/bossbea.schem", false);
    }
}
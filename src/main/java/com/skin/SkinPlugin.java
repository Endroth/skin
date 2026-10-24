package com.skin;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /skin <nome>  -> usa a skin do jogador indicado
 * /skin clear   -> volta para a skin original
 * Requer Paper (usa a API de PlayerProfile do Paper).
 */
public class SkinPlugin extends JavaPlugin implements Listener, CommandExecutor {

    private static final long COOLDOWN_MS = 5000;

    // Perfil original de cada jogador online (para o /skin clear)
    private final Map<UUID, PlayerProfile> originals = new ConcurrentHashMap<>();
    // Cache de texturas já buscadas (evita chamar a Mojang toda hora)
    private final Map<String, ProfileProperty> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldown = new ConcurrentHashMap<>();

    private File dataFile;
    private YamlConfiguration data;

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        dataFile = new File(getDataFolder(), "skins.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);

        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("skin").setExecutor(this);

        // Se o plugin for carregado com jogadores online, guarda o perfil deles
        for (Player p : Bukkit.getOnlinePlayers()) {
            originals.put(p.getUniqueId(), p.getPlayerProfile());
        }
    }

    // ---------- Eventos ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        originals.put(p.getUniqueId(), p.getPlayerProfile());

        String saved = data.getString(p.getUniqueId().toString());
        if (saved != null) {
            // reaplica a skin escolhida da última vez
            Bukkit.getScheduler().runTaskLater(this, () -> applySkin(p, saved, true), 10L);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        originals.remove(e.getPlayer().getUniqueId());
        cooldown.remove(e.getPlayer().getUniqueId());
    }

    // ---------- Comando ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Apenas jogadores podem usar este comando.");
            return true;
        }
        if (!p.hasPermission("skin.use")) {
            p.sendMessage(ChatColor.RED + "Você não tem permissão para isso.");
            return true;
        }
        if (args.length != 1) {
            p.sendMessage(ChatColor.YELLOW + "Uso: /skin <nome> ou /skin clear");
            return true;
        }

        if (args[0].equalsIgnoreCase("clear")) {
            clearSkin(p);
            return true;
        }

        String name = args[0];
        if (!name.matches("[A-Za-z0-9_]{1,16}")) {
            p.sendMessage(ChatColor.RED + "Nome de jogador inválido.");
            return true;
        }

        long now = System.currentTimeMillis();
        long last = cooldown.getOrDefault(p.getUniqueId(), 0L);
        if (now - last < COOLDOWN_MS) {
            p.sendMessage(ChatColor.RED + "Espere alguns segundos para usar o comando de novo.");
            return true;
        }
        cooldown.put(p.getUniqueId(), now);

        p.sendMessage(ChatColor.YELLOW + "Buscando a skin de " + name + "...");
        applySkin(p, name, false);
        return true;
    }

    // ---------- Skin ----------

    private void applySkin(Player p, String name, boolean silent) {
        String key = name.toLowerCase();

        ProfileProperty cached = cache.get(key);
        if (cached != null) {
            apply(p, cached, name, silent);
            return;
        }

        // Busca na Mojang fora da thread principal
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            ProfileProperty textures = null;
            try {
                PlayerProfile fetched = Bukkit.createProfile(name);
                if (fetched.complete(true) && fetched.hasTextures()) {
                    for (ProfileProperty pp : fetched.getProperties()) {
                        if (pp.getName().equals("textures")) textures = pp;
                    }
                }
            } catch (Exception ex) {
                getLogger().warning("Erro ao buscar skin de " + name + ": " + ex.getMessage());
            }

            final ProfileProperty result = textures;
            Bukkit.getScheduler().runTask(this, () -> {
                if (!p.isOnline()) return;
                if (result == null) {
                    if (!silent) {
                        p.sendMessage(ChatColor.RED + "Não encontrei a skin de " + name + ".");
                    }
                    return;
                }
                cache.put(key, result);
                apply(p, result, name, silent);
            });
        });
    }

    private void apply(Player p, ProfileProperty textures, String name, boolean silent) {
        PlayerProfile profile = Bukkit.createProfile(p.getUniqueId(), p.getName());
        profile.setProperty(textures);
        p.setPlayerProfile(profile);

        if (!silent) {
            data.set(p.getUniqueId().toString(), name);
            saveData();
            p.sendMessage(ChatColor.GREEN + "Agora você está usando a skin de " + name + ".");
        }
    }

    private void clearSkin(Player p) {
        PlayerProfile original = originals.get(p.getUniqueId());
        if (original != null) {
            p.setPlayerProfile(original);
        }
        data.set(p.getUniqueId().toString(), null);
        saveData();
        p.sendMessage(ChatColor.GREEN + "Sua skin original foi restaurada.");
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Erro ao salvar skins.yml: " + ex.getMessage());
        }
    }
}

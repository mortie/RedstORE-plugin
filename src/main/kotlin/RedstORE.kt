package redstore

import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.bukkit.event.EventHandler
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.Action
import org.bukkit.block.Block
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.ChatColor
import org.bukkit.Bukkit
import org.bukkit.Material
import co.aikar.commands.PaperCommandManager
import java.util.UUID
import commands.RedstoreCommand
import redstore.RedstOREDatabase
import redstore.ConnMode
import redstore.ColorSchemes
import redstore.Layouts
import redstore.checkPlayerPermission

fun canPlayerEnabledConnection(
    db: RedstOREDatabase,
    player: UUID,
    props: ConnectionProperties,
): Boolean {
    data class FileStatus(
        var readers: Int,
        var writers: Int,
    ) {}

    val files = HashMap<String, FileStatus>();
    files[props.file] = when (props.mode) {
        ConnMode.READ -> FileStatus(1, 0);
        ConnMode.WRITE -> FileStatus(0, 1);
    };

    var openConns = 1;
    var deny = false;

    db.getPlayerConnections(player) { meta, ps ->
        if (!meta.enabled || deny) {
            return@getPlayerConnections;
        }

        openConns += 1;
        if (openConns > 3) {
            deny = true;
            return@getPlayerConnections;
        }

        var status = files[ps.file];
        if (status == null) {
            status = when (ps.mode) {
                ConnMode.READ -> FileStatus(1, 0);
                ConnMode.WRITE -> FileStatus(0, 1);
            }
            files[ps.file] = status;
        } else if (ps.mode == ConnMode.READ) {
            status.readers += 1;
        } else if (ps.mode == ConnMode.WRITE) {
            status.writers += 1;
        }

        if (status.readers > 1 || status.writers > 1) {
            deny = true;
        }
    }

    return !deny;
}

class RedstORE: JavaPlugin(), Listener {
    val connections = HashMap<UUID, StorageConnection>();
    val connectionsByOrigin = HashMap<Block, StorageConnection>();
    var materials: Materials? = null;
    public val colorSchemes = ColorSchemes();
    public val layouts = Layouts();
    public var db: RedstOREDatabase? = null;
    public var basePath: String? = null;

    @EventHandler
    fun onPlayerInteraction(evt: PlayerInteractEvent) {
        if (evt.getHand() != EquipmentSlot.HAND) {
            return;
        }

        if (evt.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        if (evt.isBlockInHand()) {
            return;
        }

        val conn = connectionsByOrigin.get(evt.getClickedBlock());
        if (conn == null) {
            return;
        }

        val origin = conn.props.origin;
        val meta = db!!.getConnectionMetaWithOrigin(origin);
        if (meta == null) {
            logger.warning("Connection appears to exist but isn't in database");
            return;
        }

        val owner = Bukkit.getPlayer(meta.playerUUID);
        val newEnabled = !conn.isEnabled();

        if (
            newEnabled &&
            !canPlayerEnabledConnection(db!!, meta.playerUUID, conn.props)
        ) {
            evt.player.sendMessage(
                "${ChatColor.RED}Too many connections enabled! Disable some.");
            return;
        }

        if (!evt.player.hasPermission("redstore.toggle.other")) {
            evt.player.sendMessage(
                "${ChatColor.RED}You don't have permission to toggle " +
                "other people's connections.");
            return;
        }

        conn.setEnabled(newEnabled);
        db!!.setConnectionEnabled(meta.uuid, newEnabled);
        val info = "${conn.props.mode} ${conn.props.file}"

        if (newEnabled) {
            val task = Bukkit.getScheduler().runTaskTimer(this, conn, 0L, 1L);
            conn.task = task;
            evt.player.sendMessage(
                "${ChatColor.GREEN}Enabled RedstORE connection: ${info} ");
            if (owner != null && owner != evt.player) {
                owner.sendMessage(
                    "${ChatColor.GREEN}Your RedstORE connection was enabled at " +
                    "(${origin.getX()}, ${origin.getY()}, ${origin.getZ()}): " +
                    "${info}");
            }
        } else {
            conn.task?.cancel();
            conn.task = null;
            evt.player.sendMessage(
                "${ChatColor.YELLOW}Disabled RedstORE connection: ${info}");
            if (owner != null && owner != evt.player) {
                owner.sendMessage(
                    "${ChatColor.YELLOW}Your RedstORE connection was disabled at " +
                    "(${origin.getX()}, ${origin.getY()}, ${origin.getZ()}): " +
                    "${info}");
            }
        }
    }

    @EventHandler
    fun onBlockBreak(evt: BlockBreakEvent) {
        if (!connectionsByOrigin.contains(evt.block)) {
            return;
        }

        if (!evt.player.hasPermission("redstore.admin")) {
            val meta = db!!.getConnectionMetaWithOrigin(evt.block);
            if (meta == null) {
                return;
            }

            if (meta.playerUUID != evt.player.getUniqueId()) {
                evt.player.sendMessage(
                    "${ChatColor.RED}You don't have permission to break " +
                    "other people's connections.");
                evt.setCancelled(true);
                return;
            }
        }

        removeStoreConnection(evt.block);
    }

    override fun onEnable() {
        saveDefaultConfig();

        basePath = getConfig().get("base-path").toString();
        if (basePath == null) {
            logger.severe("config.yml missing 'base-path'!");
            setEnabled(false);
            return;
        }

        basePath = basePath!!.replace("%redstore%", dataFolder.toString());
        logger.info("Using base path pattern: '${basePath}'");

        dataFolder.mkdirs();
        val dbFile = dataFolder.resolve("redstore.db").toString();
        logger.info("Opening DB file '${dbFile}'...");
        db = RedstOREDatabase(dbFile, logger);

        materials = Materials(
            readDisabled = Material.matchMaterial("minecraft:netherite_block")!!,
            readEnabled = Material.matchMaterial("minecraft:diamond_block")!!,
            readPending = Material.matchMaterial("minecraft:sea_lantern")!!,
            writeDisabled = Material.matchMaterial("minecraft:coal_block")!!,
            writeEnabled = Material.matchMaterial("minecraft:gold_block")!!,
            writePending = Material.matchMaterial("minecraft:sea_lantern")!!,
            powered = Material.matchMaterial("minecraft:redstone_block")!!,
        )

        PaperCommandManager(this).apply {
            registerCommand(RedstoreCommand(this@RedstORE));
        }

        // Add all existing connections
        db!!.getConnections { meta, props ->
            val conn: StorageConnection;
            try {
                conn = StorageConnection(
                    materials!!, logger, this, meta.enabled, meta.playerUUID, props);
            } catch (ex: Exception) {
                logger.info("Failed to load connection ${meta.uuid}: ${ex}");
                return@getConnections;
            }

            if (meta.enabled) {
                val task = Bukkit.getScheduler().runTaskTimer(this, conn, 0L, 1L);
                conn.task = task;
            }

            connections.set(meta.uuid, conn);
            connectionsByOrigin.set(props.origin, conn);
            val origin = props.origin;
            logger.info(
                "Loaded connection ${meta.uuid} at " +
                "(${origin.getX()}, ${origin.getY()}, ${origin.getZ()})" +
                " @ ${origin.getWorld().getName()}");
        }

        getServer().getPluginManager().registerEvents(this, this);

        logger.info("RedstORE enabled!");
    }

    override fun onDisable() {
        for ((_, connection) in connections) {
            connection.close();
        }

        connections.clear();
        connectionsByOrigin.clear();
        db!!.close();
        db = null;
        logger.info("RedstORE disabled!");
    }

    fun addStoreConnection(
        player: Player,
        props: ConnectionProperties,
    ): Boolean {
        if (!checkPlayerPermission(player, props)) {
            player.sendMessage(
                "${ChatColor.RED}You don't have permission to do that here.");
            return false;
        }

        val playerUUID = player.getUniqueId();
        val enabled = canPlayerEnabledConnection(
            db!!, playerUUID, props);

        addStoreConnectionUnchecked(playerUUID, props, enabled);
        val origin = props.origin;
        player.sendMessage(
            "${ChatColor.GREEN}Added RedstORE connection at " +
            "(${origin.getX()}, ${origin.getY()}, ${origin.getZ()})");

        return true;
    }

    private fun addStoreConnectionUnchecked(
        playerUUID: UUID,
        props: ConnectionProperties,
        enabled: Boolean,
    ) {
        removeStoreConnection(props.origin);

        val origin = props.origin;
        logger.info("Adding connection at " +
            "(${origin.getX()}, ${origin.getY()}, ${origin.getZ()})");

        // Do this first, so that if it throws an exception
        // (permission issue for example)
        // we don't add anything to the database
        val conn = StorageConnection(
            materials!!, logger, this, enabled, playerUUID, props);

        val uuid = db!!.addConnection(playerUUID, enabled, props);

        if (enabled) {
            val task = Bukkit.getScheduler().runTaskTimer(this, conn, 0L, 1L);
            conn.task = task;
        }

        connections.set(uuid, conn);
        connectionsByOrigin.set(props.origin, conn);
    }

    fun removeStoreConnection(block: Block): Boolean {
        val meta = db!!.getConnectionMetaWithOrigin(block);
        if (meta == null) {
            return false;
        }

        logger.info("Removing connection at " +
            "(${block.getX()}, ${block.getY()}, ${block.getZ()})");
        db!!.removeConnection(meta.uuid);

        val player = Bukkit.getPlayer(meta.playerUUID);
        if (player != null) {
            player.sendMessage(
                "${ChatColor.YELLOW}Removed RedstORE connection at " +
                "(${block.getX()}, ${block.getY()}, ${block.getZ()})");
        }

        val conn = connections.get(meta.uuid);
        connections.remove(meta.uuid);
        connectionsByOrigin.remove(block);

        if (conn == null) {
            logger.warning("Connection exists in database but not in world!");
            return true;
        }

        conn.destroy();
        return true;
    }

    fun reopenStoreConnection(block: Block, file: String): Boolean {
        val oldConn = connectionsByOrigin.get(block);
        if (oldConn == null) {
            return false;
        }

        val meta = db!!.getConnectionMetaWithOrigin(block);
        if (meta == null) {
            return false;
        }

        val pair = db!!.getConnection(meta.uuid);
        if (pair == null) {
            return false;
        }

        val (_, oldProps) = pair;
        val newProps = oldProps.replaceFile(file);

        // Doing this before we destroy the old one,
        // because it might throw
        val newConn = StorageConnection(
            materials!!, logger, this, meta.enabled, meta.playerUUID, newProps);

        oldConn.close();
        connections.set(meta.uuid, newConn);
        connectionsByOrigin.set(block, newConn);

        val task = Bukkit.getScheduler().runTaskTimer(this, newConn, 0L, 1L);
        newConn.task = task;

        val player = Bukkit.getPlayer(meta.playerUUID);
        if (player != null) {
            val info = "${newProps.mode} ${newProps.file}"
            player.sendMessage(
                "${ChatColor.YELLOW}Reopened RedstORE connection: ${info}");
        }

        return true;
    }

    fun disableStoreConnection(uuid: UUID) {
        val conn = connections.get(uuid);
        if (conn == null) {
            return;
        }

        conn.setEnabled(false);
        db!!.setConnectionEnabled(uuid, false);
        conn.task?.cancel();
        conn.task = null;
    }
}

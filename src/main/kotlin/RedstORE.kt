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

    db.getPlayerConnections(player) { meta, props ->
        if (!meta.enabled || deny) {
            return@getPlayerConnections;
        }

        openConns += 1;
        if (openConns > 3) {
            deny = true;
            return@getPlayerConnections;
        }

        var status = files[props.file];
        if (status == null) {
            status = when (props.mode) {
                ConnMode.READ -> FileStatus(1, 0);
                ConnMode.WRITE -> FileStatus(0, 1);
            }
            files[props.file] = status;
        } else if (props.mode == ConnMode.READ) {
            status.readers += 1;
        } else if (props.mode == ConnMode.WRITE) {
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

        if (newEnabled && !canPlayerEnabledConnection(db!!, meta.playerUUID, conn.props)) {
            evt.player.sendMessage(
                "${ChatColor.RED}Too many connections enabled! Disable some.");
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

        logger.info("Using base path pattern: '${basePath}'");

        dataFolder.mkdirs();
        val dbFile = dataFolder.resolve("redstore.db").toString();
        logger.info("Opening DB file '${dbFile}'...");
        db = RedstOREDatabase(dbFile, logger);

        materials = Materials(
            originEnabled = Material.matchMaterial("minecraft:sea_lantern")!!,
            originDisabled = Material.matchMaterial("minecraft:lapis_block")!!,
            powered = Material.matchMaterial("minecraft:redstone_block")!!,
            writeBit = Material.matchMaterial("minecraft:red_wool")!!,
            readBit = Material.matchMaterial("minecraft:lime_wool")!!,
            readPending = Material.matchMaterial("minecraft:green_wool")!!,
            addressBits = Material.matchMaterial("minecraft:blue_wool")!!,
            dataBits = Material.matchMaterial("minecraft:brown_wool")!!,
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
            player.sendMessage("${ChatColor.RED}You don't have permission to do that here.");
            return false;
        }

        val playerUUID = player.getUniqueId();
        val enabled = canPlayerEnabledConnection(
            db!!, playerUUID, props);

        addStoreConnectionUnchecked(playerUUID, props, enabled);
        val origin = props.origin;
        player.sendMessage("${ChatColor.GREEN}Added RedstORE connection at " +
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
        logger.info("${ChatColor.GREEN}Adding connection at " +
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

        logger.info("${ChatColor.YELLOW}Removing connection at " +
            "(${block.getX()}, ${block.getY()}, ${block.getZ()})");
        db!!.removeConnection(meta.uuid);

        val player = Bukkit.getPlayer(meta.playerUUID);
        if (player != null) {
            player.sendMessage("Removed RedstORE connection at " +
                "(${block.getX()}, ${block.getY()}, ${block.getZ()})");
        }

        val conn = connections.get(meta.uuid);
        if (conn == null) {
            logger.warning("Connection exists in database but not in world!");
            return true;
        }

        conn.close();
        connections.remove(meta.uuid);
        return true;
    }
}

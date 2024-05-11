package redstore

import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.bukkit.event.EventHandler
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.block.Action
import org.bukkit.block.Block
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.Bukkit
import org.bukkit.Material
import co.aikar.commands.PaperCommandManager
import java.util.UUID
import java.nio.file.Path
import commands.RedstoreCommand
import redstore.RedstOREDatabase
import redstore.checkPlayerPermission

class RedstORE: JavaPlugin(), Listener {
    val connections = HashMap<UUID, StorageConnection>();
    val connectionsByOrigin = HashMap<Block, StorageConnection>();
    var materials: Materials? = null;
    public var db: RedstOREDatabase? = null;
    public var basePath: Path? = null;

    @EventHandler
    fun onPlayerInteraction(evt: PlayerInteractEvent) {
        if (evt.getHand() != EquipmentSlot.HAND) {
            return;
        }

        if (evt.getAction() != Action.RIGHT_CLICK_BLOCK) {
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

        val player = meta?.let { Bukkit.getPlayer(it.playerUUID) };
        val newEnabled = !conn.isEnabled();

        conn.setEnabled(newEnabled);
        db!!.setConnectionEnabled(meta.uuid, newEnabled);

        if (newEnabled) {
            val task = Bukkit.getScheduler().runTaskTimer(this, conn, 0L, 1L);
            conn.task = task;
            if (player != null) {
                player.sendMessage("Enabled RedstORE connection at " +
                    "(${origin.getX()}, ${origin.getY()}, ${origin.getZ()})");
            }
        } else {
            conn.task?.cancel();
            conn.task = null;
            if (player != null) {
                player.sendMessage("Disabled RedstORE connection at " +
                    "(${origin.getX()}, ${origin.getY()}, ${origin.getZ()})");
            }
        }
    }

    override fun onEnable() {
        saveDefaultConfig();

        basePath = getConfig().get("base-path")?.let {
            Path.of(it.toString()).normalize();
        }
        if (basePath == null) {
            logger.severe("config.yml missing 'base-path'!");
            setEnabled(false);
            return;
        }

        logger.info("Using base path: '${basePath}'");

        dataFolder.mkdirs();
        val dbFile = dataFolder.resolve("redstore.db").toString();
        logger.info("Opening DB file '${dbFile}'...");
        db = RedstOREDatabase(dbFile, logger);

        materials = Materials(
            originEnabled = Material.matchMaterial("minecraft:sea_lantern")!!,
            originDisabled = Material.matchMaterial("minecraft:target")!!,
            onBlock = Material.matchMaterial("minecraft:redstone_block")!!,
            writeBit = Material.matchMaterial("minecraft:red_wool")!!,
            readBit = Material.matchMaterial("minecraft:lime_wool")!!,
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
                    materials!!, logger, this, meta.enabled, props);
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
            player.sendMessage("You don't have permission to do that here.");
            return false;
        }

        addStoreConnectionUnchecked(player.getUniqueId(), props);
        val origin = props.origin;
        player.sendMessage("Added RedstORE connection at " +
            "(${origin.getX()}, ${origin.getY()}, ${origin.getZ()})");

        return true;
    }

    private fun addStoreConnectionUnchecked(
        playerUUID: UUID,
        props: ConnectionProperties,
    ) {
        removeStoreConnection(props.origin);

        val origin = props.origin;
        logger.info("Adding connection at " +
            "(${origin.getX()}, ${origin.getY()}, ${origin.getZ()})");

        // This is a constant for now, but in the future,
        // we may want to be able to add a non-enabled connection.
        val enabled = true;

        // Do this first, so that if it throws an exception
        // (permission issue for example)
        // we don't add anything to the database
        val conn = StorageConnection(
            materials!!, logger, this, enabled, props);

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

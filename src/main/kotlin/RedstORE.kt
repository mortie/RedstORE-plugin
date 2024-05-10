package redstore

import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.entity.Player
import org.bukkit.block.Block
import org.bukkit.Bukkit
import org.bukkit.Material
import co.aikar.commands.PaperCommandManager
import java.util.UUID
import commands.RedstoreCommand
import redstore.RedstOREDatabase
import redstore.checkPlayerPermission

class RedstORE: JavaPlugin() {
    val connections = HashMap<UUID, StorageConnection>();
    var materials: Materials? = null;
    public var db: RedstOREDatabase? = null;

    override fun onEnable() {
        this.dataFolder.mkdirs();
        val dbFile = this.dataFolder.resolve("redstore.db").toString();
        logger.info("Opening DB file '${dbFile}'...");
        db = RedstOREDatabase(dbFile, logger);

        materials = Materials(
            origin = Material.matchMaterial("minecraft:sea_lantern")!!,
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
            val conn = StorageConnection(
                materials!!, logger, this, props);
            val task = Bukkit.getScheduler().runTaskTimer(this, conn, 0L, 1L);
            conn.task = task;
            connections.set(meta.uuid, conn);
            val origin = props.origin;
            logger.info(
                "Loaded connection ${meta.uuid} at " +
                "(${origin.getX()}, ${origin.getY()}, ${origin.getZ()})@" +
                "${origin.getWorld().getName()}");
        }

        logger.info("RedstORE enabled!");
    }

    override fun onDisable() {
        for ((_, connection) in connections) {
            connection.close();
        }

        connections.clear();
        db!!.close();
        db = null;
        logger.info("RedstORE disabled!");
    }

    public fun addStoreConnection(
        player: Player,
        props: ConnectionProperties,
    ): Boolean {
        if (!checkPlayerPermission(player, props)) {
            player.sendMessage("You don't have permission to do that here.");
            return false;
        }

        addStoreConnectionUnchecked(player.getUniqueId(), props);
        val origin = props.origin;
        player.sendMessage("Added connection at " +
            "(${origin.getX()}, ${origin.getY()}, ${origin.getZ()})");

        return true;
    }

    fun addStoreConnectionUnchecked(
        playerUUID: UUID,
        props: ConnectionProperties,
    ) {
        removeStoreConnection(props.origin);

        val origin = props.origin;
        logger.info("Adding connection at " +
            "(${origin.getX()}, ${origin.getY()}, ${origin.getZ()})");

        // Do this first, so that if it throws an exception
        // (permission issue for example)
        // we don't add anything to the database
        val conn = StorageConnection(
            materials!!, logger, this, props);

        val uuid = db!!.addConnection(playerUUID, props);

        val task = Bukkit.getScheduler().runTaskTimer(this, conn, 0L, 1L);
        conn.task = task;
        connections.set(uuid, conn);
    }

    public fun removeStoreConnection(block: Block): Boolean {
        val meta = db!!.getConnectionMetaWithOrigin(block);
        if (meta == null) {
            return false;
        }

        val conn = connections.get(meta.uuid);
        if (conn == null) {
            return false;
        }

        logger.info("Removing connection at " +
            "(${block.getX()}, ${block.getY()}, ${block.getZ()})");
        conn.close();
        connections.remove(meta.uuid);
        db!!.removeConnection(meta.uuid);

        val player = Bukkit.getPlayer(meta.playerUUID);
        if (player != null) {
            player.sendMessage("Removed connection at " +
                "(${block.getX()}, ${block.getY()}, ${block.getZ()})");
        }
        return true;
    }
}

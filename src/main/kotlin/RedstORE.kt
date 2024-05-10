package redstore

import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.block.Block
import org.bukkit.Bukkit
import org.bukkit.Material
import co.aikar.commands.PaperCommandManager
import java.util.logging.Level
import java.util.UUID
import commands.RedstoreCommand
import redstore.RedstOREDatabase;

class RedstORE: JavaPlugin() {
    var connections = HashMap<UUID, StorageConnection>();
    var materials: Materials? = null;
    var db: RedstOREDatabase? = null;

    override fun onEnable() {
        this.dataFolder.mkdirs();
        val dbFile = this.dataFolder.resolve("redstore.db").toString();
        logger.log(Level.INFO, "Opening DB file '${dbFile}'...");
        db = RedstOREDatabase(dbFile);

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

        logger.log(Level.INFO, "RedstORE enabled!");
    }

    override fun onDisable() {
        for ((_, connection) in connections) {
            connection.close();
        }

        connections.clear();
        db!!.close();
        db = null;
    }

    public fun addStoreConnection(playerUUID: UUID, props: ConnectionProperties) {
        // Remove existing connection if it exists
        removeStoreConnection(props.origin);

        val origin = props.origin;
        logger.log(Level.INFO, "Adding connection at " +
            "(${origin.getX()}, ${origin.getY()}, ${origin.getZ()})");

        val uuid = db!!.addConnection(playerUUID, props);

        var conn = StorageConnection(
            materials!!, logger, this, props);
        var task = Bukkit.getScheduler().runTaskTimer(this, conn, 0L, 1L);
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

        logger.log(Level.INFO, "Removing connection at " +
            "(${block.getX()}, ${block.getY()}, ${block.getZ()})");
        conn.close();
        connections.remove(meta.uuid);

        val player = Bukkit.getPlayer(meta.playerUUID);
        if (player != null) {
            player.sendMessage("Removed connection at " +
                "(${block.getX()}, ${block.getY()}, ${block.getZ()})");
        }
        return true;
    }
}

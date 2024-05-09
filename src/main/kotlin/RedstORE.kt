import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.block.Block
import org.bukkit.Bukkit
import org.bukkit.Material
import co.aikar.commands.PaperCommandManager
import java.util.logging.Level
import java.util.UUID
import commands.RedstoreCommand

class RedstOREPlayer {
    var connections = HashMap<Block, StorageConnection>();
}

class RedstORE: JavaPlugin() {
    var connections = HashMap<Block, StorageConnection>();
    var players = HashMap<UUID, RedstOREPlayer>();
    var materials: Materials? = null;

    override fun onEnable() {
        materials = Materials(
            origin = Material.matchMaterial("minecraft:sea_lantern")!!,
            onBlock = Material.matchMaterial("minecraft:redstone_block")!!,
            writeBit = Material.matchMaterial("minecraft:red_wool")!!,
            readBit = Material.matchMaterial("minecraft:lime_wool")!!,
            addressBits = Material.matchMaterial("minecraft:blue_wool")!!,
            dataBits = Material.matchMaterial("minecraft:brown_wool")!!,
        )

        logger.log(Level.INFO, "RedstORE enabled!");

        PaperCommandManager(this).apply {
            registerCommand(RedstoreCommand(this@RedstORE));
        }
    }

    override fun onDisable() {
        logger.log(Level.INFO, "RedstORE disabled!")
    }

    public fun addStoreConnection(playerId: UUID, props: ConnectionProperties) {
        var player = players.get(playerId);
        if (player == null) {
            player = RedstOREPlayer();
            players.set(playerId, player);
        }

        logger.log(Level.INFO, "Adding connection at " +
            "(${props.origin.getX()}, ${props.origin.getY()}, ${props.origin.getZ()})");
        removeStoreConnection(props.origin);
        var conn = StorageConnection(
            materials!!, logger, this, props, playerId);
        var task = Bukkit.getScheduler().runTaskTimer(this, conn, 0L, 1L);
        conn.task = task;
        connections.set(props.origin, conn);
        player.connections.set(props.origin, conn);
    }

    public fun removeStoreConnection(block: Block): Boolean {
        val conn = connections.get(block);
        if (conn == null) {
            return false;
        }

        val player = Bukkit.getPlayer(conn.playerId);
        if (player != null) {
            val origin = conn.props.origin;
            player.sendMessage("Connection removed at " +
                "(${origin.getX()}, ${origin.getY()}, ${origin.getZ()})");
        }

        logger.log(Level.INFO, "Removing connection at " +
            "(${block.getX()}, ${block.getY()}, ${block.getZ()})");
        conn.close();
        connections.remove(block);
        players.get(conn.playerId)?.connections?.remove(block);
        return true;
    }
}

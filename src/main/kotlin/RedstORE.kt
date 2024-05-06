import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.block.Block
import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitTask
import org.bukkit.Material
import co.aikar.commands.PaperCommandManager
import java.lang.Runnable
import java.util.logging.Logger;
import java.io.File
import java.util.logging.Level
import commands.RedstoreCommand

class Materials(
    public val target: Material,
) {}

class StorageConnection(
    private val materials: Materials,
    private val logger: Logger,
    private val origin: Block,
    public val redstore: RedstORE,
): Runnable {
    public var task: BukkitTask? = null;

    init {
        origin.setType(materials.target);
    }

    override fun run() {
        if (origin.getType() != materials.target) {
            redstore.removeStoreConnection(origin);
        }
    }
}

class RedstORE: JavaPlugin() {
    var connections = HashMap<Block, StorageConnection>();
    var materials: Materials? = null;

    override fun onEnable() {
        materials = Materials(
            target = Material.matchMaterial("minecraft:target")!!,
        )

        logger.log(Level.INFO, "RedstORE enabled!");

        PaperCommandManager(this).apply {
            registerCommand(RedstoreCommand(this@RedstORE));
        }
    }

    override fun onDisable() {
        logger.log(Level.INFO, "RedstORE disabled!")
    }

    public fun addStoreConnection(block: Block) {
        logger.log(Level.INFO, "Adding connection at " +
            "(${block.getX()}, ${block.getY()}, ${block.getZ()})");
        var conn = StorageConnection(materials!!, logger, block, this);
        var task = Bukkit.getScheduler().runTaskTimer(this, conn, 0L, 1L);
        conn.task = task;
        connections.set(block, conn);
    }

    public fun removeStoreConnection(block: Block): Boolean {
        val conn = connections.get(block);
        if (conn != null) {
            logger.log(Level.INFO, "Removing connection at " +
                "(${block.getX()}, ${block.getY()}, ${block.getZ()})");
            conn.task?.cancel();
            connections.remove(block);
            return true;
        } else {
            return false;
        }
    }
}

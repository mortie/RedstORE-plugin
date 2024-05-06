import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.Location
import co.aikar.commands.PaperCommandManager
import java.io.File
import java.util.logging.Level
import commands.RedstoreCommand

class RedstORE: JavaPlugin() {
    override fun onEnable() {
        logger.log(Level.INFO, "RedstORE enabled!");

        PaperCommandManager(this).apply {
            registerCommand(RedstoreCommand(this@RedstORE))
        }
    }

    override fun onDisable() {
        logger.log(Level.INFO, "RedstORE disabled!")
    }

    public fun addStoreConnection(loc: Location) {
        logger.log(Level.INFO, "Add store connection")
    }
}

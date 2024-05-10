package commands

import redstore.RedstORE
import redstore.ConnectionProperties
import redstore.ConnMode
import co.aikar.commands.BaseCommand
import co.aikar.commands.annotation.*
import org.bukkit.entity.Player
import org.bukkit.block.BlockFace
import java.io.File

@CommandAlias("redstore")
@Description("A command to interface with RedstORE")
class RedstoreCommand(private val redstore: RedstORE): BaseCommand() {
    @Default
    @CatchUnknown
    @Subcommand("version")
    fun info(player: Player) {
        player.sendMessage("Hi! I'm RedstORE ${redstore.description.version}");
    }

    @Subcommand("connect")
    @CommandCompletion("read|write")
    fun connect(player: Player, modeStr: String) {
        val mode = when (modeStr) {
            "read" -> ConnMode.READ;
            "write" -> ConnMode.WRITE;
            else -> {
                player.sendMessage("Unknown mode: '${modeStr}'. Expected 'read' or 'write'.");
                return;
            }
        }

        val block = player.getLocation().subtract(0.0, 1.0, 0.0).getBlock();
        redstore.addStoreConnection(player.getUniqueId(), ConnectionProperties(
            mode = mode,
            origin = block,
            direction = player.getFacing(),
            addressBits = 4,
            wordSize = 16,
            pageSize = 8,

            file = File("hello.txt"),
        ));

        player.sendMessage("Added connection at " +
            "(${block.getX()}, ${block.getY()}, ${block.getZ()})");
    }

    @Subcommand("disconnect")
    fun disconnect(player: Player) {
        val block = player.getLocation().subtract(0.0, 1.0, 0.0).getBlock();
        if (!redstore.removeStoreConnection(block)) {
            player.sendMessage("! No connection at " +
                "(${block.getX()}, ${block.getY()}, ${block.getZ()})");
        }
    }
}

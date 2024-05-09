package commands

import RedstORE
import ConnectionProperties
import ConnMode
import co.aikar.commands.BaseCommand
import co.aikar.commands.annotation.*
import org.bukkit.entity.Player
import org.bukkit.block.BlockFace
import java.io.RandomAccessFile

@CommandAlias("redstore")
@Description("A command to interface with RedstORE")
class RedstoreCommand(private val redstore: RedstORE): BaseCommand() {
    @Default
    @CatchUnknown
    @Subcommand("version")
    fun info(player: Player) {
        player.sendMessage("Hi! I'm RedstORE ${redstore.description.version}");
    }

    fun rotateFacing(facing: BlockFace): BlockFace {
        if (facing == BlockFace.NORTH) {
            return BlockFace.WEST;
        } else if (facing == BlockFace.WEST) {
            return BlockFace.SOUTH;
        } else if (facing == BlockFace.SOUTH) {
            return BlockFace.EAST;
        } else if (facing == BlockFace.EAST) {
            return BlockFace.NORTH;
        } else {
            return BlockFace.UP;
        }
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
        redstore.addStoreConnection(ConnectionProperties(
            mode = mode,
            origin = block,
            direction = player.getFacing(),
            facing = rotateFacing(player.getFacing()),
            addressBits = 4,
            wordSize = 16,
            pageSizeWords = 8,

            file = RandomAccessFile("hello.txt", "rw"),
        ))
        player.sendMessage("Added connection at " +
            "(${block.getX()}, ${block.getY()}, ${block.getZ()})");
    }

    @Subcommand("disconnect")
    fun disconnect(player: Player) {
        val block = player.getLocation().subtract(0.0, 1.0, 0.0).getBlock();
        if (redstore.removeStoreConnection(block)) {
            player.sendMessage("Removed connection at " +
                "(${block.getX()}, ${block.getY()}, ${block.getZ()})");
        } else {
            player.sendMessage("! No connection at " +
                "(${block.getX()}, ${block.getY()}, ${block.getZ()})");
        }
    }
}

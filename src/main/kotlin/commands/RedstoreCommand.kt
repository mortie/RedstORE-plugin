package commands

import RedstORE
import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.*;
import org.bukkit.entity.Player;

@CommandAlias("redstore")
@Description("A command to interface with RedstORE")
class RedstoreCommand(private val redstore: RedstORE): BaseCommand() {
    @Default
    @CatchUnknown
    @Subcommand("info")
    fun info(player: Player) {
        player.sendMessage("Hi! I'm RedstORE ${redstore.description.version}");
    }

    @Subcommand("connect")
    fun connect(player: Player) {
        val block = player.getLocation().subtract(0.0, 1.0, 0.0).getBlock();
        redstore.addStoreConnection(block);
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

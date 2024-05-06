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
        player.sendMessage("Hi! I'm RedstORE ${redstore.description.version}")
    }

    @Subcommand("connect")
    fun add(player: Player) {
        val loc = player.getLocation().subtract(0.0, 1.0, 0.0);
        player.sendMessage("Adding connection to ${loc.getBlockX()},${loc.getBlockY()},${loc.getBlockZ()}")
        redstore.addStoreConnection(loc)
    }
}

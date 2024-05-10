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
    @Subcommand("help")
    @CommandCompletion("connect|disconnect|version|help")
    fun help(p: Player, @Optional command: String?) {
        if (command == null || command == "all") {
            p.sendMessage("Available RedstORE commands:");
            p.sendMessage("/redstore connect <read|write> <file> <params...>");
            p.sendMessage("   Create a new RedstORE connection at your location.");
            p.sendMessage("   Use '/redstore help connect' for info on params.");
            p.sendMessage("/redstore disconnect");
            p.sendMessage("   Disconnect the RedstORE connection at your location.");
            p.sendMessage("/redstore version");
            p.sendMessage("   Print version string.");
            p.sendMessage("/redstore help <subcommand>");
            p.sendMessage("   Print this help text, or help with a subcommand.");
        } else if (command == "connect") {
            p.sendMessage("/redstore connect <read|write> <file> <params...>");
            p.sendMessage("   Create a new RedstORE connection at your location.");
            p.sendMessage("   Available parameters:");
            p.sendMessage("   dir=<direction>: Set direction. One of:");
            p.sendMessage("       north, south, east, west, up, down");
            p.sendMessage("       Default: determined by camera direction");
            p.sendMessage("   addr=<N>: Set the number of address bits.");
            p.sendMessage("       Default: 4");
            p.sendMessage("   ws=<N>: Set the number of bits in a word.");
            p.sendMessage("       Default: 8");
            p.sendMessage("   ps=<N>: Set the number of words in a page.");
            p.sendMessage("       Default: 8");
        } else if (command == "disconnect") {
            p.sendMessage("/redstore disconnect");
            p.sendMessage("   Disconnect the RedstORE connection at your location.");
        } else if (command == "version") {
            p.sendMessage("/redstore version");
            p.sendMessage("   Print version string.");
        } else if (command == "help") {
            p.sendMessage("/redstore help <subcommand>");
            p.sendMessage("   Print general help text, or help with a subcommand.");
        } else {
            p.sendMessage("Unknown subcommand: ${command}.");
            p.sendMessage("Use '/redstone help' for help.");
        }
    }

    @Subcommand("version")
    fun info(player: Player) {
        player.sendMessage("Hi! I'm RedstORE ${redstore.description.version}");
    }

    @Subcommand("connect")
    @CommandCompletion("read|write <file> <params...>")
    fun connect(
        player: Player,
        mode: String,
        path: String,
        params: Array<String>,
    ) {
        @Suppress("NAME_SHADOWING")
        val mode = when (mode) {
            "read" -> ConnMode.READ;
            "write" -> ConnMode.WRITE;
            else -> {
                player.sendMessage(
                    "Unknown mode: '${mode}'. Expected 'read' or 'write'.");
                return;
            }
        }

        var direction = player.getFacing();
        var addressBits = 4;
        var wordSize = 8;
        var pageSize = 8;

        for (param in params) {
            val parts = param.split("=", limit=2);
            if (parts.size != 2) {
                player.sendMessage("Parameter '${param}' is missing an '='");
                return;
            }

            val k = parts[0];
            val v = parts[1];
            if (k == "dir") {
                direction = when (v) {
                    "north" -> BlockFace.NORTH;
                    "south" -> BlockFace.SOUTH;
                    "east" -> BlockFace.EAST;
                    "west" -> BlockFace.WEST;
                    "up" -> BlockFace.UP;
                    "down" -> BlockFace.DOWN;
                    else -> {
                        player.sendMessage("Unknown direction: ${v}");
                        return@connect;
                    }
                }
            } else if (k == "addr") {
                addressBits = v.toInt();
                if (addressBits < 1 || addressBits > 16) {
                    player.sendMessage("Invalid address size: ${addressBits}");
                    return;
                }
            } else if (k == "ws") {
                wordSize = v.toInt();
                if (wordSize < 1 || wordSize > 32) {
                    player.sendMessage("Invalid word size: ${wordSize}");
                    return;
                }
            } else if (k == "ps") {
                pageSize = v.toInt();
                if (pageSize < 1 || pageSize > 512) {
                    player.sendMessage("Invalid page size: ${pageSize}");
                    return;
                }
            } else {
                player.sendMessage("Unknown parameter: '${k}'");
                return;
            }
        }

        val block = player.getLocation().subtract(0.0, 1.0, 0.0).getBlock();
        redstore.addStoreConnection(player, ConnectionProperties(
            mode = mode,
            origin = block,
            direction = direction,
            addressBits = addressBits,
            wordSize = wordSize,
            pageSize = pageSize,

            file = File(path),
        ));
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

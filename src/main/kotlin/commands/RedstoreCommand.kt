package commands

import redstore.RedstORE
import redstore.ConnectionProperties
import redstore.ConnMode
import redstore.LayoutDirection
import redstore.Layout
import co.aikar.commands.BaseCommand
import co.aikar.commands.annotation.*
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import org.bukkit.block.BlockFace
import java.io.FileNotFoundException
import java.nio.file.AccessDeniedException

fun parseDirection(dir: String) = when (dir) {
    "north" -> LayoutDirection.NORTH;
    "south" -> LayoutDirection.SOUTH;
    "east" -> LayoutDirection.EAST;
    "west" -> LayoutDirection.WEST;
    else -> null;
}

fun baseLatencyFromWordCount(wc: Int): Int {
    if (wc <= 256) {
        return 24;
    } else if (wc <= 512) {
        return 26;
    } else if (wc <= 1_024) {
        return 28;
    } else if (wc <= 2_048) {
        return 30;
    } else if (wc <= 4_096) {
        return 32;
    } else if (wc <= 8_192) {
        return 34;
    } else if (wc <= 16_384) {
        return 36;
    } else if (wc <= 32_768) {
        return 38;
    } else if (wc <= 65_536) {
        return 40;
    } else if (wc <= 131_072) {
        return 42;
    } else if (wc <= 262_144) {
        return 44;
    } else if (wc <= 524_288) {
        return 46;
    } else {
        return 48;
    }
}

fun latencyFromWordSize(wordSize: Int): Int {
    if (wordSize <= 8) {
        return 0;
    } else if (wordSize <= 16) {
        return 1;
    } else {
        return 2;
    }
}

fun calculateLatency(pageCount: Int, pageSize: Int, wordSize: Int): Int {
    return baseLatencyFromWordCount(pageCount * pageSize) +
        latencyFromWordSize(wordSize);
}

@CommandAlias("redstore")
@Description("A command to interface with RedstORE")
class RedstoreCommand(private val redstore: RedstORE): BaseCommand() {
    @Default
    @CatchUnknown
    @Subcommand("help")
    @CommandCompletion("connect|disconnect|query|list|version|help")
    fun help(p: Player, @Optional command: String?) {
        if (command == null || command == "all") {
            p.sendMessage("${ChatColor.GREEN}Available RedstORE commands:");
            p.sendMessage(
                "${ChatColor.YELLOW}/redstore connect " +
                "<read|write> <file> <params...>");
            p.sendMessage(" Create a new RedstORE connection at your location.");
            p.sendMessage(" Use '/redstore help connect' for info on params.");
            p.sendMessage("${ChatColor.YELLOW}/redstore disconnect");
            p.sendMessage(" Disconnect the RedstORE connection you're looking at.");
            p.sendMessage("${ChatColor.YELLOW}/redstore open <file>");
            p.sendMessage(" Open a new file at the connection you're looking at.");
            p.sendMessage("${ChatColor.YELLOW}/redstore query");
            p.sendMessage(" Get info about the connection you're looking at.");
            p.sendMessage("${ChatColor.YELLOW}/redstore list");
            p.sendMessage(" List your connections.");
            p.sendMessage("${ChatColor.YELLOW}/redstore version");
            p.sendMessage(" Print version string.");
            p.sendMessage("${ChatColor.YELLOW}/redstore help <subcommand>");
            p.sendMessage(" Print this help text, or get help with a subcommand.");
        } else if (command == "connect") {
            p.sendMessage(
                "${ChatColor.YELLOW}/redstore connect " +
                "<read|write> <file> <params...>");
            p.sendMessage(" Create a new RedstORE connection at your location.");
            p.sendMessage(" Available parameters:");
            p.sendMessage(" dir=<direction>: Set direction. One of:");
            p.sendMessage("    north, south, east, west");
            p.sendMessage("    Default: determined by camera direction");
            p.sendMessage(" addr=<N>: Set the number of address bits.");
            p.sendMessage("    Default: 4");
            p.sendMessage(" ws=<N>: Set the number of bits in a word.");
            p.sendMessage("    Default: 8");
            p.sendMessage(" ps=<N>: Set the number of words in a page.");
            p.sendMessage("    Default: 8");
            p.sendMessage(" count=<N>: Set the number of accessible pages in the file.");
            p.sendMessage("    This dictates the latency of the connection.");
            p.sendMessage("    Default: 2^addr");
            p.sendMessage(" layout=<layout>: Set the layout of the connection. One of:");
            p.sendMessage("    line, diag, towers");
            p.sendMessage("    Default: line");
            p.sendMessage(" colors=<colors>: Set the color scheme. One of:");
            p.sendMessage("    muted, wool");
            p.sendMessage("    Default: muted");
        } else if (command == "disconnect") {
            p.sendMessage("${ChatColor.YELLOW}/redstore disconnect");
            p.sendMessage(" Disconnect the RedstORE connection you're looking at.");
        } else if (command == "open") {
            p.sendMessage("${ChatColor.YELLOW}/redstore open <file>");
            p.sendMessage(" Open a new file at the connection you're looking at.");
        } else if (command == "query") {
            p.sendMessage("${ChatColor.YELLOW}/redstore query");
            p.sendMessage(" Get info about the connection you're looking at.");
        } else if (command == "list") {
            p.sendMessage("${ChatColor.YELLOW}/redstore list");
            p.sendMessage(" List your connections.");
        } else if (command == "version") {
            p.sendMessage("${ChatColor.YELLOW}/redstore version");
            p.sendMessage(" Print version string.");
        } else if (command == "help") {
            p.sendMessage("${ChatColor.YELLOW}/redstore help <subcommand>");
            p.sendMessage(" Print general help text, or get help with a subcommand.");
        } else {
            p.sendMessage("${ChatColor.RED}Unknown subcommand: ${command}.");
            p.sendMessage("${ChatColor.RED}Use '/redstone help' for help.");
        }
    }

    @Subcommand("connect")
    @CommandCompletion("read|write <file> <params...>")
    fun connect(
        player: Player,
        mode: String,
        file: String,
        params: Array<String>,
    ) {
        @Suppress("NAME_SHADOWING")
        val mode = when (mode) {
            "read" -> ConnMode.READ;
            "write" -> ConnMode.WRITE;
            else -> {
                player.sendMessage(
                    "${ChatColor.RED}Unknown mode: '${mode}'. " +
                    "Expected 'read' or 'write'.");
                return;
            }
        }

        var direction = LayoutDirection.fromFace(player.getFacing());
        if (direction == null) {
            player.sendMessage("${ChatColor.RED}Invalid direction: ${player.getFacing()}");
            return;
        }

        var addressBits = 4;
        var wordSize = 8;
        var pageSize = 8;
        var pageCount = -1;
        var layoutName: String? = null;
        var colorsName: String? = null;
        var flipAddr = false;
        var flipData = false;

        for (param in params) {
            if (param == "flip") {
                flipAddr = true;
                flipData = true;
                continue;
            }

            val parts = param.split("=", limit=2);
            if (parts.size != 2) {
                player.sendMessage(
                    "${ChatColor.RED}Parameter '${param}' is missing an '='");
                return;
            }

            val k = parts[0];
            val v = parts[1];
            if (k == "dir") {
                val dir = parseDirection(v);
                if (dir == null) {
                    player.sendMessage("${ChatColor.RED}Unknown direction: ${v}");
                    return;
                }

                direction = dir;
            } else if (k == "addr") {
                addressBits = v.toInt();
                if (addressBits < 1 || addressBits > 16) {
                    player.sendMessage(
                        "${ChatColor.RED}Invalid address size: ${addressBits}");
                    return;
                }
            } else if (k == "ws") {
                wordSize = v.toInt();
                if (wordSize < 1 || wordSize > 32) {
                    player.sendMessage(
                        "${ChatColor.RED}Invalid word size: ${wordSize}");
                    return;
                }
            } else if (k == "ps") {
                pageSize = v.toInt();
                if (pageSize < 1 || pageSize > 512) {
                    player.sendMessage(
                        "${ChatColor.RED}Invalid page size: ${pageSize}");
                    return;
                }
            } else if (k == "count") {
                pageCount = v.toInt();
                if (pageCount < 1) {
                    player.sendMessage(
                        "${ChatColor.RED}Invalid page count: ${pageCount}");
                    return;
                }
            } else if (k == "colors") {
                colorsName = v;
            } else if (k == "layout") {
                layoutName = v;
            } else if (k == "flip") {
                if (v == "data") {
                    flipData = true;
                } else if (v == "addr") {
                    flipAddr = true;
                } else if (v == "y") {
                    flipData = true;
                    flipAddr = true;
                } else if (v == "n") {
                    flipData = false;
                    flipAddr = false;
                } else {
                    player.sendMessage("${ChatColor.RED}Invalid flip option: '${v}'");
                    return;
                }
            } else {
                player.sendMessage("${ChatColor.RED}Unknown parameter: '${k}'");
                return;
            }
        }

        val maxPageCount = Math.pow(2.0, addressBits.toDouble()).toInt();
        if (pageCount < 0) {
            pageCount = maxPageCount;
        } else if (pageCount > maxPageCount) {
            player.sendMessage(
                "${ChatColor.YELLOW}Page count ${pageCount} is too big for " +
                "a ${addressBits} bit address, reducing to ${maxPageCount}");
            pageCount = maxPageCount;
        }

        val latency = when (mode) {
            ConnMode.READ -> calculateLatency(pageCount, pageSize, wordSize);
            ConnMode.WRITE -> 0;
        }

        val layoutSpec = if (layoutName == null) {
            redstore.layouts.getDefault();
        } else {
            redstore.layouts.get(layoutName);
        }
        if (layoutSpec == null) {
            player.sendMessage("${ChatColor.RED}Unknown layout: ${layoutName!!}");
            return;
        }

        val colorScheme = if (colorsName == null) {
            redstore.colorSchemes.getDefault();
        } else {
            redstore.colorSchemes.get(colorsName);
        }
        if (colorScheme == null) {
            player.sendMessage("${ChatColor.RED}Unknown color scheme: ${colorsName!!}");
            return;
        }

        var layout = layoutSpec(direction!!, addressBits, wordSize);

        if (flipData) {
            layout = Layout(
                address = layout.address,
                addressSpacing = layout.addressSpacing,
                data = layout.data.add(
                    layout.dataSpacing.mul(wordSize - 1)),
                dataSpacing = layout.dataSpacing.inv(),
            );
        }

        if (flipAddr) {
            layout = Layout(
                address = layout.address.add(
                    layout.addressSpacing.mul(addressBits - 1)),
                addressSpacing = layout.addressSpacing.inv(),
                data = layout.data,
                dataSpacing = layout.dataSpacing,
            );
        }

        val fullFile = if (file.endsWith(".bin")) file else file + ".bin";

        val origin = player.getLocation().subtract(0.0, 1.0, 0.0).getBlock();
        val props = ConnectionProperties(
            mode = mode,
            origin = origin,
            layout = layout,
            colorScheme = colorScheme,
            addressBits = addressBits,
            wordSize = wordSize,
            pageSize = pageSize,
            pageCount = pageCount,
            latency = latency,
            file = fullFile,
        );

        try {
            redstore.addStoreConnection(player, props);
        } catch (ex: FileNotFoundException) {
            player.sendMessage(
                "${ChatColor.RED}Couldn't open ${fullFile}: No such file");
        } catch (ex: AccessDeniedException) {
            player.sendMessage(
                "${ChatColor.RED}Couldn't open ${fullFile}: Permission denied");
        }
    }

    @Subcommand("disconnect")
    fun disconnect(player: Player) {
        var block = player.rayTraceBlocks(15.0)?.getHitBlock();
        if (block == null) {
            player.sendMessage("${ChatColor.RED}You're not looking at a block.");
            return;
        }

        if (!redstore.removeStoreConnection(block)) {
            player.sendMessage(
                "${ChatColor.RED}No connection at " +
                "(${block.getX()}, ${block.getY()}, ${block.getZ()})");
        }
    }

    @Subcommand("open")
    fun open(player: Player, file: String) {
        var block = player.rayTraceBlocks(15.0)?.getHitBlock();
        if (block == null) {
            player.sendMessage("${ChatColor.RED}You're not looking at a block.");
            return;
        }

        val fullFile = if (file.endsWith(".bin")) file else file + ".bin";
        try {
            if (!redstore.reopenStoreConnection(block, fullFile)) {
                player.sendMessage(
                    "${ChatColor.RED}No connection at " +
                    "(${block.getX()}, ${block.getY()}, ${block.getZ()})");
            }
        } catch (ex: FileNotFoundException) {
            player.sendMessage(
                "${ChatColor.RED}Couldn't open ${fullFile}: No such file");
        } catch (ex: AccessDeniedException) {
            player.sendMessage(
                "${ChatColor.RED}Couldn't open ${fullFile}: Permission denied");
        }
    }

    @Subcommand("query")
    fun query(player: Player) {
        var block = player.rayTraceBlocks(15.0)?.getHitBlock();
        if (block == null) {
            player.sendMessage("${ChatColor.RED}You're not looking at a block.");
            return;
        }

        val meta = redstore.db?.getConnectionMetaWithOrigin(block);
        if (meta == null) {
            player.sendMessage(
                "${ChatColor.RED}No connection at " +
                "(${block.getX()}, ${block.getY()}, ${block.getZ()})");
            return;
        }

        val props = redstore.db?.getConnection(meta.uuid)?.let { it.second };
        if (props == null) {
            player.sendMessage(
                "${ChatColor.RED}Connection ${meta.uuid} exists but seems broken");
            return;
        }

        val owner = Bukkit.getOfflinePlayer(meta.playerUUID);

        player.sendMessage("${ChatColor.GREEN}Connection ${meta.uuid}:");
        player.sendMessage("  Owner: ${owner.getName()}");
        player.sendMessage("  Mode: ${props.mode.toString()}");
        player.sendMessage("  File: ${props.file}");
        player.sendMessage("  Latency: ${props.latency} rticks");
        player.sendMessage("  Enabled: ${meta.enabled}");
        player.sendMessage(
            "  " +
            "addr=${props.addressBits} " +
            "ws=${props.wordSize} " +
            "ps=${props.pageSize} " +
            "count=${props.pageCount}");
    }

    @Subcommand("list")
    fun list(player: Player) {
        player.sendMessage("${ChatColor.GREEN}Your RedstORE connections:");
        redstore.db!!.getPlayerConnections(player.getUniqueId()) { meta, props ->
            val origin = props.origin;
            player.sendMessage(
                "  (${origin.getX()}, ${origin.getY()}, ${origin.getZ()})" +
                " @ ${origin.getWorld().getName()}: ${props.mode} (${meta.uuid})");
        }
    }

    @Subcommand("version")
    fun version(player: Player) {
        player.sendMessage("Hi! I'm RedstORE ${redstore.description.version}");
    }
}

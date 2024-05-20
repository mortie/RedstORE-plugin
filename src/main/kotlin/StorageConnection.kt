package redstore

import org.bukkit.Bukkit
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.Material
import org.bukkit.scheduler.BukkitTask
import org.bukkit.ChatColor
import java.util.logging.Logger
import java.util.UUID
import java.nio.file.Path
import java.nio.file.AccessDeniedException
import java.io.File
import java.io.RandomAccessFile
import java.lang.Math
import redstore.ColorScheme
import redstore.BlockOffset
import redstore.Layout
import redstore.getPlayerBasePath
import redstore.calcDirFileCount
import redstore.calcDirSize

class Materials(
    val readDisabled: Material,
    val readEnabled: Material,
    val readPending: Material,
    val writeDisabled: Material,
    val writeEnabled: Material,
    val writePending: Material,
    val powered: Material,
) {}

enum class ConnMode {
    READ, WRITE
}

data class ConnectionProperties(
    val mode: ConnMode,
    val origin: Block,
    val layout: Layout,
    val colorScheme: ColorScheme,
    val addressBits: Int,
    val wordSize: Int,
    val pageSize: Int,
    val pageCount: Int,
    val latency: Int,
    val dataRate: Int,
    val file: String,
) {
    fun replaceFile(newFile: String): ConnectionProperties {
        // Is there a cleaner way to do this?
        return ConnectionProperties(
            mode = mode,
            origin = origin,
            layout = layout,
            colorScheme = colorScheme,
            addressBits = addressBits,
            wordSize = wordSize,
            pageSize = pageSize,
            pageCount = pageCount,
            latency = latency,
            dataRate = dataRate,
            file = newFile,
        )
    }
}

data class TxnState(
    val address: Int,
    val page: ByteArray,

    var timer: Int,
    var bytePosition: Int,
    var bitPosition: Int,
) {}

private fun checkPlayerCanBreak(player: Player, block: Block): Boolean {
    val evt = BlockBreakEvent(block, player);
    Bukkit.getServer().getPluginManager().callEvent(evt);
    return !evt.isCancelled();
}

fun checkPlayerPermission(player: Player, props: ConnectionProperties): Boolean {
    // Origin
    var block = props.origin;
    if (!checkPlayerCanBreak(player, block)) {
        return false;
    }

    // Address bits
    block = props.layout.address.relativeTo(props.origin);
    repeat(props.addressBits) {
        if (!checkPlayerCanBreak(player, block)) {
            return@checkPlayerPermission false;
        }
        block = props.layout.addressSpacing.relativeTo(block);
    }

    // Data bits
    block = props.layout.data.relativeTo(props.origin);
    repeat(props.wordSize) {
        if (!checkPlayerCanBreak(player, block)) {
            return@checkPlayerPermission false;
        }
        block = props.layout.dataSpacing.relativeTo(block);
    }

    return true;
}

class StorageConnection(
    private val materials: Materials,
    private val logger: Logger,
    private val redstore: RedstORE,
    private var enabled: Boolean,
    private var playerUUID: UUID,
    public val props: ConnectionProperties,
): Runnable {
    public var task: BukkitTask? = null;

    val disabledMaterial: Material;
    val enabledMaterial: Material;
    val pendingMaterial: Material;

    val addressBlocksStart: Block;
    val dataBlocksStart: Block;
    val pageSizeBytes: Int;
    val filePath: Path;
    val basePath: Path;

    var transaction: TxnState? = null;

    init {

        basePath = getPlayerBasePath(redstore.basePath!!, playerUUID);
        filePath = basePath.resolve(props.file).normalize();
        if (!filePath.startsWith(basePath)) {
            throw AccessDeniedException(props.file);
        }

        filePath.getParent().toFile().mkdirs();

        pageSizeBytes = Math.ceil(
            (props.pageSize.toDouble() * props.wordSize.toDouble()) /
            8.toDouble()).toInt();

        when (props.mode) {
            ConnMode.READ -> {
                disabledMaterial = materials.readDisabled;
                enabledMaterial = materials.readEnabled;
                pendingMaterial = materials.readPending;
            }
            ConnMode.WRITE -> {
                disabledMaterial = materials.writeDisabled;
                enabledMaterial = materials.writeEnabled;
                pendingMaterial = materials.writePending;
            }
        }

        var block: Block;

        block = props.origin;
        block.setType(when (enabled) {
            true -> enabledMaterial;
            false -> disabledMaterial;
        });

        addressBlocksStart = props.layout.address.relativeTo(props.origin);
        block = addressBlocksStart;
        block.setType(props.colorScheme.addressMSB);
        repeat(props.addressBits - 1) {
            block = props.layout.addressSpacing.relativeTo(block);
            block.setType(props.colorScheme.address);
        }

        dataBlocksStart = props.layout.data.relativeTo(props.origin);
        block = dataBlocksStart;
        block.setType(props.colorScheme.dataMSB);
        repeat(props.wordSize - 1) {
            block = props.layout.dataSpacing.relativeTo(block);
            block.setType(props.colorScheme.data);
        }
    }

    fun sendMessageToOwner(msg: String) {
        val player = Bukkit.getPlayer(playerUUID);
        if (player == null) {
            return;
        }

        player.sendMessage(msg);
    }

    // Throws an exception if opening the file fails
    fun healthcheck() {
        var file: RandomAccessFile? = null;
        try {
            file = RandomAccessFile(filePath.toFile(), when (props.mode) {
                ConnMode.READ -> "r";
                ConnMode.WRITE -> "rw";
            });
        } finally {
            file?.close();
        }
    }

    fun close() {
        task?.cancel();
        transaction = null;
    }

    fun destroy() {
        close();

        var block = props.origin;
        block.setType(Material.AIR);

        block = props.layout.address.relativeTo(props.origin);
        repeat(props.addressBits) {
            block.setType(Material.AIR);
            block = props.layout.addressSpacing.relativeTo(block);
        }

        block = props.layout.data.relativeTo(props.origin);
        repeat(props.wordSize) {
            block.setType(Material.AIR);
            block = props.layout.dataSpacing.relativeTo(block);
        }
    }

    fun readBlockBits(start: Block, count: Int, spacing: BlockOffset): Int {
        var block = start;
        var num = 0;
        repeat(count) { index ->
            if (block.isBlockPowered()) {
                num = num or (1 shl (count - index - 1));
            }
            block = spacing.relativeTo(block);
        }

        return num;
    }

    fun isEnabled(): Boolean {
        return enabled;
    }

    fun setEnabled(newEnabled: Boolean) {
        if (newEnabled == enabled) {
            return;
        }

        enabled = newEnabled;

        if (transaction != null) {
            endTransaction();
        }

        props.origin.setType(when (enabled) {
            true -> enabledMaterial;
            false -> disabledMaterial;
        });
    }

    override fun run() {
        if (transaction == null) {
            val activatePowered = props.origin.isBlockPowered();
            if (!activatePowered) {
                return;
            }

            val address = readBlockBits(
                addressBlocksStart, props.addressBits, props.layout.addressSpacing);

            transaction = TxnState(
                address = address,
                page = ByteArray(pageSizeBytes),

                timer = props.latency * 2, // 2 redstone ticks per game tick
                bytePosition = 0,
                bitPosition = 0,
            );

            val txn = transaction!!;
            if (props.mode == ConnMode.READ && address < props.pageCount) {
                var file: RandomAccessFile? = null;
                try {
                    file = RandomAccessFile(filePath.toFile(), "r");
                    file.seek(address.toLong() * pageSizeBytes);
                    file.read(txn.page);
                } catch (ex: Exception) {
                    logger.warning("Failed to read ${filePath}: ${ex.toString()}");
                    sendMessageToOwner("Failed to read ${props.file}!");
                } finally {
                    file?.close();
                }
            }

            props.origin.setType(pendingMaterial);
            handleTransaction();
        } else {
            handleTransaction();
        }
    }

    fun handleRead() {
        props.origin.setType(materials.powered);

        val txn = transaction!!;

        var num = 0L;
        repeat(props.wordSize) { index ->
            if (txn.bytePosition >= txn.page.size) {
                return@repeat;
            }

            val byte = txn.page[txn.bytePosition].toInt();
            val bit = byte and (1 shl txn.bitPosition);
            if (bit != 0) {
                num = num or (1L shl index);
            }

            txn.bitPosition += 1;
            if (txn.bitPosition >= 8) {
                txn.bitPosition = 0;
                txn.bytePosition += 1;
            }
        }

        var block = dataBlocksStart;
        repeat(props.wordSize) { index ->
            val bit = num and (1L shl (props.wordSize - index - 1));
            val material: Material;
            if (bit == 0L) {
                if (index == 0) {
                    material = props.colorScheme.dataMSB;
                } else {
                    material = props.colorScheme.data;
                }
            } else {
                material = materials.powered;
            }
            block.setType(material);
            block = props.layout.dataSpacing.relativeTo(block);
        }
    }

    fun handleWrite() {
        val txn = transaction!!;

        val bits = readBlockBits(
            dataBlocksStart, props.wordSize, props.layout.dataSpacing);
        repeat(props.wordSize) { index ->
            if (txn.bytePosition >= txn.page.size) {
                return@repeat;
            }

            var byte = txn.page[txn.bytePosition].toInt();
            if (bits and (1 shl index) == 0) {
                byte = byte and (1 shl txn.bitPosition).inv();
            } else {
                byte = byte or (1 shl txn.bitPosition);
            }
            txn.page[txn.bytePosition] = byte.toByte();

            txn.bitPosition += 1;
            if (txn.bitPosition >= 8) {
                txn.bitPosition = 0;
                txn.bytePosition += 1;
            }
        }
    }

    fun endTransaction() {
        val txn = transaction!!;
        transaction = null;

        props.origin.setType(when (enabled) {
            true -> enabledMaterial;
            false -> disabledMaterial;
        });

        var block = addressBlocksStart;
        block.setType(props.colorScheme.addressMSB);
        repeat(props.addressBits - 1) {
            block = props.layout.addressSpacing.relativeTo(block);
            block.setType(props.colorScheme.address);
        }

        block = dataBlocksStart;
        block.setType(props.colorScheme.dataMSB);
        repeat(props.wordSize - 1) {
            block = props.layout.dataSpacing.relativeTo(block);
            block.setType(props.colorScheme.data);
        }

        if (props.mode == ConnMode.WRITE && txn.address < props.pageCount) {
            val length = (txn.address.toLong() + 1L) * pageSizeBytes;
            var file: RandomAccessFile? = null;
            try {
                file = RandomAccessFile(filePath.toFile(), "rw");
                val oldLength = file.length();
                if (oldLength < length) {
                    if (!canPlayerExtendFile(oldLength, length)) {
                        return;
                    }
                    file.setLength(length);
                }
                file.seek(txn.address.toLong() * pageSizeBytes);
                file.write(txn.page);
            } catch (ex: Exception) {
                logger.warning("Failed to write file: ${ex.toString()}");
                sendMessageToOwner("Failed to write to ${props.file}!");
            } finally {
                file?.close();
            }
        }
    }

    fun handleTransaction() {
        val txn = transaction!!;

        txn.timer -= 1;
        val tick = txn.timer <= 0;

        if (tick) {
            if (txn.bytePosition >= txn.page.size) {
                endTransaction();
                return;
            }

            txn.timer = props.dataRate * 2;
            when (props.mode) {
                ConnMode.READ -> handleRead();
                ConnMode.WRITE -> handleWrite();
            }
        }
    }

    fun canPlayerExtendFile(oldLength: Long, newLength: Long): Boolean {
        if (newLength > redstore.maxFileSize) {
            sendMessageToOwner(
                "${ChatColor.RED}Can't increase file size: " +
                "Would exceed max file size ${redstore.maxFileSize} bytes");
            return false;
        }

        val newDirSize = calcDirSize(basePath.toFile()) + (newLength - oldLength);
        if (newDirSize > redstore.maxPlayerSpaceUsage) {
            sendMessageToOwner(
                "${ChatColor.RED}Can't increase file size: " +
                "Would exceed max total space usage " +
                "${redstore.maxPlayerSpaceUsage} bytes");
            return false;
        }

        return true;
    }
}

package redstore

import org.bukkit.Bukkit
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.Material
import org.bukkit.scheduler.BukkitTask
import java.util.logging.Logger
import java.util.UUID
import java.io.File
import java.io.RandomAccessFile
import java.lang.Math

class Materials(
    public val originEnabled: Material,
    public val originDisabled: Material,
    public val onBlock: Material,
    public val writeBit: Material,
    public val readBit: Material,
    public val addressBits: Material,
    public val dataBits: Material,
) {}

enum class ConnMode {
    READ, WRITE
}

data class ConnectionProperties(
    val mode: ConnMode,
    val origin: Block,
    val direction: BlockFace,
    val addressBits: Int,
    val wordSize: Int,
    val pageSize: Int,
    val pageCount: Int,
    val latency: Int,
    val file: String,
) {}

data class TxnState(
    val address: Int,
    val page: ByteArray,

    var timer: Int,
    var bytePosition: Int,
    var bitPosition: Int,
) {}

fun checkPlayerCanBreak(player: Player, block: Block): Boolean {
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

    // Activate block
    block = block.getRelative(props.direction, 2);
    if (!checkPlayerCanBreak(player, block)) {
        return false;
    }

    // Address bits
    repeat(props.addressBits) {
        block = block.getRelative(props.direction, 2);
        if (!checkPlayerCanBreak(player, block)) {
            return@checkPlayerPermission false;
        }
    }

    // Data bits
    repeat(props.wordSize) {
        block = block.getRelative(props.direction, 2);
        if (!checkPlayerCanBreak(player, block)) {
            return@checkPlayerPermission false;
        }
    }

    return true;
}

class StorageConnection(
    private val materials: Materials,
    private val logger: Logger,
    private val redstore: RedstORE,
    private var enabled: Boolean,
    public val props: ConnectionProperties,
): Runnable {
    public var task: BukkitTask? = null;

    val activateMaterial: Material;
    val activateBlock: Block;
    val addressBlocksStart: Block;
    val dataBlocksStart: Block;
    val pageSizeBytes: Int;
    val file: RandomAccessFile;

    var transaction: TxnState? = null;

    init {
        val path = redstore.basePath!!.resolve(props.file).normalize();
        if (!path.startsWith(redstore.basePath)) {
            throw Exception("Bad path name");
        }

        path.getParent()?.toFile()?.mkdirs();

        file = RandomAccessFile(path.toFile(), when (props.mode) {
            ConnMode.READ -> "r";
            ConnMode.WRITE -> "rw";
        });

        pageSizeBytes = Math.ceil(
            (props.pageSize.toDouble() * props.wordSize.toDouble()) /
            8.toDouble()).toInt();

        var block = props.origin;
        block.setType(when (enabled) {
            true -> materials.originEnabled;
            false -> materials.originDisabled;
        });

        block = block.getRelative(props.direction, 2);
        activateMaterial = when (props.mode) {
            ConnMode.READ -> materials.readBit;
            ConnMode.WRITE -> materials.writeBit;
        };
        block.setType(activateMaterial);
        activateBlock = block;

        addressBlocksStart = block.getRelative(props.direction, 2);
        repeat(props.addressBits) {
            block = block.getRelative(props.direction, 2);
            block.setType(materials.addressBits);
        }

        dataBlocksStart = block.getRelative(props.direction, 2);
        repeat(props.wordSize) {
            block = block.getRelative(props.direction, 2);
            block.setType(materials.dataBits);
        }
    }

    fun close() {
        task?.cancel();
        transaction = null;
        file.close();
    }

    fun readBlockBits(start: Block, count: Int): Int {
        var block = start;
        var num = 0;
        repeat(count) { index ->
            if (block.isBlockPowered()) {
                num = num or (1 shl (count - index - 1));
            }
            block = block.getRelative(props.direction, 2);
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
            true -> materials.originEnabled;
            false -> materials.originDisabled;
        });
    }

    override fun run() {
        if (props.origin.getType() != materials.originEnabled) {
            redstore.removeStoreConnection(props.origin);
            return;
        }

        if (transaction == null) {
            val activatePowered = activateBlock.isBlockPowered();
            if (!activatePowered) {
                return;
            }

            val address = readBlockBits(addressBlocksStart, props.addressBits);

            logger.info("Begin ${props.mode} txn, page ${address}");
            transaction = TxnState(
                address = address,
                page = ByteArray(pageSizeBytes),

                timer = props.latency * 2, // 2 redstone ticks per gamm tick
                bytePosition = 0,
                bitPosition = 0,
            );

            val txn = transaction!!;
            if (props.mode == ConnMode.READ && address < props.pageCount) {
                file.seek(address.toLong() * pageSizeBytes);
                file.read(txn.page);
            }

            activateBlock.setType(materials.onBlock);
            handleTransaction();
        } else {
            handleTransaction();
        }
    }

    fun handleRead() {
        val txn = transaction!!;

        var block = dataBlocksStart.getRelative(props.direction, (props.wordSize - 1) * 2);
        val direction = props.direction.getOppositeFace();
        repeat(props.wordSize) {
            if (txn.bytePosition >= txn.page.size) {
                block.setType(materials.dataBits);
                return@repeat;
            }

            val byte = txn.page[txn.bytePosition].toInt();
            val bit = byte and (1 shl txn.bitPosition);
            block.setType(if (bit == 0) materials.dataBits else materials.onBlock);

            txn.bitPosition += 1;
            if (txn.bitPosition >= 8) {
                txn.bitPosition = 0;
                txn.bytePosition += 1;
            }

            block = block.getRelative(direction, 2);
        }
    }

    fun handleWrite() {
        val txn = transaction!!;

        val bits = readBlockBits(dataBlocksStart, props.wordSize);
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
        logger.info("End ${props.mode} txn, page ${txn.address}");
        transaction = null;

        activateBlock.setType(activateMaterial);

        var block = addressBlocksStart;
        repeat(props.addressBits) {
            block.setType(materials.addressBits);
            block = block.getRelative(props.direction, 2);
        }

        block = dataBlocksStart;
        repeat(props.wordSize) {
            block.setType(materials.dataBits);
            block = block.getRelative(props.direction, 2);
        }

        if (props.mode == ConnMode.WRITE && txn.address < props.pageCount) {
            val length = (txn.address.toLong() + 1L) * pageSizeBytes;
            if (file.length() < length) {
                file.setLength(length);
            }

            file.seek(txn.address.toLong() * pageSizeBytes);
            file.write(txn.page);
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

            txn.timer = 4;
            when (props.mode) {
                ConnMode.READ -> handleRead();
                ConnMode.WRITE -> handleWrite();
            }
        }
    }
}

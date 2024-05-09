import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.Material
import org.bukkit.scheduler.BukkitTask
import java.util.logging.Logger
import java.util.logging.Level
import java.util.UUID
import java.io.RandomAccessFile
import java.lang.Math

class Materials(
    public val origin: Material,
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
    val origin: Block,
    val direction: BlockFace,
    val facing: BlockFace,
    val addressBits: Int,
    val wordSize: Int,
    val pageSizeWords: Int,
    val mode: ConnMode,

    val file: RandomAccessFile,
) {}

data class TxnState(
    val address: Int,
    val page: ByteArray,

    var timer: Int,
    var bytePosition: Int,
    var bitPosition: Int,
) {}

class StorageConnection(
    private val materials: Materials,
    private val logger: Logger,
    private val redstore: RedstORE,
    public val props: ConnectionProperties,
    public val playerId: UUID,
): Runnable {
    public var task: BukkitTask? = null;

    val activateMaterial: Material;
    val activateBlock: Block;
    val addressBlocksStart: Block;
    val dataBlocksStart: Block;
    val pageSizeBytes: Int;

    var transaction: TxnState? = null;

    init {
        pageSizeBytes = Math.ceil(
            (props.pageSizeWords.toDouble() * props.wordSize.toDouble()) /
            8.toDouble()).toInt();

        var block = props.origin;
        block.setType(materials.origin);
        block = block.getRelative(props.direction, 2);

        activateMaterial = when (props.mode) {
            ConnMode.READ -> materials.readBit;
            ConnMode.WRITE -> materials.writeBit;
        };
        block.setType(activateMaterial);
        activateBlock = block;
        block = block.getRelative(props.direction, 2);

        addressBlocksStart = block;
        repeat(props.addressBits) {
            block.setType(materials.addressBits);
            block = block.getRelative(props.direction, 2);
        }

        dataBlocksStart = block;
        repeat(props.wordSize) {
            block.setType(materials.dataBits);
            block = block.getRelative(props.direction, 2);
        }
    }

    fun close() {
        task?.cancel();
        transaction = null;
        props.file.close();
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

    override fun run() {
        if (props.origin.getType() != materials.origin) {
            redstore.removeStoreConnection(props.origin);
            return;
        }

        if (transaction == null) {
            val activatePowered = activateBlock.isBlockPowered();
            if (!activatePowered) {
                return;
            }

            val address = readBlockBits(addressBlocksStart, props.addressBits);

            logger.log(Level.INFO, "Begin ${props.mode} txn, page ${address}");
            transaction = TxnState(
                address = address,
                page = ByteArray(pageSizeBytes),

                timer = 0,
                bytePosition = 0,
                bitPosition = 0,
            );

            var txn = transaction!!;
            if (props.mode == ConnMode.READ) {
                props.file.seek(address.toLong() * pageSizeBytes);
                props.file.read(txn.page);
            }

            activateBlock.setType(materials.onBlock);
            handleTransaction();
        } else {
            handleTransaction();
        }
    }

    fun handleRead() {
        var txn = transaction!!;

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
        var txn = transaction!!;

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
        var txn = transaction!!;
        logger.log(Level.INFO, "End ${props.mode} txn, page ${txn.address}");
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

        if (props.mode == ConnMode.WRITE) {
            val length = (txn.address.toLong() + 1L) * pageSizeBytes;
            if (props.file.length() < length) {
                props.file.setLength(length);
            }

            props.file.seek(txn.address.toLong() * pageSizeBytes);
            props.file.write(txn.page);
        }
    }

    fun handleTransaction() {
        var txn = transaction!!;

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

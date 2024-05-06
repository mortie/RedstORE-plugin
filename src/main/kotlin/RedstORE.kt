import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.block.Block
import org.bukkit.block.data.type.Repeater
import org.bukkit.block.data.Powerable
import org.bukkit.block.BlockFace
import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitTask
import org.bukkit.Material
import co.aikar.commands.PaperCommandManager
import java.lang.Runnable
import java.util.logging.Logger
import java.io.RandomAccessFile
import java.util.logging.Level
import commands.RedstoreCommand

class Materials(
    public val origin: Material,
    public val repeater: Material,
    public val writeBit: Material,
    public val readBit: Material,
    public val addressBits: Material,
    public val dataBits: Material,
) {}

data class ConnectionProperties(
    val origin: Block,
    val direction: BlockFace,
    val facing: BlockFace,
    val addressBits: Int,
    val dataBits: Int,
    val pageSize: Int,

    val file: RandomAccessFile,
) {}

enum class TxnMode {
    READ, WRITE
}

data class TxnState(
    val mode: TxnMode,
    val address: Int,
    val dataRepeaters: Array<Repeater>,
    val addressRepeaters: Array<Repeater>,
    val page: ByteArray,

    var timer: Int,
    var bytePosition: Int,
    var bitPosition: Int,
) {}

class StorageConnection(
    private val materials: Materials,
    private val logger: Logger,
    private val redstore: RedstORE,
    private val props: ConnectionProperties,
): Runnable {
    public var task: BukkitTask? = null;

    val writeBit: Block;
    val readBit: Block;
    val addressBitsStart: Block;
    val dataBitsStart: Block;

    val repeaterOff: Repeater;
    val repeaterOn: Repeater;

    var transaction: TxnState? = null;

    init {
        val repeaterData = materials.repeater.createBlockData() as Repeater;
        repeaterData.setFacing(props.facing);
        repeaterOff = repeaterData;

        repeaterOn = repeaterData.clone() as Repeater;
        repeaterOn.setPowered(true);

        var block = props.origin;
        block.setType(materials.origin);
        block = block.getRelative(props.direction, 2);

        block.setType(materials.writeBit);
        writeBit = block.getRelative(0, 1, 0);
        writeBit.setBlockData(repeaterData);
        block = block.getRelative(props.direction, 2);

        block.setType(materials.readBit);
        readBit = block.getRelative(0, 1, 0);
        readBit.setBlockData(repeaterData);
        block = block.getRelative(props.direction, 2);

        addressBitsStart = block.getRelative(0, 1, 0);
        repeat(props.addressBits) {
            block.setType(materials.addressBits);
            block.getRelative(0, 1, 0).setBlockData(repeaterData);
            block = block.getRelative(props.direction, 2);
        }

        dataBitsStart = block.getRelative(0, 1, 0);
        repeat(props.dataBits) {
            block.setType(materials.dataBits);
            block.getRelative(0, 1, 0).setBlockData(repeaterData);
            block = block.getRelative(props.direction, 2);
        }
    }

    fun close() {
        task?.cancel();
        transaction = null;
        props.file.close();
    }

    fun readBits(start: Block, count: Int): Int {
        var block = start;
        var num = 0;
        repeat(count) {
            num = num shl 1;
            val data = block.getBlockData() as? Powerable;
            if (data == null) {
                block = block.getRelative(props.direction, 2);
                return@repeat;
            }

            num = num or (if (data.isPowered()) 1 else 0);
            block = block.getRelative(props.direction, 2);
        }

        return num;
    }

    override fun run() {
        if (props.origin.getType() != materials.origin) {
            redstore.removeStoreConnection(props.origin);
        }

        if (transaction == null) {
            val readData = readBit.getBlockData() as? Repeater;
            val writeData = writeBit.getBlockData() as? Repeater;
            if (readData == null || writeData == null) {
                return;
            }

            val shouldRead = readData.isPowered();
            val shouldWrite = writeData.isPowered();

            val mode: TxnMode;
            if (shouldRead && !shouldWrite) {
                mode = TxnMode.READ;
            } else if (shouldWrite && !shouldRead) {
                mode = TxnMode.WRITE;
            } else {
                return;
            }

            val address = readBits(addressBitsStart, props.addressBits);

            val addressRepeaters = Array<Repeater>(props.addressBits) { index ->
                val bit = props.addressBits - index - 1;
                val x = address and (1 shl bit);
                if (x == 0) repeaterOff else repeaterOn;
            }

            logger.log(Level.INFO, "Begin txn: R? ${shouldRead}, W? ${shouldWrite}, addr: ${address}");
            transaction = TxnState(
                mode = mode,
                address = address,
                addressRepeaters = addressRepeaters,
                dataRepeaters = Array<Repeater>(props.dataBits) { repeaterOff },
                page = ByteArray(props.pageSize),

                timer = 1,
                bytePosition = 0,
                bitPosition = 0,
            );

            var txn = transaction!!;
            if (shouldRead) {
                props.file.seek(address.toLong() * props.pageSize);
                props.file.read(txn.page);
            }
        } else {
            handleTransaction();
        }
    }

    fun handleRead() {
        var txn = transaction!!;

        repeat(txn.dataRepeaters.size) { index ->
            if (txn.bytePosition >= txn.page.size) {
                txn.dataRepeaters[index] = repeaterOff;
                return@repeat;
            }

            val byte = txn.page[txn.bytePosition].toInt();
            val bit = byte and (1 shl txn.bitPosition);
            txn.dataRepeaters[index] = if (bit == 0) repeaterOff else repeaterOn;

            txn.bitPosition += 1;
            if (txn.bitPosition >= 8) {
                txn.bitPosition = 0;
                txn.bytePosition += 1;
            }
        }
    }

    fun handleWrite() {
        var txn = transaction!!;

        // TODO
        txn.bytePosition = txn.page.size;
    }

    fun endTransaction() {
        transaction = null;

        var pos = addressBitsStart;
        repeat(props.addressBits) {
            pos.setBlockData(repeaterOff);
            pos = pos.getRelative(props.direction, 2);
        }

        pos = dataBitsStart;
        repeat(props.dataBits) {
            pos.setBlockData(repeaterOff);
            pos = pos.getRelative(props.direction, 2);
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
            when (txn.mode) {
                TxnMode.READ -> handleRead();
                TxnMode.WRITE -> handleWrite();
            }
        }

        var pos = addressBitsStart;
        for (repeater in txn.addressRepeaters) {
            pos.setBlockData(repeater);
            pos = pos.getRelative(props.direction, 2);
        }

        if (txn.mode == TxnMode.READ) {
            pos = dataBitsStart;
            for (repeater in txn.dataRepeaters) {
                pos.setBlockData(repeater);
                pos = pos.getRelative(props.direction, 2);
            }
        }
    }
}

class RedstORE: JavaPlugin() {
    var connections = HashMap<Block, StorageConnection>();
    var materials: Materials? = null;

    override fun onEnable() {
        materials = Materials(
            origin = Material.matchMaterial("minecraft:sea_lantern")!!,
            repeater = Material.matchMaterial("minecraft:repeater")!!,
            writeBit = Material.matchMaterial("minecraft:red_wool")!!,
            readBit = Material.matchMaterial("minecraft:lime_wool")!!,
            addressBits = Material.matchMaterial("minecraft:blue_wool")!!,
            dataBits = Material.matchMaterial("minecraft:brown_wool")!!,
        )

        logger.log(Level.INFO, "RedstORE enabled!");

        PaperCommandManager(this).apply {
            registerCommand(RedstoreCommand(this@RedstORE));
        }
    }

    override fun onDisable() {
        logger.log(Level.INFO, "RedstORE disabled!")
    }

    public fun addStoreConnection(props: ConnectionProperties) {
        logger.log(Level.INFO, "Adding connection at " +
            "(${props.origin.getX()}, ${props.origin.getY()}, ${props.origin.getZ()})");
        removeStoreConnection(props.origin);
        var conn = StorageConnection(
            materials!!, logger, this, props);
        var task = Bukkit.getScheduler().runTaskTimer(this, conn, 0L, 1L);
        conn.task = task;
        connections.set(props.origin, conn);
    }

    public fun removeStoreConnection(block: Block): Boolean {
        val conn = connections.get(block);
        if (conn != null) {
            logger.log(Level.INFO, "Removing connection at " +
                "(${block.getX()}, ${block.getY()}, ${block.getZ()})");
            conn.close();
            connections.remove(block);
            return true;
        } else {
            return false;
        }
    }
}

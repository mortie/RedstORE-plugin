package redstore

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.io.File
import java.util.UUID
import java.util.logging.Logger
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.Bukkit
import org.bukkit.Material
import redstore.ConnectionProperties
import redstore.ConnMode
import redstore.Layout
import redstore.BlockOffset
import redstore.ColorScheme

object Connections: Table("storage_connections_v1") {
    val uuid = uuid("uuid").uniqueIndex();
    val mode = char("mode");
    val enabled = bool("enabled");

    val worldUUID = uuid("world_uuid");
    val originX = integer("origin_x");
    val originY = integer("origin_y");
    val originZ = integer("origin_z");

    val addrOffX = integer("addr_offset_x");
    val addrOffY = integer("addr_offset_y");
    val addrOffZ = integer("addr_offset_z");
    val addrSpaceX = integer("addr_spacing_x");
    val addrSpaceY = integer("addr_spacing_y");
    val addrSpaceZ = integer("addr_spacing_z");

    val dataOffX = integer("data_offset_x");
    val dataOffY = integer("data_offset_y");
    val dataOffZ = integer("data_offset_z");
    val dataSpaceX = integer("data_spacing_x");
    val dataSpaceY = integer("data_spacing_y");
    val dataSpaceZ = integer("data_spacing_z");

    val addrMaterial = text("address_material");
    val addrMSBMaterial = text("address_msb_material");
    val dataMaterial = text("data_material");
    val dataMSBMaterial = text("data_msb_material");

    val addressBits = integer("address_bits");
    val wordSize = integer("word_size");
    val pageSize = integer("page_size");
    val pageCount = integer("page_count");
    val latency = integer("latency");
    val skew = integer("skew").default(0);
    val dataRate = integer("data_rate");
    val filePath = text("file_path");
    val playerUUID = uuid("player_uuid");
    override val primaryKey = PrimaryKey(uuid);
}

data class ConnectionMeta(
    val uuid: UUID,
    val playerUUID: UUID,
    val enabled: Boolean,
)

class RedstOREDatabase(
    dbFile: String,
    val logger: Logger,
) {
    private val db = Database.connect(
        "jdbc:sqlite:${dbFile}", driver = "org.sqlite.JDBC")

    init {
        transaction(db) {
            SchemaUtils.createMissingTablesAndColumns(Connections);
        }
    }

    private fun parseConnection(
        it: ResultRow,
    ): Pair<ConnectionMeta, ConnectionProperties>? {
        val mode = when (it[Connections.mode]) {
            'R' -> ConnMode.READ;
            'W' -> ConnMode.WRITE;
            else -> null;
        };
        if (mode == null) {
            logger.warning(
                "Connection with unrecognized mode " +
                "${it[Connections.mode]}");
            return null;
        }

        val worldUUID = it[Connections.worldUUID];
        val world = Bukkit.getWorld(worldUUID);
        if (world == null) {
            logger.warning("Connection at non-existent world ${worldUUID}");
            return null;
        }

        val meta = ConnectionMeta(
            uuid = it[Connections.uuid],
            playerUUID = it[Connections.playerUUID],
            enabled = it[Connections.enabled],
        );

        val addrMaterial = Material.matchMaterial(it[Connections.addrMaterial]);
        val addrMSBMaterial = Material.matchMaterial(it[Connections.addrMSBMaterial]);
        val dataMaterial = Material.matchMaterial(it[Connections.dataMaterial]);
        val dataMSBMaterial = Material.matchMaterial(it[Connections.dataMSBMaterial]);

        if (
            addrMaterial == null ||
            addrMSBMaterial == null ||
            dataMaterial == null ||
            dataMSBMaterial == null
        ) {
            logger.warning(
                "Connection with bad addr material name");
            return null;
        }

        val props = ConnectionProperties(
            mode = mode,
            origin = world.getBlockAt(
                it[Connections.originX],
                it[Connections.originY],
                it[Connections.originZ]),
            layout = Layout(
                address = BlockOffset(
                    it[Connections.addrOffX],
                    it[Connections.addrOffY],
                    it[Connections.addrOffZ],
                ),
                addressSpacing = BlockOffset(
                    it[Connections.addrSpaceX],
                    it[Connections.addrSpaceY],
                    it[Connections.addrSpaceZ],
                ),
                data = BlockOffset(
                    it[Connections.dataOffX],
                    it[Connections.dataOffY],
                    it[Connections.dataOffZ],
                ),
                dataSpacing = BlockOffset(
                    it[Connections.dataSpaceX],
                    it[Connections.dataSpaceY],
                    it[Connections.dataSpaceZ],
                ),
            ),
            colorScheme = ColorScheme(
                addrMaterial,
                addrMSBMaterial,
                dataMaterial,
                dataMSBMaterial,
            ),
            addressBits = it[Connections.addressBits],
            wordSize = it[Connections.wordSize],
            pageSize = it[Connections.pageSize],
            pageCount = it[Connections.pageCount],
            latency = it[Connections.latency],
            skew = it[Connections.skew],
            dataRate = it[Connections.dataRate],
            file = it[Connections.filePath],
        );

        return Pair(meta, props);
    }

    fun getConnection(uuid: UUID): Pair<ConnectionMeta, ConnectionProperties>? {
        return transaction(db) {
            Connections.select {
                Connections.uuid eq uuid
            }.firstOrNull()?.let {
                parseConnection(it);
            }
        }
    }

    fun getConnectionMetaWithOrigin(block: Block): ConnectionMeta? {
        val worldUUID = block.getWorld().getUID();
        return transaction(db) {
            Connections.select {
                (Connections.worldUUID eq worldUUID) and
                (Connections.originX eq block.getX()) and
                (Connections.originY eq block.getY()) and
                (Connections.originZ eq block.getZ())
            }.firstOrNull()?.let {ConnectionMeta(
                uuid = it[Connections.uuid],
                playerUUID = it[Connections.playerUUID],
                enabled = it[Connections.enabled],
            )};
        }
    }

    fun addConnection(playerUUID: UUID, enabled: Boolean, props: ConnectionProperties): UUID {
        val uuid = UUID.randomUUID();
        transaction(db) {
            Connections.insert {
                it[Connections.uuid] = uuid;
                it[Connections.mode] = when (props.mode) {
                    ConnMode.READ -> 'R';
                    ConnMode.WRITE -> 'W';
                };
                it[Connections.enabled] = enabled;

                it[Connections.worldUUID] = props.origin.getWorld().getUID();
                it[Connections.originX] = props.origin.getX();
                it[Connections.originY] = props.origin.getY();
                it[Connections.originZ] = props.origin.getZ();

                it[Connections.addrOffX] = props.layout.address.x;
                it[Connections.addrOffY] = props.layout.address.y;
                it[Connections.addrOffZ] = props.layout.address.z;
                it[Connections.addrSpaceX] = props.layout.addressSpacing.x;
                it[Connections.addrSpaceY] = props.layout.addressSpacing.y;
                it[Connections.addrSpaceZ] = props.layout.addressSpacing.z;

                it[Connections.dataOffX] = props.layout.data.x;
                it[Connections.dataOffY] = props.layout.data.y;
                it[Connections.dataOffZ] = props.layout.data.z;
                it[Connections.dataSpaceX] = props.layout.dataSpacing.x;
                it[Connections.dataSpaceY] = props.layout.dataSpacing.y;
                it[Connections.dataSpaceZ] = props.layout.dataSpacing.z;

                it[Connections.addrMaterial] =
                    props.colorScheme.address.getKey().toString();
                it[Connections.addrMSBMaterial] =
                    props.colorScheme.addressMSB.getKey().toString();
                it[Connections.dataMaterial] =
                    props.colorScheme.data.getKey().toString();
                it[Connections.dataMSBMaterial] =
                    props.colorScheme.dataMSB.getKey().toString();

                it[Connections.addressBits] = props.addressBits;
                it[Connections.wordSize] = props.wordSize;
                it[Connections.pageSize] = props.pageSize;
                it[Connections.pageCount] = props.pageCount;
                it[Connections.latency] = props.latency;
                it[Connections.skew] = props.skew;
                it[Connections.dataRate] = props.dataRate;
                it[Connections.filePath] = props.file.toString();
                it[Connections.playerUUID] = playerUUID;
            }
        }
        return uuid;
    }

    fun removeConnection(uuid: UUID) {
        transaction(db) {
            Connections.deleteWhere { Connections.uuid eq uuid };
        }
    }

    fun setConnectionEnabled(uuid: UUID, enabled: Boolean) {
        transaction(db) {
            Connections.update({ Connections.uuid eq uuid }) {
                it[Connections.enabled] = enabled;
            }
        }
    }

    fun setConnectionFile(uuid: UUID, file: String) {
        transaction(db) {
            Connections.update({ Connections.uuid eq uuid }) {
                it[Connections.filePath] = file;
            }
        }
    }

    fun getConnections(cb: (ConnectionMeta, ConnectionProperties) -> Unit) {
        transaction(db) {
            Connections.selectAll().map {
                parseConnection(it)?.let { (meta, props) ->
                    cb(meta, props);
                };
            }
        }
    }

    fun getPlayerConnections(
        playerUUID: UUID,
        cb: (ConnectionMeta, ConnectionProperties) -> Unit,
    ) {
        transaction(db) {
            Connections.select {
                Connections.playerUUID eq playerUUID
            }.map {
                val pair = parseConnection(it);
                if (pair != null) {
                    cb(pair.first, pair.second);
                }
            }
        }
    }

    fun getPlayerConnectionMetas(
        playerUUID: UUID,
        cb: (ConnectionMeta) -> Unit,
    ) {
        transaction(db) {
            Connections.select {
                Connections.playerUUID eq playerUUID
            }.map {
                val meta = ConnectionMeta(
                    uuid = it[Connections.uuid],
                    playerUUID = it[Connections.playerUUID],
                    enabled = it[Connections.enabled],
                );
                cb(meta);
            }
        }
    }

    fun getWorldConnections(
        worldUUID: UUID,
        cb: (ConnectionMeta, ConnectionProperties) -> Unit,
    ) {
        transaction(db) {
            Connections.select {
                Connections.worldUUID eq worldUUID
            }.map {
                val pair = parseConnection(it);
                if (pair != null) {
                    cb(pair.first, pair.second);
                }
            }
        }
    }

    fun close() {
        TransactionManager.closeAndUnregister(db);
    }
}

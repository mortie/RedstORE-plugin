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
import redstore.ConnectionProperties
import redstore.ConnMode

object Connections: Table("connections") {
    val uuid = uuid("uuid").uniqueIndex();
    val mode = char("mode");
    val worldUUID = uuid("world_uuid");
    val originX = integer("origin_x");
    val originY = integer("origin_y");
    val originZ = integer("origin_z");
    val direction = varchar("direction", 2);
    val addressBits = integer("address_bits");
    val wordSize = integer("word_size");
    val pageSize = integer("page_size");
    val pageCount = integer("page_count");
    val latency = integer("latency");
    val filePath = text("file_path");
    val playerUUID = uuid("player_uuid");
    override val primaryKey = PrimaryKey(uuid);
}

data class ConnectionMeta(
    val uuid: UUID,
    val playerUUID: UUID,
)

class RedstOREDatabase(
    dbFile: String,
    val logger: Logger,
) {
    private val db = Database.connect(
        "jdbc:sqlite:${dbFile}", driver = "org.sqlite.JDBC")

    init {
        transaction(db) {
            SchemaUtils.create(Connections);
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
        );

        val direction = when (it[Connections.direction]) {
            "N" -> BlockFace.NORTH;
            "S" -> BlockFace.SOUTH;
            "E" -> BlockFace.EAST;
            "W" -> BlockFace.WEST;
            "U" -> BlockFace.UP;
            "D" -> BlockFace.DOWN;
            else -> null;
        };
        if (direction == null) {
            logger.warning(
                "Connection with unrecognized direction " +
                "${it[Connections.direction]}");
            return null;
        }

        val props = ConnectionProperties(
            mode = mode,
            origin = world.getBlockAt(
                it[Connections.originX],
                it[Connections.originY],
                it[Connections.originZ]),
            direction = direction,
            addressBits = it[Connections.addressBits],
            wordSize = it[Connections.wordSize],
            pageSize = it[Connections.pageSize],
            pageCount = it[Connections.pageCount],
            latency = it[Connections.latency],
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
            )};
        }
    }

    fun addConnection(playerUUID: UUID, props: ConnectionProperties): UUID {
        val uuid = UUID.randomUUID();
        transaction(db) {
            Connections.insert {
                it[Connections.uuid] = uuid;
                it[Connections.mode] = when (props.mode) {
                    ConnMode.READ -> 'R';
                    ConnMode.WRITE -> 'W';
                };
                it[Connections.worldUUID] = props.origin.getWorld().getUID();
                it[Connections.originX] = props.origin.getX();
                it[Connections.originY] = props.origin.getY();
                it[Connections.originZ] = props.origin.getZ();
                it[Connections.direction] = when (props.direction) {
                    BlockFace.NORTH -> "N";
                    BlockFace.SOUTH -> "S";
                    BlockFace.EAST-> "E";
                    BlockFace.WEST-> "W";
                    BlockFace.UP-> "U";
                    BlockFace.DOWN-> "D";
                    else -> "?";
                };
                it[Connections.addressBits] = props.addressBits;
                it[Connections.wordSize] = props.wordSize;
                it[Connections.pageSize] = props.pageSize;
                it[Connections.pageCount] = props.pageCount;
                it[Connections.latency] = props.latency;
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

    fun close() {
        TransactionManager.closeAndUnregister(db);
    }
}

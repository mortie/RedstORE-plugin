package redstore

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import redstore.ConnectionProperties
import redstore.ConnMode

object ConnectionsTable: Table("connections") {
    val uuid = uuid("uuid").uniqueIndex();
    val worldUUID = uuid("world_uuid");
    val originX = integer("origin_x");
    val originY = integer("origin_y");
    val originZ = integer("origin_z");
    val direction = varchar("direction", 2);
    val addressBits = integer("address_bits");
    val wordSize = integer("word_size");
    val pageSize = integer("page_size");
    val mode = char("mode");
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
) {
    private val db = Database.connect(
        "jdbc:sqlite:${dbFile}", driver = "org.sqlite.JDBC")

    init {
        transaction(db) {
            SchemaUtils.create(ConnectionsTable);
        }
    }

    fun getConnectionMetaWithOrigin(block: Block): ConnectionMeta? {
        val worldUUID = block.getWorld().getUID();
        return transaction(db) {
            ConnectionsTable.select {
                (ConnectionsTable.worldUUID eq worldUUID) and
                (ConnectionsTable.originX eq block.getX()) and
                (ConnectionsTable.originY eq block.getY()) and
                (ConnectionsTable.originZ eq block.getZ())
            }.firstOrNull()?.let {ConnectionMeta(
                uuid = it[ConnectionsTable.uuid],
                playerUUID = it[ConnectionsTable.playerUUID],
            )};
        }
    }

    fun addConnection(playerUUID: UUID, props: ConnectionProperties): UUID {
        val uuid = UUID.randomUUID();
        transaction(db) {
            ConnectionsTable.insert {
                it[ConnectionsTable.uuid] = uuid;
                it[ConnectionsTable.worldUUID] = props.origin.getWorld().getUID();
                it[ConnectionsTable.originX] = props.origin.getX();
                it[ConnectionsTable.originY] = props.origin.getY();
                it[ConnectionsTable.originZ] = props.origin.getZ();
                it[ConnectionsTable.direction] = when (props.direction) {
                    BlockFace.NORTH -> "N";
                    BlockFace.SOUTH -> "S";
                    BlockFace.EAST-> "E";
                    BlockFace.WEST-> "W";
                    BlockFace.UP-> "U";
                    BlockFace.DOWN-> "D";
                    else -> "?";
                };
                it[ConnectionsTable.addressBits] = props.addressBits;
                it[ConnectionsTable.wordSize] = props.wordSize;
                it[ConnectionsTable.pageSize] = props.pageSize;
                it[ConnectionsTable.mode] = when (props.mode) {
                    ConnMode.READ -> 'R';
                    ConnMode.WRITE -> 'W';
                };
                it[ConnectionsTable.filePath] = props.file.toString();
                it[ConnectionsTable.playerUUID] = playerUUID;
            }
        }
        return uuid;
    }

    fun close() {
    }
}

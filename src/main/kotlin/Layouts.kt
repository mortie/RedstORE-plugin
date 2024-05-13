package redstore

import org.bukkit.block.BlockFace
import org.bukkit.block.Block

data class BlockOffset(
    val x: Int,
    val y: Int,
    val z: Int,
) {
    fun relativeTo(src: Block): Block {
        return src.getRelative(x, y, z);
    }

    fun mul(num: Int): BlockOffset {
        return BlockOffset(x * num, y * num, z * num);
    }

    fun add(addX: Int, addY: Int, addZ: Int): BlockOffset {
        return BlockOffset(x + addX, y + addY, z + addZ);
    }

    fun add(add: BlockOffset): BlockOffset {
        return BlockOffset(x + add.x, y + add.y, z + add.z);
    }

    fun inv(): BlockOffset {
        return BlockOffset(-x, -y, -z);
    }
}

data class Layout(
    val address: BlockOffset,
    val addressSpacing: BlockOffset,
    val data: BlockOffset,
    val dataSpacing: BlockOffset,
) {
    fun flipAddress(addressBits: Int): Layout {
        return Layout(
            address = address.add(addressSpacing.mul(addressBits - 1)),
            addressSpacing = addressSpacing.inv(),
            data = data,
            dataSpacing = dataSpacing,
        );
    }

    fun flipData(dataBits: Int): Layout {
        return Layout(
            address = address,
            addressSpacing = addressSpacing,
            data = data.add(dataSpacing.mul(dataBits - 1)),
            dataSpacing = dataSpacing.inv(),
        );
    }
}

enum class LayoutDirection {
    NORTH, SOUTH, EAST, WEST;

    override fun toString(): String {
        return when (this) {
            NORTH -> "n";
            SOUTH -> "s";
            EAST -> "e";
            WEST -> "w";
        }
    }

    fun toFace(): BlockFace {
        return when (this) {
            NORTH -> BlockFace.NORTH;
            SOUTH -> BlockFace.SOUTH;
            EAST -> BlockFace.EAST;
            WEST -> BlockFace.WEST;
        }
    }

    fun getSpacing(): BlockOffset {
        return when (this) {
            NORTH -> BlockOffset(0, 0, -1);
            SOUTH -> BlockOffset(0, 0, 1);
            EAST -> BlockOffset(1, 0, 0);
            WEST -> BlockOffset(-1, 0, 0);
        }
    }

    companion object {
        fun fromFace(face: BlockFace): LayoutDirection? {
            return when (face) {
                BlockFace.NORTH -> LayoutDirection.NORTH;
                BlockFace.SOUTH -> LayoutDirection.SOUTH;
                BlockFace.EAST -> LayoutDirection.EAST;
                BlockFace.WEST -> LayoutDirection.WEST;
                else -> null;
            }
        }

        fun fromString(str: String): LayoutDirection? {
            return when (str) {
                "n" -> LayoutDirection.NORTH;
                "s" -> LayoutDirection.SOUTH;
                "e" -> LayoutDirection.EAST;
                "w" -> LayoutDirection.WEST;
                else -> null;
            }
        }
    }
}

typealias LayoutSpec = (
    dir: LayoutDirection,
    addrBits: Int,
    dataBits: Int,
) -> Layout;

private fun lineLayout(
    dir: LayoutDirection,
    addrBits: Int,
    @Suppress("UNUSED_PARAMETER") dataBits: Int,
): Layout {
    val spacing = dir.getSpacing().mul(2);
    return Layout(
        address = spacing,
        addressSpacing = spacing,
        data = spacing.mul(1 + addrBits),
        dataSpacing = spacing,
    );
}

private fun diagLayout(
    dir: LayoutDirection,
    addrBits: Int,
    @Suppress("UNUSED_PARAMETER") dataBits: Int,
): Layout {
    var spacing = dir.getSpacing().add(0, 1, 0).mul(2);
    return Layout(
        address = spacing,
        addressSpacing = spacing,
        data = spacing.mul(1 + addrBits),
        dataSpacing = spacing,
    );
}

private fun diagA1Layout(
    dir: LayoutDirection,
    @Suppress("UNUSED_PARAMETER") addrBits: Int,
    @Suppress("UNUSED_PARAMETER") dataBits: Int,
): Layout {
    var spacing = dir.getSpacing().add(0, 1, 0).mul(2);
    return Layout(
        address = BlockOffset(0, 2, 0),
        addressSpacing = spacing,
        data = dir.getSpacing().mul(2),
        dataSpacing = spacing,
    ).flipAddress(addrBits).flipData(dataBits);
}

private fun diagB1Layout(
    dir: LayoutDirection,
    addrBits: Int,
    dataBits: Int,
): Layout {
    var spacing = dir.getSpacing().add(0, 1, 0).mul(2);
    return Layout(
        address = dir.getSpacing().mul(2),
        addressSpacing = spacing,
        data = BlockOffset(0, 2, 0),
        dataSpacing = spacing,
    ).flipAddress(addrBits).flipData(dataBits);
}

private fun diagA2Layout(
    dir: LayoutDirection,
    addrBits: Int,
    dataBits: Int,
): Layout {
    var spacing = dir.getSpacing().add(0, 1, 0).mul(2);
    return Layout(
        address = BlockOffset(0, 3, 0),
        addressSpacing = spacing,
        data = dir.getSpacing().mul(3),
        dataSpacing = spacing,
    ).flipAddress(addrBits).flipData(dataBits);
}

private fun diagB2Layout(
    dir: LayoutDirection,
    addrBits: Int,
    dataBits: Int,
): Layout {
    var spacing = dir.getSpacing().add(0, 1, 0).mul(2);
    return Layout(
        address = dir.getSpacing().mul(3),
        addressSpacing = spacing,
        data = BlockOffset(0, 3, 0),
        dataSpacing = spacing,
    ).flipAddress(addrBits).flipData(dataBits);
}

private fun towersLayout(
    dir: LayoutDirection,
    addrBits: Int,
    dataBits: Int,
): Layout {
    val spacing = dir.getSpacing().mul(2);
    return Layout(
        address = spacing,
        addressSpacing = BlockOffset(0, 2, 0),
        data = spacing.mul(2),
        dataSpacing = BlockOffset(0, 2, 0),
    ).flipAddress(addrBits).flipData(dataBits);
}

class Layouts {
    private val layouts = HashMap<String, LayoutSpec>();

    init {
        layouts.set("line", ::lineLayout)
        layouts.set("towers", ::towersLayout)
        layouts.set("diag:a1", ::diagA1Layout)
        layouts.set("diag:b1", ::diagB1Layout)
        layouts.set("diag:a2", ::diagA2Layout)
        layouts.set("diag:b2", ::diagB2Layout)
    }

    fun get(name: String): LayoutSpec? {
        return layouts.get(name);
    }

    fun getDefault(): LayoutSpec {
        return ::lineLayout;
    }
}

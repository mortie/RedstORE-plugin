package redstore

import org.bukkit.Material
import org.bukkit.block.Block

data class ColorScheme(
    val addressMSB: Material,
    val address: Material,
    val dataMSB: Material,
    val data: Material,
) {}

class ColorSchemes {
    private val schemes = HashMap<String, ColorScheme>();

    init {
        add("muted",
            "minecraft:pink_concrete", "minecraft:purple_terracotta",
            "minecraft:purple_concrete", "minecraft:blue_terracotta");
        add("wool",
            "minecraft:light_blue_wool", "minecraft:blue_wool",
            "minecraft:orange_wool", "minecraft:brown_wool");
    }

    fun get(name: String): ColorScheme? {
        return schemes.get(name);
    }

    fun getDefault(): ColorScheme {
        return schemes.get("muted")!!;
    }

    private fun add(
        name: String,
        addressMSB: String,
        address: String,
        dataMSB: String,
        data: String,
    ) {
        schemes.set(name, ColorScheme(
            address = Material.matchMaterial(address)!!,
            addressMSB= Material.matchMaterial(addressMSB)!!,
            data = Material.matchMaterial(data)!!,
            dataMSB = Material.matchMaterial(dataMSB)!!,
        ));
    }
}

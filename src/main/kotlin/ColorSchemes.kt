package redstore

import org.bukkit.Material
import org.bukkit.block.Block

data class ColorScheme(
    val address: Material,
    val data: Material,
) {}

class ColorSchemes {
    private val schemes = HashMap<String, ColorScheme>();

    init {
        add("wool", "minecraft:blue_wool", "minecraft:brown_wool");
        add("capo", "minecraft:purple_terracotta", "minecraft:blue_terracotta");
    }

    fun get(name: String): ColorScheme? {
        return schemes.get(name);
    }

    fun getDefault(): ColorScheme {
        return schemes.get("wool")!!;
    }

    private fun add(name: String, address: String, data: String) {
        schemes.set(name, ColorScheme(
            address = Material.matchMaterial(address)!!,
            data = Material.matchMaterial(data)!!,
        ));
    }
}

package app.navelo.shared

import java.util.Locale

object ServerNames {
    const val MAX_LENGTH = 60

    fun clean(value: String): String = value.replace(Regex("[\\p{Cc}\\p{Cf}\\s]+"), " ").trim()

    fun defaultName(deviceName: String?, manufacturer: String?, model: String?): String {
        val device = clean(deviceName.orEmpty())
        if (device.isNotBlank()) return device.take(MAX_LENGTH)
        val phoneModel = clean(model.orEmpty()).takeUnless { it.equals("unknown", true) }.orEmpty()
        val brand = clean(manufacturer.orEmpty()).takeUnless { it.equals("unknown", true) }.orEmpty()
        val hardware = if (phoneModel.startsWith(brand, ignoreCase = true)) phoneModel
            else listOf(brand, phoneModel).filter(String::isNotBlank).joinToString(" ")
        return (hardware.ifBlank { "Navelo phone" }).take(MAX_LENGTH)
    }

    fun labels(servers: List<DiscoveredServer>): Map<String, String> {
        val names = servers.associate { it.serverId to clean(it.displayName).ifBlank { "Navelo server" } }
        val groups = servers.distinctBy { it.serverId }.groupBy { names.getValue(it.serverId).lowercase(Locale.ROOT) }
        return groups.values.flatMap { group ->
            group.map { server ->
                val name = names.getValue(server.serverId)
                val id = server.serverId.replace("-", "")
                val length = (8..id.length).firstOrNull { size ->
                    group.none { it.serverId != server.serverId && it.serverId.replace("-", "").take(size) == id.take(size) }
                } ?: id.length
                server.serverId to if (group.size > 1) "$name · ${id.take(length).uppercase(Locale.ROOT)}" else name
            }
        }.toMap()
    }
}

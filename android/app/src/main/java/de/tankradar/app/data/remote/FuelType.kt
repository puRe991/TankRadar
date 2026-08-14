package de.tankradar.app.data.remote

/**
 * The four fuel types TankRadar tracks.
 *
 * [adacName] is what the ADAC endpoint expects, [key] is the identifier stored in
 * the database. Both match the Python edition so histories stay compatible.
 */
enum class FuelType(val key: String, val adacName: String, val label: String) {
    E5("e5", "Super", "Super E5"),
    E10("e10", "Super E10", "Super E10"),
    E5P("e5p", "Super Plus", "Super Plus"),
    DIESEL("diesel", "Diesel", "Diesel");

    companion object {
        val DEFAULT = E10

        fun fromKey(key: String?): FuelType = entries.firstOrNull { it.key == key } ?: DEFAULT

        fun labelFor(key: String): String = entries.firstOrNull { it.key == key }?.label ?: key.uppercase()
    }
}

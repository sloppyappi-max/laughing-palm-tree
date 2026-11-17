package com.obdscanner.core

/** What can be read from a VIN without an online lookup. */
data class VehicleIdentity(val modelYear: Int?, val make: String?) {
    /** e.g. "2013 Chevrolet", for searching a trouble code online. */
    val label: String? get() = listOfNotNull(modelYear?.toString(), make).joinToString(" ").ifEmpty { null }

    companion object {
        private const val YEAR_CODES = "ABCDEFGHJKLMNPRSTVWXY123456789"

        private val makes = mapOf(
            "1G1" to "Chevrolet", "1GN" to "Chevrolet", "1GC" to "Chevrolet", "2G1" to "Chevrolet",
            "3GN" to "Chevrolet", "3G1" to "Chevrolet", "KL1" to "Chevrolet", "1GT" to "GMC", "1GK" to "GMC",
            "1G6" to "Cadillac", "1GY" to "Cadillac", "5GA" to "Buick", "1G4" to "Buick",
            "1FA" to "Ford", "1FT" to "Ford", "1FM" to "Ford", "1FB" to "Ford", "2FM" to "Ford", "3FA" to "Ford",
            "1LN" to "Lincoln", "1C3" to "Chrysler", "2C3" to "Chrysler", "1C4" to "Jeep/Chrysler", "1J4" to "Jeep",
            "1C6" to "Ram", "3C6" to "Ram", "1B3" to "Dodge", "2B3" to "Dodge",
            "1HG" to "Honda", "2HG" to "Honda", "5FN" to "Honda", "JHM" to "Honda", "5J6" to "Honda", "19X" to "Honda",
            "JH4" to "Acura", "19U" to "Acura",
            "4T1" to "Toyota", "4T3" to "Toyota", "5TD" to "Toyota", "5TF" to "Toyota", "JTD" to "Toyota",
            "JTE" to "Toyota", "JTM" to "Toyota", "2T1" to "Toyota", "JTH" to "Lexus", "2T2" to "Lexus",
            "1N4" to "Nissan", "1N6" to "Nissan", "5N1" to "Nissan", "JN1" to "Nissan", "JN8" to "Nissan",
            "3N1" to "Nissan", "JNK" to "Infiniti",
            "KMH" to "Hyundai", "5NP" to "Hyundai", "KM8" to "Hyundai", "KNA" to "Kia", "KND" to "Kia", "5XY" to "Kia",
            "JF1" to "Subaru", "JF2" to "Subaru", "4S3" to "Subaru", "4S4" to "Subaru",
            "JM1" to "Mazda", "JM3" to "Mazda", "1YV" to "Mazda",
            "WVW" to "Volkswagen", "3VW" to "Volkswagen", "1VW" to "Volkswagen", "WVG" to "Volkswagen",
            "WAU" to "Audi", "WBA" to "BMW", "5UX" to "BMW", "WDD" to "Mercedes-Benz", "4JG" to "Mercedes-Benz",
            "WDC" to "Mercedes-Benz", "YV1" to "Volvo", "5YJ" to "Tesla", "7SA" to "Tesla",
        )

        fun fromVin(vin: String?): VehicleIdentity {
            if (vin == null || vin.length != 17) return VehicleIdentity(null, null)
            val v = vin.uppercase()
            val index = YEAR_CODES.indexOf(v[9])
            val year = if (index < 0) null else {
                var y = 1980 + index
                // The code repeats every 30 years. OBD-II started in 1996, and North American VINs
                // use a letter in position 7 from 2010 on.
                if (y < 1996 || (v[6].isLetter() && y < 2010)) y += 30
                y
            }
            return VehicleIdentity(year, makes[v.substring(0, 3)])
        }
    }
}

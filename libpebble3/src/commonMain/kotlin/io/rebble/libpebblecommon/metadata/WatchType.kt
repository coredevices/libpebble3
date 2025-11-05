package io.rebble.libpebblecommon.metadata

enum class WatchType(val codename: String) {
    APLITE("aplite"),
    BASALT("basalt"),
    CHALK("chalk"),
    DIORITE("diorite"),
    EMERY("emery"),
    FLINT("flint"),
    ;

    fun getCompatibleAppVariants(): List<WatchType> {
        return when (this) {
            APLITE -> listOf(APLITE)
            BASALT -> listOf(BASALT, APLITE)
            CHALK -> listOf(CHALK)
            DIORITE -> listOf(DIORITE, APLITE)
            EMERY -> listOf(
                EMERY,
                BASALT,
                DIORITE,
                APLITE
            )
            FLINT -> listOf(FLINT, DIORITE, APLITE)
        }
    }

    /**
     * Get the most compatible variant for this WatchType
     * @param availableAppVariants List of variants, from [io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo.targetPlatforms]
     */
    fun getBestVariant(availableAppVariants: List<String>): WatchType? {
        val compatibleVariants = getCompatibleAppVariants()

        return compatibleVariants.firstOrNull() { variant ->
            availableAppVariants.contains(variant.codename)
        }
    }

    companion object {
        fun fromCodename(codename: String): WatchType? {
            return entries.firstOrNull { it.codename == codename }
        }
    }
}
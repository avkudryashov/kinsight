package app.kinsight.feature.onboarding

enum class BatteryVendor { SAMSUNG, GOOGLE, XIAOMI, HUAWEI, ONEPLUS, OPPO, VIVO, OTHER }

/** Which manufacturer-specific instruction to show (spec §8: no REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, only guidance). */
object BatteryGuide {
    fun vendor(manufacturer: String): BatteryVendor = when (manufacturer.trim().lowercase()) {
        "samsung" -> BatteryVendor.SAMSUNG
        "google" -> BatteryVendor.GOOGLE
        "xiaomi", "redmi", "poco" -> BatteryVendor.XIAOMI
        "huawei", "honor" -> BatteryVendor.HUAWEI
        "oneplus" -> BatteryVendor.ONEPLUS
        "oppo", "realme" -> BatteryVendor.OPPO
        "vivo", "iqoo" -> BatteryVendor.VIVO
        else -> BatteryVendor.OTHER
    }
}

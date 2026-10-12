package app.kinsight.feature.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals

class BatteryGuideTest {
    @Test
    fun manufacturersMapToVendors() {
        assertEquals(BatteryVendor.SAMSUNG, BatteryGuide.vendor("samsung"))
        assertEquals(BatteryVendor.GOOGLE, BatteryGuide.vendor("Google"))
        assertEquals(BatteryVendor.XIAOMI, BatteryGuide.vendor("Redmi"))
        assertEquals(BatteryVendor.XIAOMI, BatteryGuide.vendor("POCO"))
        assertEquals(BatteryVendor.HUAWEI, BatteryGuide.vendor("HONOR"))
        assertEquals(BatteryVendor.ONEPLUS, BatteryGuide.vendor("OnePlus"))
        assertEquals(BatteryVendor.OPPO, BatteryGuide.vendor("realme"))
        assertEquals(BatteryVendor.VIVO, BatteryGuide.vendor("vivo"))
        assertEquals(BatteryVendor.OTHER, BatteryGuide.vendor("Fairphone"))
    }
}

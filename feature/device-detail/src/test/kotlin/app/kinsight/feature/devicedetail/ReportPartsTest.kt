package app.kinsight.feature.devicedetail

import app.kinsight.core.data.AppCategory.GAMES
import app.kinsight.core.data.AppCategory.MAPS
import app.kinsight.core.data.AppCategory.NEWS
import app.kinsight.core.data.AppCategory.OTHER
import app.kinsight.core.data.AppCategory.PHOTOS
import app.kinsight.core.data.AppCategory.VIDEO
import app.kinsight.core.data.CategoryTotal
import kotlin.test.Test
import kotlin.test.assertEquals

class ReportPartsTest {
    private val m = 60_000L

    @Test
    fun upToFourNamedCategoriesThenTheRestAsOther() {
        val cats = listOf(
            CategoryTotal(GAMES, 50 * m), CategoryTotal(VIDEO, 40 * m), CategoryTotal(NEWS, 30 * m),
            CategoryTotal(PHOTOS, 20 * m), CategoryTotal(MAPS, 10 * m), CategoryTotal(OTHER, 5 * m),
        )
        assertEquals(
            listOf(CategoryTotal(GAMES, 50 * m), CategoryTotal(VIDEO, 40 * m), CategoryTotal(NEWS, 30 * m), CategoryTotal(PHOTOS, 20 * m), CategoryTotal(OTHER, 15 * m)),
            stripParts(cats),
        )
    }

    @Test
    fun fewCategoriesStayAsTheyAre() {
        val cats = listOf(CategoryTotal(VIDEO, 40 * m), CategoryTotal(GAMES, 10 * m))
        assertEquals(cats, stripParts(cats))
    }

    @Test
    fun otherAloneStaysOtherAndNothingGivesNothing() {
        assertEquals(listOf(CategoryTotal(OTHER, 5 * m)), stripParts(listOf(CategoryTotal(OTHER, 5 * m))))
        assertEquals(emptyList(), stripParts(emptyList()))
    }
}

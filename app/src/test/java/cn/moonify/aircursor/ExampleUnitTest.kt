package cn.moonify.aircursor

import cn.moonify.aircursor.ui.TouchpadView
import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun testTwoFingerScrollSensitivityRatio() {
        // 当灵敏度为 1.2f 时，effectiveSens 必须精确等于旧版的 0.1f
        val effectiveSensAt1_2 = 1.2f * TouchpadView.SCROLL_SENS_RATIO
        assertEquals(0.1f, effectiveSensAt1_2, 0.0001f)

        // 当灵敏度为 0.1f 时，effectiveSens 按 1.2 等比缩放 (为 1.2f 的 1/12)
        val effectiveSensAt0_1 = 0.1f * TouchpadView.SCROLL_SENS_RATIO
        assertEquals(0.1f / 12f, effectiveSensAt0_1, 0.0001f)

        // 当灵敏度为 2.4f 时，effectiveSens 为 0.2f
        val effectiveSensAt2_4 = 2.4f * TouchpadView.SCROLL_SENS_RATIO
        assertEquals(0.2f, effectiveSensAt2_4, 0.0001f)
    }
}
package cn.moonify.aircursor

import cn.moonify.aircursor.bluetooth.PinyinConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinyinConverterTest {

    @Test
    fun testPolyphonicWords() {
        val yinhang = PinyinConverter.convertChinesePhraseToPinyin("银行")
        assertEquals(listOf("yin", "hang"), yinhang)

        val xian = PinyinConverter.convertChinesePhraseToPinyin("西安")
        assertEquals(listOf("xi", "an"), xian)

        val chongxin = PinyinConverter.convertChinesePhraseToPinyin("重新")
        assertEquals(listOf("chong", "xin"), chongxin)
    }

    @Test
    fun testKeystrokeActionApostropheInsertion() {
        val actions = PinyinConverter.convertTextToKeystrokeActions("西安")
        assertEquals(1, actions.size)
        assertTrue(actions[0] is PinyinConverter.KeystrokeAction.PinyinSequence)
        val pinyinSeq = (actions[0] as PinyinConverter.KeystrokeAction.PinyinSequence).pinyin
        // xi + an should insert apostrophe: xi'an
        assertEquals("xi'an", pinyinSeq)
    }

    @Test
    fun testKeystrokeActionFangan() {
        val actions = PinyinConverter.convertTextToKeystrokeActions("方案")
        assertEquals(1, actions.size)
        assertTrue(actions[0] is PinyinConverter.KeystrokeAction.PinyinSequence)
        val pinyinSeq = (actions[0] as PinyinConverter.KeystrokeAction.PinyinSequence).pinyin
        // fang + an should insert apostrophe: fang'an
        assertEquals("fang'an", pinyinSeq)
    }

    @Test
    fun testKeystrokeActionDirectChar() {
        val actions = PinyinConverter.convertTextToKeystrokeActions("A1!")
        assertEquals(3, actions.size)
        assertEquals(PinyinConverter.KeystrokeAction.DirectChar('A'), actions[0])
        assertEquals(PinyinConverter.KeystrokeAction.DirectChar('1'), actions[1])
        assertEquals(PinyinConverter.KeystrokeAction.DirectChar('!'), actions[2])
    }

    @Test
    fun testNvLvPinyin() {
        val dictFile = java.io.File("src/main/assets/pinyin_dict.bin")
        if (dictFile.exists()) {
            val bytes = dictFile.readBytes()
            val loadMethod = PinyinConverter::class.java.getDeclaredMethod("loadFromBytes", ByteArray::class.java)
            loadMethod.isAccessible = true
            loadMethod.invoke(PinyinConverter, bytes)
            val fieldInit = PinyinConverter::class.java.getDeclaredField("isInitialized")
            fieldInit.isAccessible = true
            fieldInit.setBoolean(PinyinConverter, true)

            assertEquals("nv", PinyinConverter.getPinyin('女'))
            assertEquals("lv", PinyinConverter.getPinyin('绿'))
            assertEquals("lv", PinyinConverter.getPinyin('旅'))
            assertEquals("lv", PinyinConverter.getPinyin('律'))
            assertEquals("lve", PinyinConverter.getPinyin('略'))
        }
    }
}

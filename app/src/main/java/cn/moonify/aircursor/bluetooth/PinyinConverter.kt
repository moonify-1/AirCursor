package cn.moonify.aircursor.bluetooth

import android.content.Context
import android.util.Log
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/**
 * 离线全量中文字符转拼音与击键序列转换引擎。
 * 基于压缩的 Unicode CJK 统一汉字（0x4E00..0x9FA5）拼音字典（包含 20,892 个汉字与 415 个标准音节），
 * 内存仅占用约 40KB，实现 O(1) 瞬时查询，无需任何网络或外部库。
 */
object PinyinConverter {
    private const val TAG = "PinyinConverter"
    private const val ASSET_NAME = "pinyin_dict.bin"
    private const val UNICODE_START = 0x4E00
    private const val UNICODE_END = 0x9FA5
    private const val TOTAL_CHARS = UNICODE_END - UNICODE_START + 1

    private var syllables: Array<String> = emptyArray()
    private var charIndices: ShortArray = ShortArray(0)

    @Volatile
    var isInitialized = false
        private set

    fun init(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            try {
                context.assets.open(ASSET_NAME).use { input ->
                    loadFromStream(input)
                }
                isInitialized = true
                Log.d(TAG, "PinyinConverter initialized with ${syllables.size} syllables and $TOTAL_CHARS chars")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize PinyinConverter", e)
            }
        }
    }

    private fun loadFromStream(input: InputStream) {
        val numSyllablesBytes = ByteArray(2)
        readFully(input, numSyllablesBytes)
        val numSyllables = ByteBuffer.wrap(numSyllablesBytes).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

        val sylList = ArrayList<String>(numSyllables)
        for (i in 0 until numSyllables) {
            val len = input.read()
            val strBytes = ByteArray(len)
            readFully(input, strBytes)
            sylList.add(String(strBytes, Charsets.US_ASCII))
        }
        syllables = sylList.toTypedArray()

        GZIPInputStream(input).use { gzipIn ->
            val indicesBytes = ByteArray(TOTAL_CHARS * 2)
            readFully(gzipIn, indicesBytes)
            val buffer = ByteBuffer.wrap(indicesBytes).order(ByteOrder.LITTLE_ENDIAN)
            val indices = ShortArray(TOTAL_CHARS)
            for (i in 0 until TOTAL_CHARS) {
                indices[i] = buffer.short
            }
            charIndices = indices
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count < 0) throw IllegalStateException("Unexpected EOF while reading pinyin dictionary")
            offset += count
        }
    }

    fun getPinyin(c: Char): String? {
        val code = c.code
        if (code in UNICODE_START..UNICODE_END && isInitialized && charIndices.isNotEmpty()) {
            val index = (charIndices[code - UNICODE_START].toInt() and 0xFFFF)
            if (index in 1..syllables.size) {
                return syllables[index - 1]
            }
        }
        return null
    }

    /**
     * 将一段文本拆解为可通过 HID 实体键盘发送给平板输入法的击键动作序列。
     * - 中文汉字连续序列：转换为连续拼音（如 "你好" -> "nihao"），并自动追加空格键进行上屏确认
     * - 标点符号与特殊字符：直接映射为对应的键盘按键
     * - 英文字母与数字：直接发送键盘按键
     */
    fun convertTextToKeystrokeActions(text: String): List<KeystrokeAction> {
        val actions = mutableListOf<KeystrokeAction>()
        var currentChinesePinyin = StringBuilder()

        fun flushChinese() {
            if (currentChinesePinyin.isNotEmpty()) {
                actions.add(KeystrokeAction.PinyinSequence(currentChinesePinyin.toString()))
                currentChinesePinyin = StringBuilder()
            }
        }

        for (c in text) {
            val py = getPinyin(c)
            if (py != null) {
                currentChinesePinyin.append(py)
            } else {
                flushChinese()
                actions.add(KeystrokeAction.DirectChar(c))
            }
        }
        flushChinese()

        return actions
    }

    sealed class KeystrokeAction {
        data class PinyinSequence(val pinyin: String) : KeystrokeAction()
        data class DirectChar(val char: Char) : KeystrokeAction()
    }
}

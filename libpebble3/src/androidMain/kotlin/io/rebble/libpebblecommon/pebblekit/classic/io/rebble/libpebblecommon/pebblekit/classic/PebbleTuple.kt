package io.rebble.libpebblecommon.pebblekit.classic

import java.nio.charset.Charset
import java.util.Locale

/**
 * A key-value pair stored in a [PebbleClassicDictionary].
 *
 * This is a copy of the file from the original Pebble SDK, converted to Kotlin
 *
 * @author zulak@getpebble.com
 */
class PebbleTuple private constructor(
    /**
     * The integer key identifying the tuple.
     */
    val key: Int,
    /**
     * The type of value contained in the tuple.
     */
    val type: TupleType,
    /**
     * The 'width' of the tuple's value; This value will always be 'NONE' for non-integer types.
     */
    val width: Width,
    /**
     * The length of the tuple's value in bytes.
     */
    val length: Int,
    /**
     * The value being associated with the tuple's key.
     */
    val value: Any?
) {
    class ValueOverflowException : RuntimeException("Value exceeds tuple capacity")

    enum class Width(val value: Int) {
        NONE(0),
        BYTE(1),
        SHORT(2),
        WORD(4);

        companion object {
            fun fromValue(widthValue: Int): Width {
                for (width in entries) {
                    if (widthValue == width.value) {
                        return width
                    }
                }

                throw IllegalArgumentException("Unknown width value: " + widthValue)
            }
        }
    }

    enum class TupleType(ord: Int) {
        BYTES(0),
        STRING(1),
        UINT(2),
        INT(3);

        val ord: Byte

        init {
            this.ord = ord.toByte()
        }

        val tupleName: String
            get() = name.lowercase(Locale.US)
    }

    companion object {
        private val UTF8: Charset = Charset.forName("UTF-8")

        val TYPE_NAMES: MutableMap<String?, TupleType?> = HashMap<String?, TupleType?>()

        init {
            for (t in TupleType.entries) {
                TYPE_NAMES.put(t.tupleName, t)
            }
        }

        val WIDTH_MAP: MutableMap<Int?, Width?> = HashMap<Int?, Width?>()

        init {
            for (w in Width.entries) {
                WIDTH_MAP.put(w.value, w)
            }
        }

        fun create(
            key: Int, type: TupleType, width: Width, value: Int
        ): PebbleTuple {
            return PebbleTuple(key, type, width, width.value, value)
        }

        fun create(
            key: Int, type: TupleType, width: Width, value: Any
        ): PebbleTuple {
            var length = Int.Companion.MAX_VALUE
            if (width != Width.NONE) {
                length = width.value
            } else if (type == TupleType.BYTES) {
                length = (value as ByteArray).size
            } else if (type == TupleType.STRING) {
                length = (value as String).toByteArray(UTF8).size
            }

            if (length > 0xffff) {
                throw ValueOverflowException()
            }

            return PebbleTuple(key, type, width, length, value)
        }
    }
}

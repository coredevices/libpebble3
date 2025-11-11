package io.rebble.libpebblecommon.structmapper

import io.rebble.libpebblecommon.exceptions.PacketDecodeException
import io.rebble.libpebblecommon.exceptions.PacketEncodeException
import io.rebble.libpebblecommon.util.DataBuffer
import io.rebble.libpebblecommon.util.Endian
import kotlin.uuid.Uuid


/**
 * Represents anything mappable to a struct via a StructMapper
 */
abstract class Mappable(val endianness: Endian = Endian.Unspecified) {
    /**
     * Serializes/packs the mappable to its raw equivalent
     * @return The serialized mappable
     */
    abstract fun toBytes(): UByteArray

    /**
     * Deserializes/unpacks raw data into the mappable
     * This will increment the seek position on the DataBuffer
     * @param bytes the data to read, seek position is incremented
     */
    abstract fun fromBytes(bytes: DataBuffer)

    /**
     * The projected size in bytes of the raw data returned by [toBytes]
     */
    abstract val size: Int
}

interface NumberStructElement {
    val valueNumber: Long
}

/**
 * Represents a property mappable to a struct via a StructMapper
 * @param endianness represents endianness on serialization
 */
open class StructElement<T>(
    private val putType: (DataBuffer, StructElement<T>) -> Unit,
    private val getType: (DataBuffer, StructElement<T>) -> Unit,
    mapper: StructMapper,
    size: Int,
    default: T,
    endianness: Endian = Endian.Unspecified
) : Mappable(endianness) {

    override var size = size
        get() {
            return linkedSize?.valueNumber?.toInt() ?: field
        }
        set(value) {
            field = value
            linkedSize = null
        }
    private var linkedSize: NumberStructElement? = null
        private set

    private val mapIndex = mapper.register(this)
    private var value: T = default

    fun get(): T {
        return value
    }

    fun set(value: T, newSize: Int? = null) {
        if (newSize != null) size = newSize
        this.value = value
    }

    override fun toBytes(): UByteArray {
        if (size < 0) throw PacketDecodeException("Invalid StructElement size: $size")
        else if (size == 0) return ubyteArrayOf()
        val buf = DataBuffer(size)
        buf.setEndian(endianness)
        putType(buf, this)
        return buf.array()
    }

    override fun fromBytes(bytes: DataBuffer) {
        bytes.setEndian(endianness)
        getType(bytes, this)
    }

    /**
     * Link the size of this element to the value of another struct element. Size will
     * automatically match value of the target element.
     */
    fun linkWithSize(numberStructElement: NumberStructElement) {
        linkedSize = numberStructElement
    }

    override fun toString(): String {
        return "StructElement(size=$size, linkedSize=${linkedSize?.valueNumber}, " +
                "value=$value)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as StructElement<*>

        if (value != other.value) return false

        return true
    }

    override fun hashCode(): Int {
        return value?.hashCode() ?: 0
    }
}

class SUByte(mapper: StructMapper, default: UByte = 0u) :
    StructElement<UByte>(
        { buf, el -> buf.putUByte(el.get()) },
        { buf, el -> el.set(buf.getUByte()) },
        mapper,
        UByte.SIZE_BYTES,
        default
    ), NumberStructElement {
    override val valueNumber: Long
        get() = get().toLong()
}

class SByte(mapper: StructMapper, default: Byte = 0) :
    StructElement<Byte>(
        { buf, el -> buf.putByte(el.get()) },
        { buf, el -> el.set(buf.getByte()) },
        mapper,
        Byte.SIZE_BYTES,
        default
    ), NumberStructElement {
    override val valueNumber: Long
        get() = get().toLong()
}

class SUInt(mapper: StructMapper, default: UInt = 0u, endianness: Endian = Endian.Unspecified) :
    StructElement<UInt>(
        { buf, el -> buf.putUInt(el.get()) },
        { buf, el -> el.set(buf.getUInt()) },
        mapper,
        UInt.SIZE_BYTES,
        default,
        endianness
    ), NumberStructElement {
    override val valueNumber: Long
        get() = get().toLong()
}

class SInt(mapper: StructMapper, default: Int = 0, endianness: Endian = Endian.Unspecified) :
    StructElement<Int>(
        { buf, el -> buf.putInt(el.get()) },
        { buf, el -> el.set(buf.getInt()) },
        mapper,
        Int.SIZE_BYTES,
        default,
        endianness
    ), NumberStructElement {
    override val valueNumber: Long
        get() = get().toLong()
}

class SULong(mapper: StructMapper, default: ULong = 0u) :
    StructElement<ULong>(
        { buf, el -> buf.putULong(el.get()) },
        { buf, el -> el.set(buf.getULong()) },
        mapper,
        ULong.SIZE_BYTES,
        default
    ), NumberStructElement {
    override val valueNumber: Long
        get() = get().toLong()
}

class SUShort(mapper: StructMapper, default: UShort = 0u, endianness: Endian = Endian.Unspecified) :
    StructElement<UShort>(
        { buf, el -> buf.putUShort(el.get()) },
        { buf, el -> el.set(buf.getUShort()) },
        mapper,
        UShort.SIZE_BYTES,
        default,
        endianness
    ), NumberStructElement {
    override val valueNumber: Long
        get() = get().toLong()
}

class SShort(mapper: StructMapper, default: Short = 0, endianness: Endian = Endian.Unspecified) :
    StructElement<Short>(
        { buf, el -> buf.putShort(el.get()) },
        { buf, el -> el.set(buf.getShort()) },
        mapper,
        Short.SIZE_BYTES,
        default,
        endianness = endianness
    ), NumberStructElement {
    override val valueNumber: Long
        get() = get().toLong()
}

class SBoolean(mapper: StructMapper, default: Boolean = false) :
    StructElement<Boolean>(
        { buf, el -> buf.putUByte(if (el.get()) 1u else 0u) },
        { buf, el -> el.set(buf.getUByte() != 0u.toUByte()) },
        mapper,
        UByte.SIZE_BYTES,
        default
    )

class SUUID(mapper: StructMapper, default: Uuid = Uuid.NIL) :
    StructElement<Uuid>(
        { buf, el -> buf.putBytes(el.get().toByteArray().asUByteArray()) },
        { buf, el -> el.set(Uuid.fromByteArray(buf.getBytes(2 * ULong.SIZE_BYTES).toByteArray())) },
        mapper,
        2 * ULong.SIZE_BYTES,
        default
    )

/**
 * Represents a string (UTF-8) in a struct, includes framing for length
 */
class SString(mapper: StructMapper, default: String = "") :
    StructElement<String>(
        { buf, el ->
            val bytes = el.get().encodeToByteArray()
            buf.putUByte(bytes.size.toUByte())
            buf.putBytes(
                bytes.toUByteArray()
            )
        },
        { buf, el ->
            val len = buf.getUByte().toInt()
            el.set(buf.getBytes(len).toByteArray().decodeToString(), len)
        }, mapper, default.encodeToByteArray().size + 1, default
    )

/**
 * Represents a string (UTF-8) in a struct with a fixed length
 */
class SFixedString(mapper: StructMapper, initialSize: Int, default: String = "") :
    StructElement<String>(
        { buf, el ->
            var bytes = el.get().encodeToByteArray()
            if (bytes.size > el.size) {
                bytes = bytes.take(el.size).toByteArray()
            }

            buf.putBytes(
                bytes.toUByteArray()
            )

            val amountPad = el.size - bytes.size
            repeat(amountPad) {
                buf.putUByte(0u)
            }
        },
        { buf, el ->
            el.set(
                buf.getBytes(el.size).toByteArray().takeWhile { it > 0 }.toByteArray()
                    .decodeToString(), el.size
            )
        }, mapper, initialSize, default
    )

/**
 * Upload-only type that writes String as unbound null-terminated byte array.
 */
class SNullTerminatedString(mapper: StructMapper, default: String = "") :
    StructElement<String>(
        putType = { buf, el ->
            val bytes = el.get().encodeToByteArray()

            buf.putBytes(
                bytes.toUByteArray()
            )
            buf.putUByte(0u)
        },
        getType = { buf, el ->
            buf.array().asByteArray().decodeToString().trimEnd('\u0000')
        }, mapper = mapper, size = default.length + 1, default = default,
    )

/**
 * Represents arbitrary bytes in a struct
 * @param length the number of bytes, when serializing this is used to pad/truncate the provided value to ensure it's 'length' bytes long (-1 to disable this)
 */
class SBytes(
    mapper: StructMapper,
    length: Int = -1,
    default: UByteArray = ubyteArrayOf(),
    endianness: Endian = Endian.Unspecified,
    allRemainingBytes: Boolean = false,
) :
    StructElement<UByteArray>(
        { buf, el ->
            if (el.size != 0) {
                var mValue = el.get()
                if (el.size != -1 && mValue.size > el.size) {
                    mValue = el.get().sliceArray(0 until length - 1) // Truncate if too long
                } else if (el.size != -1 && mValue.size < length) {
                    mValue += UByteArray(length - el.size)// Pad if too short
                }
                buf.putBytes(if (el.endianness == Endian.Little) mValue.reversedArray() else mValue)
            }
        },
        { buf, el ->
            val numBytes = when {
                allRemainingBytes -> buf.remaining
                else -> el.size
            }
            val value = buf.getBytes(numBytes)
            el.set(if (el.endianness == Endian.Little) value.reversedArray() else value)
        },
        mapper, length, default, endianness
    ) {
    override fun toString(): String {
        return "SBytes(value=${get().contentToString()}, size=$size)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SBytes) return false
        if (!this.get().contentEquals(other.get())) return false
        return true
    }

    override fun hashCode(): Int {
        return this.get().hashCode()
    }
}

/**
 * Byte array without bounded size. It reads until whole packet buffer is read.
 *
 * This must be declared as last object
 *
 * Only for reading from watch.
 */
class SUnboundBytes(
    mapper: StructMapper,
    endianness: Endian = Endian.Unspecified
) : StructElement<UByteArray>(
    { buf, el ->
        throw UnsupportedOperationException("SUnboundBytes is read-only")
    },
    { buf, el ->
        val leftBytes = buf.length - buf.readPosition
        val value = buf.getBytes(leftBytes)
        el.set(if (el.endianness == Endian.Little) value.reversedArray() else value)
    },
    mapper, 0, ubyteArrayOf(), endianness
) {

    override fun toString(): String {
        return "SUnboundBytes(value=${get().contentToString()})"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SUnboundBytes) return false
        if (get() != other.get()) return false
        return true
    }

    override fun hashCode(): Int {
        return get().hashCode()
    }
}

/**
 * Represents a fixed size list of T
 * @param T the type (must inherit Mappable)
 */
class SFixedList<T : Mappable>(
    mapper: StructMapper,
    count: Int,
    default: List<T> = emptyList(),
    private val itemFactory: () -> T
) :
    Mappable(Endian.Unspecified), List<T> {

    override fun contains(element: T): Boolean = list.contains(element)
    override fun containsAll(elements: Collection<T>): Boolean = list.containsAll(elements)
    override fun isEmpty(): Boolean = list.isEmpty()
    override fun iterator(): Iterator<T> = list.iterator()
    override fun indexOf(element: T): Int = list.indexOf(element)
    override fun lastIndexOf(element: T): Int = list.lastIndexOf(element)
    override fun listIterator(): ListIterator<T> = list.listIterator()
    override fun listIterator(index: Int): ListIterator<T> = list.listIterator(index)
    override fun subList(fromIndex: Int, toIndex: Int): List<T> = list.subList(fromIndex, toIndex)
    override fun get(index: Int): T = list[index]

    var count = count
        set(value) {
            field = value
            linkedCount = null
        }

    var linkedCount: NumberStructElement? = null
        private set


    private val mapIndex = mapper.register(this)
    var list = default

    init {
        if (count != default.size) throw PacketEncodeException("Fixed list count does not match default value count")
    }

    override fun toBytes(): UByteArray {
        val bytes: MutableList<UByte> = mutableListOf()
        list.forEach {
            bytes += it.toBytes()
        }
        return bytes.toUByteArray()
    }

    override fun fromBytes(bytes: DataBuffer) {
        val count = linkedCount?.valueNumber?.toInt() ?: count
        list = List(count) {
            itemFactory().apply { fromBytes(bytes) }
        }
    }

    override val size: Int
        get() = list.fold(0, { t, el -> t + el.size })

    /**
     * Link the count of this element to the value of another struct element. Count will
     * automatically match value of the target element.
     */
    fun linkWithCount(numberStructElement: NumberStructElement) {
        linkedCount = numberStructElement
    }

    override fun toString(): String {
        return "SFixedList(list=$list)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SFixedList<*>) return false

        if (list != other.list) return false

        return true
    }

    override fun hashCode(): Int {
        return list.hashCode()
    }
}

class SOptional<T>(
    mapper: StructMapper,
    val value: StructElement<T>,
    var present: Boolean,
    endianness: Endian = Endian.Unspecified
) : Mappable(endianness) {
    init {
        mapper.register(this)
    }

    override fun toBytes(): UByteArray {
        return if (present) value.toBytes() else UByteArray(0)
    }

    override fun fromBytes(bytes: DataBuffer) {
        val leftBytes = bytes.length - bytes.readPosition
        if (leftBytes < value.size) {
            present = false
        } else {
            present = true
            value.fromBytes(bytes)
        }
    }

    override val size: Int
        get() = if (present) value.size else 0

    fun get(): T? {
        return if (present) value.get() else null
    }

    fun set(value: T?) {
        if (value != null) {
            present = true
            this.value.set(value)
        } else {
            present = false
        }
    }
}
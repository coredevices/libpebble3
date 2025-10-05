package io.rebble.libpebblecommon.packets

import io.rebble.libpebblecommon.protocolhelpers.PacketRegistry
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import io.rebble.libpebblecommon.protocolhelpers.ProtocolEndpoint
import io.rebble.libpebblecommon.structmapper.SByte
import io.rebble.libpebblecommon.structmapper.SBytes
import io.rebble.libpebblecommon.structmapper.SFixedList
import io.rebble.libpebblecommon.structmapper.SInt
import io.rebble.libpebblecommon.structmapper.SShort
import io.rebble.libpebblecommon.structmapper.SUByte
import io.rebble.libpebblecommon.structmapper.SUInt
import io.rebble.libpebblecommon.structmapper.SUShort
import io.rebble.libpebblecommon.structmapper.SUUID
import io.rebble.libpebblecommon.structmapper.StructMappable
import io.rebble.libpebblecommon.structmapper.StructMapper
import io.rebble.libpebblecommon.util.DataBuffer
import io.rebble.libpebblecommon.util.Endian
import kotlin.uuid.Uuid


class AppMessageTuple() : StructMappable() {
    enum class Type(val value: UByte) {
        ByteArray(0u),
        CString(1u),
        UInt(2u),
        Int(3u);

        companion object {
            fun fromValue(value: UByte): Type {
                return entries.firstOrNull { it.value == value } ?: error("Unknown type: $value")
            }
        }
    }

    val key = SUInt(m, endianness = Endian.Little)
    val type = SUByte(m)
    val dataLength = SUShort(m, endianness = Endian.Little)
    val data = SBytes(m, 0)

    init {
        data.linkWithSize(dataLength)
    }

    fun getTypedData(): Any {
        return when (Type.fromValue(type.get())) {
            Type.ByteArray -> dataAsBytes
            Type.CString -> dataAsString
            Type.UInt -> dataAsUnsignedNumber
            Type.Int -> dataAsSignedNumber
        }
    }

    val dataAsBytes: UByteArray
        get() {
            return data.get()
        }

    val dataAsString: String
        get() {
            val dataWithoutNull = data.get().dropLast(1)
            return dataWithoutNull.toUByteArray().toByteArray().decodeToString()
        }

    val dataAsSignedNumber: Long
        get() {
            val obj = when (val size = dataLength.get().toInt()) {
                1 -> SByte(StructMapper())
                2 -> SShort(StructMapper(), endianness = Endian.Little)
                4 -> SInt(StructMapper(), endianness = Endian.Little)
                else -> error("Size not supported: $size")
            }
            return obj.apply {
                fromBytes(DataBuffer(data.get()))
            }.valueNumber
        }

    val dataAsUnsignedNumber: ULong
        get() {
            val obj = when (val size = dataLength.get().toInt()) {
                1 -> SUByte(StructMapper())
                2 -> SUShort(StructMapper(), endianness = Endian.Little)
                4 -> SUInt(StructMapper(), endianness = Endian.Little)
                else -> error("Size not supported: $size")
            }
            return obj.apply {
                fromBytes(DataBuffer(data.get()))
            }.valueNumber.toULong()
        }

    override fun toString(): String {
        return "AppMessageTuple(key=$key, type=$type, dataLength=$dataLength, data=$data)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as AppMessageTuple

        if (key != other.key) return false
        if (type != other.type) return false
        if (dataLength != other.dataLength) return false
        if (data != other.data) return false

        return true
    }

    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + dataLength.hashCode()
        result = 31 * result + data.hashCode()
        return result
    }

    companion object {
        fun createUByteArray(
            key: UInt,
            data: UByteArray
        ): AppMessageTuple = AppMessageTuple().apply {
            this.key.set(key)
            this.type.set(Type.ByteArray.value)
            this.dataLength.set(data.size.toUShort())
            this.data.set(data)
        }

        fun createString(
            key: UInt,
            data: String
        ): AppMessageTuple = AppMessageTuple().apply {
            this.key.set(key)
            this.type.set(Type.CString.value)

            val bytes = (data.encodeToByteArray() + byteArrayOf(0x00)).toUByteArray()
            this.dataLength.set(bytes.size.toUShort())
            this.data.set(bytes)
        }

        fun createByte(
            key: UInt,
            data: Byte
        ): AppMessageTuple = AppMessageTuple().apply {
            this.key.set(key)
            this.type.set(Type.Int.value)

            this.dataLength.set(1u)
            this.data.set(ubyteArrayOf(data.toUByte()))
        }

        fun createUByte(
            key: UInt,
            data: UByte
        ): AppMessageTuple = AppMessageTuple().apply {
            this.key.set(key)
            this.type.set(Type.UInt.value)

            this.dataLength.set(1u)
            this.data.set(ubyteArrayOf(data))
        }

        fun createShort(
            key: UInt,
            data: Short
        ): AppMessageTuple = AppMessageTuple().apply {
            this.key.set(key)
            this.type.set(Type.Int.value)

            val bytes = SShort(StructMapper(), data, endianness = Endian.Little).toBytes()
            this.dataLength.set(bytes.size.toUShort())
            this.data.set(bytes)
        }

        fun createUShort(
            key: UInt,
            data: UShort
        ): AppMessageTuple = AppMessageTuple().apply {
            this.key.set(key)
            this.type.set(Type.UInt.value)

            val bytes = SUShort(StructMapper(), data, endianness = Endian.Little).toBytes()
            this.dataLength.set(bytes.size.toUShort())
            this.data.set(bytes)
        }

        fun createInt(
            key: UInt,
            data: Int
        ): AppMessageTuple = AppMessageTuple().apply {
            this.key.set(key)
            this.type.set(Type.Int.value)

            val bytes = SInt(StructMapper(), data, endianness = Endian.Little).toBytes()
            this.dataLength.set(bytes.size.toUShort())
            this.data.set(bytes)
        }

        fun createUInt(
            key: UInt,
            data: UInt
        ): AppMessageTuple = AppMessageTuple().apply {
            this.key.set(key)
            this.type.set(Type.UInt.value)

            val bytes = SUInt(StructMapper(), data, endianness = Endian.Little).toBytes()
            this.dataLength.set(bytes.size.toUShort())
            this.data.set(bytes)
        }
    }
}

sealed class AppMessage(message: Message, transactionId: UByte) : PebblePacket(endpoint) {
    val command = SUByte(m, message.value)
    val transactionId = SUByte(m, transactionId)

    init {
        type = command.get()
    }

    enum class Message(val value: UByte) {
        AppMessagePush(0x01u),
        AppMessageACK(0xffu),
        AppMessageNACK(0x7fu)
    }

    class AppMessagePush(
        transactionId: UByte = 0u,
        uuid: Uuid = Uuid.NIL,
        tuples: List<AppMessageTuple> = emptyList()
    ) :
        AppMessage(Message.AppMessagePush, transactionId) {
        val uuid = SUUID(m, uuid)
        val count = SUByte(m, tuples.size.toUByte())
        val dictionary = SFixedList(m, tuples.size, tuples, ::AppMessageTuple)

        init {
            dictionary.linkWithCount(count)
        }

        override fun toString(): String {
            return "AppMessagePush(uuid=$uuid, count=$count, dictionary=$dictionary)"
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            if (!super.equals(other)) return false

            other as AppMessagePush

            if (uuid != other.uuid) return false
            if (count != other.count) return false
            if (dictionary != other.dictionary) return false

            return true
        }

        override fun hashCode(): Int {
            var result = super.hashCode()
            result = 31 * result + uuid.hashCode()
            result = 31 * result + count.hashCode()
            result = 31 * result + dictionary.hashCode()
            return result
        }
    }

    class AppMessageACK(
        transactionId: UByte = 0u
    ) : AppMessage(Message.AppMessageACK, transactionId)

    class AppMessageNACK(
        transactionId: UByte = 0u
    ) : AppMessage(Message.AppMessageNACK, transactionId)


    companion object {
        val endpoint = ProtocolEndpoint.APP_MESSAGE
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as AppMessage

        if (command != other.command) return false
        if (transactionId != other.transactionId) return false

        return true
    }

    override fun hashCode(): Int {
        var result = command.hashCode()
        result = 31 * result + transactionId.hashCode()
        return result
    }
}

fun appmessagePacketsRegister() {
    PacketRegistry.register(
        AppMessage.endpoint,
        AppMessage.Message.AppMessagePush.value
    ) { AppMessage.AppMessagePush() }

    PacketRegistry.register(
        AppMessage.endpoint,
        AppMessage.Message.AppMessageACK.value
    ) { AppMessage.AppMessageACK() }

    PacketRegistry.register(
        AppMessage.endpoint,
        AppMessage.Message.AppMessageNACK.value
    ) { AppMessage.AppMessageNACK() }
}

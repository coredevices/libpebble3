package io.rebble.libpebblecommon.packets

import assertIs
import assertUByteArrayEquals
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

internal class AppMessageTest {
    @Test
    fun serializeDeserializePushMessage() {
        val testPushMessage = AppMessage.AppMessagePush(
            5u,
            Uuid.parse("30880933-cead-49f6-ba94-3a6f8cd3218a"),
            listOf(
                AppMessageTuple.createUByteArray(77u, ubyteArrayOf(1u, 170u, 245u)),
                AppMessageTuple.createString(6710u, "Hello World"),
                AppMessageTuple.createString(7710u, "Emoji: \uD83D\uDC7D."),
                AppMessageTuple.createByte(38485u, -7),
                AppMessageTuple.createUByte(2130680u, 177u.toUByte()),
                AppMessageTuple.createShort(2845647u, -20),
                AppMessageTuple.createUShort(2845648u, 49885u.toUShort()),
                AppMessageTuple.createInt(2845649u, -707573),
                AppMessageTuple.createUInt(2845650u, 2448461u)
            )
        )

        val bytes = testPushMessage.serialize()
        val newMessage = PebblePacket.deserialize(bytes)
        assertIs<AppMessage.AppMessagePush>(newMessage)

        val list = newMessage.dictionary.list

        assertEquals(testPushMessage.dictionary.list.size, list.size)

        assertUByteArrayEquals(ubyteArrayOf(1u, 170u, 245u), list[0].dataAsBytes)
        assertEquals("Hello World", list[1].dataAsString)
        assertEquals("Emoji: \uD83D\uDC7D.", list[2].dataAsString)
        assertEquals(-7, list[3].dataAsSignedNumber)
        assertEquals(177u, list[4].dataAsUnsignedNumber)
        assertEquals(-20, list[5].dataAsSignedNumber)
        assertEquals(49885u, list[6].dataAsUnsignedNumber)
        assertEquals(-707573, list[7].dataAsSignedNumber)
        assertEquals(2448461u, list[8].dataAsUnsignedNumber)

        assertEquals(testPushMessage, newMessage)
    }

    @Test
    fun serializeDeserializeAckMessage() {
        val testPushMessage = AppMessage.AppMessageACK(
            74u
        )

        val bytes = testPushMessage.serialize()
        val newMessage = PebblePacket.deserialize(bytes)
        assertIs<AppMessage.AppMessageACK>(newMessage)

        assertEquals(74u, newMessage.transactionId.get())
    }

    @Test
    fun serializeDeserializeNackMessage() {
        val testPushMessage = AppMessage.AppMessageNACK(
            244u
        )

        val bytes = testPushMessage.serialize()
        val newMessage = PebblePacket.deserialize(bytes)
        assertIs<AppMessage.AppMessageNACK>(newMessage)

        assertEquals(244u, newMessage.transactionId.get())
    }

    @Test
    fun appMessageShortShouldBeLittleEndian() {
        val testPushMessage = AppMessage.AppMessagePush(
            0u,
            Uuid.NIL,
            listOf(
                AppMessageTuple.createShort(0u, -50),
            )
        )

        val expectedMessage = ubyteArrayOf(
            1u, 0u, 0u, 0u,
            0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u,
            0u, 0u, 0u, 0u,
            0u, 1u,
            0u, 0u, 0u, 0u,
            3u,
            2u, 0u,
            206u, 255u
        )

        assertUByteArrayEquals(
            expectedMessage,
            testPushMessage.m.toBytes()
        )
    }

    @Test
    fun appMessageUShortShouldBeLittleEndian() {
        val testPushMessage = AppMessage.AppMessagePush(
            0u,
            Uuid.NIL,
            listOf(
                AppMessageTuple.createUShort(0u, 4876u),
            )
        )

        val expectedMessage = ubyteArrayOf(
            1u, 0u, 0u, 0u,
            0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u,
            0u, 0u, 0u, 0u,
            0u, 1u,
            0u, 0u, 0u, 0u,
            2u,
            2u, 0u,
            12u, 19u
        )

        assertUByteArrayEquals(
            expectedMessage,
            testPushMessage.m.toBytes()
        )
    }

    @Test
    fun appMessageIntShouldBeLittleEndian() {
        val testPushMessage = AppMessage.AppMessagePush(
            0u,
            Uuid.NIL,
            listOf(
                AppMessageTuple.createInt(0u, -90000),
            )
        )

        val expectedMessage = ubyteArrayOf(
            1u, 0u, 0u, 0u,
            0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u,
            0u, 0u, 0u, 0u,
            0u, 1u,
            0u, 0u, 0u, 0u,
            3u,
            4u, 0u,
            112u, 160u, 254u, 255u
        )

        assertUByteArrayEquals(
            expectedMessage,
            testPushMessage.m.toBytes()
        )
    }

    @Test
    fun appMessageUIntShouldBeLittleEndian() {
        val testPushMessage = AppMessage.AppMessagePush(
            0u,
            Uuid.NIL,
            listOf(
                AppMessageTuple.createUInt(0u, 900000u),
            )
        )

        val expectedMessage = ubyteArrayOf(
            1u, 0u, 0u, 0u,
            0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u,
            0u, 0u, 0u, 0u,
            0u, 1u,
            0u, 0u, 0u, 0u,
            2u,
            4u, 0u,
            160u, 187u, 13u, 0u
        )

        assertUByteArrayEquals(
            expectedMessage,
            testPushMessage.m.toBytes()
        )
    }

    @Test
    fun appMessageStringShouldBeBigEndianAndTerminatedWithZero() {
        val testPushMessage = AppMessage.AppMessagePush(
            0u,
            Uuid.NIL,
            listOf(
                AppMessageTuple.createString(0u, "Hello"),
            )
        )

        val expectedMessage = ubyteArrayOf(
            1u, 0u, 0u, 0u,
            0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u,
            0u, 0u, 0u, 0u,
            0u, 1u,
            0u, 0u, 0u, 0u,
            1u,
            6u, 0u,
            'H'.code.toUByte(),
            'e'.code.toUByte(),
            'l'.code.toUByte(),
            'l'.code.toUByte(),
            'o'.code.toUByte(),
            0u
        )

        assertUByteArrayEquals(
            expectedMessage,
            testPushMessage.m.toBytes()
        )
    }

    @Test
    fun appMessageBytesShouldBeBigEndian() {
        val testPushMessage = AppMessage.AppMessagePush(
            0u,
            Uuid.NIL,
            listOf(
                AppMessageTuple.createUByteArray(0u, ubyteArrayOf(1u, 2u, 3u)),
            )
        )

        val expectedMessage = ubyteArrayOf(
            1u, 0u, 0u, 0u,
            0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u,
            0u, 0u, 0u, 0u,
            0u, 1u,
            0u, 0u, 0u, 0u,
            0u,
            3u, 0u,
            1u, 2u, 3u
        )

        assertUByteArrayEquals(
            expectedMessage,
            testPushMessage.m.toBytes()
        )
    }
}
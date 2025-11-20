package io.rebble.libpebblecommon.pebblekit.classic

import android.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * A collection of key-value pairs of heterogeneous types. PebbleDictionaries are the primary structure used to exchange
 * data between the phone and watch.
 *
 *
 * To accommodate the mixed-types contained within a PebbleDictionary, an internal JSON representation is used when
 * exchanging the dictionary between Android processes.
 *
 * This is a copy of the file from the original Pebble SDK, converted to Kotlin
 *
 * @author zulak@getpebble.com
 */
class PebbleClassicDictionary : Iterable<PebbleTuple?> {
    val tuples: MutableMap<Int, PebbleTuple> = HashMap<Int, PebbleTuple>()

    /**
     * {@inheritDoc}
     */
    override fun iterator(): MutableIterator<PebbleTuple?> {
        return tuples.values.iterator()
    }

    /**
     * Returns the number of key-value pairs in this dictionary.
     *
     * @return the number of key-value pairs in this dictionary
     */
    fun size(): Int {
        return tuples.size
    }

    /**
     * Returns true if this dictionary contains a mapping for the specified key.
     *
     * @param key key whose presence in this dictionary is to be tested
     * @return true if this dictionary contains a mapping for the specified key
     */
    fun contains(key: Int): Boolean {
        return tuples.containsKey(key)
    }

    /**
     * Removes the mapping for a key from this map if it is present.
     *
     * @param key key to be removed from the dictionary
     */
    fun remove(key: Int) {
        tuples.remove(key)
    }

    /**
     * Associate the specified byte array with the provided key in the dictionary. If another key-value pair with the
     * same key is already present in the dictionary, it will be replaced.
     *
     * @param key   key with which the specified value is associated
     * @param bytes value to be associated with the specified key
     */
    fun addBytes(key: Int, bytes: ByteArray) {
        val t = PebbleTuple.create(key, PebbleTuple.TupleType.BYTES, PebbleTuple.Width.NONE, bytes)
        addTuple(t)
    }

    /**
     * Associate the specified String with the provided key in the dictionary. If another key-value pair with the same
     * key is already present in the dictionary, it will be replaced.
     *
     * @param key   key with which the specified value is associated
     * @param value value to be associated with the specified key
     */
    fun addString(key: Int, value: String) {
        val t =
            PebbleTuple.create(key, PebbleTuple.TupleType.STRING, PebbleTuple.Width.NONE, value)
        addTuple(t)
    }

    /**
     * Associate the specified signed byte with the provided key in the dictionary. If another key-value pair with the
     * same key is already present in the dictionary, it will be replaced.
     *
     * @param key key with which the specified value is associated
     * @param b   value to be associated with the specified key
     */
    fun addInt8(key: Int, b: Byte) {
        val t = PebbleTuple.create(key, PebbleTuple.TupleType.INT, PebbleTuple.Width.BYTE, b.toInt())
        addTuple(t)
    }

    /**
     * Associate the specified unsigned byte with the provided key in the dictionary. If another key-value pair with the
     * same key is already present in the dictionary, it will be replaced.
     *
     * @param key key with which the specified value is associated
     * @param b   value to be associated with the specified key
     */
    fun addUint8(key: Int, b: Byte) {
        val t = PebbleTuple.create(key, PebbleTuple.TupleType.UINT, PebbleTuple.Width.BYTE, b.toInt())
        addTuple(t)
    }

    /**
     * Associate the specified signed short with the provided key in the dictionary. If another key-value pair with the
     * same key is already present in the dictionary, it will be replaced.
     *
     * @param key key with which the specified value is associated
     * @param s   value to be associated with the specified key
     */
    fun addInt16(key: Int, s: Short) {
        val t = PebbleTuple.create(key, PebbleTuple.TupleType.INT, PebbleTuple.Width.SHORT, s.toInt())
        addTuple(t)
    }

    /**
     * Associate the specified unsigned short with the provided key in the dictionary. If another key-value pair with
     * the same key is already present in the dictionary, it will be replaced.
     *
     * @param key key with which the specified value is associated
     * @param s   value to be associated with the specified key
     */
    fun addUint16(key: Int, s: Short) {
        val t = PebbleTuple.create(key, PebbleTuple.TupleType.UINT, PebbleTuple.Width.SHORT, s.toInt())
        addTuple(t)
    }

    /**
     * Associate the specified signed int with the provided key in the dictionary. If another key-value pair with the
     * same key is already present in the dictionary, it will be replaced.
     *
     * @param key key with which the specified value is associated
     * @param i   value to be associated with the specified key
     */
    fun addInt32(key: Int, i: Int) {
        val t = PebbleTuple.create(key, PebbleTuple.TupleType.INT, PebbleTuple.Width.WORD, i)
        addTuple(t)
    }

    /**
     * Associate the specified unsigned int with the provided key in the dictionary. If another key-value pair with the
     * same key is already present in the dictionary, it will be replaced.
     *
     * @param key key with which the specified value is associated
     * @param i   value to be associated with the specified key
     */
    fun addUint32(key: Int, i: Int) {
        val t = PebbleTuple.create(key, PebbleTuple.TupleType.UINT, PebbleTuple.Width.WORD, i)
        addTuple(t)
    }

    private fun getTuple(key: Int, type: PebbleTuple.TupleType): PebbleTuple? {
        if (!tuples.containsKey(key) || tuples.get(key) == null) {
            return null
        }

        val t = tuples.getValue(key)
        if (t.type != type) {
            throw PebbleDictTypeException(key.toLong(), type, t.type)
        }
        return t
    }

    /**
     * Returns the signed integer to which the specified key is mapped, or null if the key does not exist in this
     * dictionary.
     *
     * @param key key whose associated value is to be returned
     * @return value to which the specified key is mapped
     */
    fun getInteger(key: Int): Long? {
        val tuple = getTuple(key, PebbleTuple.TupleType.INT)
        if (tuple == null) {
            return null
        }
        return tuple.value as Long?
    }

    /**
     * Returns the unsigned integer as a long to which the specified key is mapped, or null if the key does not exist in this
     * dictionary. We are using the Long type here so that we can remove the guava dependency. This is done so that we dont
     * have incompatibility issues with the UnsignedInteger class from the Holo application, which uses a newer version of Guava.
     *
     * @param key key whose associated value is to be returned
     * @return value to which the specified key is mapped
     */
    fun getUnsignedIntegerAsLong(key: Int): Long? {
        val tuple = getTuple(key, PebbleTuple.TupleType.UINT)
        if (tuple == null) {
            return null
        }
        return tuple.value as Long?
    }

    /**
     * Returns the byte array to which the specified key is mapped, or null if the key does not exist in this
     * dictionary.
     *
     * @param key key whose associated value is to be returned
     * @return value to which the specified key is mapped
     */
    fun getBytes(key: Int): ByteArray? {
        val tuple = getTuple(key, PebbleTuple.TupleType.BYTES)
        if (tuple == null) {
            return null
        }
        return tuple.value as ByteArray?
    }

    /**
     * Returns the string to which the specified key is mapped, or null if the key does not exist in this dictionary.
     *
     * @param key key whose associated value is to be returned
     * @return value to which the specified key is mapped
     */
    fun getString(key: Int): String? {
        val tuple = getTuple(key, PebbleTuple.TupleType.STRING)
        if (tuple == null) {
            return null
        }
        return tuple.value as String?
    }

    fun addTuple(tuple: PebbleTuple) {
        if (tuples.size > 0xff) {
            throw TupleOverflowException()
        }

        tuples.put(tuple.key, tuple)
    }

    class PebbleDictTypeException(key: Long, expected: PebbleTuple.TupleType, actual: PebbleTuple.TupleType) : RuntimeException(
        String.format(
            "Expected type '%s', but got '%s' for key 0x%08x", expected.name, actual.name, key
        )
    )

    class TupleOverflowException : RuntimeException("Too many tuples in dict")

    /**
     * Returns a JSON representation of this dictionary.
     *
     * @return a JSON representation of this dictionary
     */
    fun toJsonString(): String? {
        try {
            val array = JSONArray()
            for (t in tuples.values) {
                array.put(serializeTuple(t))
            }
            return array.toString()
        } catch (je: JSONException) {
            je.printStackTrace()
        }
        return null
    }

    companion object {
        private const val KEY = "key"
        private const val TYPE = "type"
        private const val LENGTH = "length"
        private const val VALUE = "value"

        /**
         * Deserializes a JSON representation of a PebbleDictionary.
         *
         * @param jsonString the JSON representation to be deserialized
         * @throws JSONException thrown if the specified JSON representation cannot be parsed
         */
        @Throws(JSONException::class)
        fun fromJson(jsonString: String?): PebbleClassicDictionary {
            val d = PebbleClassicDictionary()

            val elements = JSONArray(jsonString)
            for (idx in 0..<elements.length()) {
                val o = elements.getJSONObject(idx)
                val key = o.getInt(KEY)
                val type = PebbleTuple.TYPE_NAMES.get(o.getString(TYPE))
                val width = PebbleTuple.WIDTH_MAP.get(o.getInt(LENGTH))

                when (type) {
                    PebbleTuple.TupleType.BYTES -> {
                        val bytes = Base64.decode(o.getString(VALUE), Base64.NO_WRAP)
                        d.addBytes(key, bytes)
                    }

                    PebbleTuple.TupleType.STRING -> d.addString(key, o.getString(VALUE))
                    PebbleTuple.TupleType.INT -> if (width == PebbleTuple.Width.BYTE) {
                        d.addInt8(key, o.getInt(VALUE).toByte())
                    } else if (width == PebbleTuple.Width.SHORT) {
                        d.addInt16(key, o.getInt(VALUE).toShort())
                    } else if (width == PebbleTuple.Width.WORD) {
                        d.addInt32(key, o.getInt(VALUE))
                    }

                    PebbleTuple.TupleType.UINT -> if (width == PebbleTuple.Width.BYTE) {
                        d.addUint8(key, o.getInt(VALUE).toByte())
                    } else if (width == PebbleTuple.Width.SHORT) {
                        d.addUint16(key, o.getInt(VALUE).toShort())
                    } else if (width == PebbleTuple.Width.WORD) {
                        d.addUint32(key, o.getInt(VALUE))
                    }

                    null -> {}
                }
            }

            return d
        }

        @Throws(JSONException::class)
        private fun serializeTuple(t: PebbleTuple): JSONObject {
            val j = JSONObject()
            j.put(KEY, t.key)
            j.put(TYPE, t.type.tupleName)
            j.put(LENGTH, t.width.value)

            when (t.type) {
                PebbleTuple.TupleType.BYTES -> j.put(VALUE, Base64.encodeToString(t.value as ByteArray?, Base64.NO_WRAP))
                PebbleTuple.TupleType.STRING, PebbleTuple.TupleType.INT, PebbleTuple.TupleType.UINT -> j.put(VALUE, t.value)
            }

            return j
        }
    }
}

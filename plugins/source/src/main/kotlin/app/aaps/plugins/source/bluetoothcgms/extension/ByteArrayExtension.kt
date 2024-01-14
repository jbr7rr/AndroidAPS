package app.aaps.plugins.source.bluetoothcgms.extension

/** Extensions for different types of conversions needed when doing stuff with bytes */
fun ByteArray.toLong(): Long {
    require(this.size <= 8) {
        "Array size must be <= 8 for 'toLong' conversion operation"
    }
    var result = 0L
    for (i in this.indices) {
        val byte = this[i]
        val shifted = (byte.toInt() and 0xFF).toLong() shl 8 * i
        result = result or shifted
    }
    return result
}

fun ByteArray.toInt(): Int {
    require(this.size <= 4) {
        "Array size must be <= 4 for 'toInt' conversion operation"
    }
    var result = 0
    for (i in this.indices) {
        val byte = this[i]
        val shifted = (byte.toInt() and 0xFF) shl 8 * i
        result = result or shifted
    }
    return result
}

fun ByteArray.toFloat(): Float {
    require(this.size == 4) {
        "Array size must be == 4 for 'toFloat' conversion operation"
    }
    var asInt = 0
    for (i in this.indices) {
        val byte = this[i]
        val shifted = (byte.toInt() and 0xFF) shl 8 * i
        asInt = asInt or shifted
    }
    return Float.fromBits(asInt)
}

fun ByteArray.sFloatToFloat(): Float {
    if (this.size < 2) {
        throw IllegalArgumentException("ByteArray must contain at least 2 bytes")
    }

    val lowByte = this[0]
    val highByte = this[1]

    val combined = "${String.format("%02X", highByte)}${String.format("%02X", lowByte)}".toInt(16)

    // Extract exponent (first 4 bits) and magnitude (last 12 bits)
    val exponent = ((combined shr 12) and 0x000F).let {
        // Adjusting exponent to be in the range of [-8,7]
        if (it >= 8) -(16 - it) else it
    }
    var magnitude = combined and 0x0FFF

    // Convert to signed magnitude if the most significant bit is 1 (negative number)
    if (magnitude >= 2048) {
        magnitude -= 4096
    }

    // Calculate the actual floating-point value
    return (magnitude * Math.pow(10.0, exponent.toDouble())).toFloat()
}

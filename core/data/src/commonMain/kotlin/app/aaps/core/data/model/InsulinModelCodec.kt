package app.aaps.core.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Shared JSON codec for [InsulinModel], used across settings JSON, Room DB storage,
 * and v3 migration backfill so every persistence layer uses one format and one error policy.
 *
 * Error policy:
 * - Absent or `"BILINEAR"` -> [InsulinModel.Bilinear].
 * - Unknown `type` -> [InsulinModel.Bilinear] with [DecodeResult.unknownType] populated so callers
 *   can log a warning without breaking forward compatibility with newer app versions.
 * - Known `"PD"` with missing, non-numeric, or invalid parameters -> [InsulinModel.Pd] with
 *   [InsulinModel.isUsable] == false (e.g. `Double.NaN` or non-positive values), never silently
 *   rewritten to Bilinear.
 */
object InsulinModelCodec {

    const val TYPE_BILINEAR = "BILINEAR"
    const val TYPE_PD = "PD"
    const val DEFAULT_BILINEAR_JSON = """{"type":"BILINEAR"}"""

    data class DecodeResult(
        val model: InsulinModel,
        val unknownType: String? = null,
        val parseError: Boolean = false
    )

    fun toJsonObject(model: InsulinModel): JsonObject = when (model) {
        InsulinModel.Bilinear -> buildJsonObject {
            put("type", TYPE_BILINEAR)
        }

        is InsulinModel.Pd -> buildJsonObject {
            put("type", TYPE_PD)
            putFiniteOrNull("a0", model.a0)
            putFiniteOrNull("a1", model.a1)
            putFiniteOrNull("b1", model.b1)
            put("concentrationScalesPeak", model.concentrationScalesPeak)
            put("durationMinutes", model.durationMinutes)
        }
    }

    fun encodeToString(model: InsulinModel): String = toJsonObject(model).toString()

    fun decode(json: JsonObject?): DecodeResult {
        if (json == null) return DecodeResult(InsulinModel.Bilinear)
        val rawType = (json["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        if (rawType.isNullOrEmpty() || rawType == TYPE_BILINEAR) {
            return DecodeResult(InsulinModel.Bilinear)
        }
        if (rawType == TYPE_PD) {
            val a0 = readDoubleOrNaN(json, "a0")
            val a1 = readDoubleOrNaN(json, "a1")
            val b1 = readDoubleOrNaN(json, "b1")
            val concentrationScalesPeak = (json["concentrationScalesPeak"] as? JsonPrimitive)?.booleanOrNull ?: false
            val durationMinutes = if (json.containsKey("durationMinutes")) {
                (json["durationMinutes"] as? JsonPrimitive)?.intOrNull ?: 0
            } else {
                480
            }
            return DecodeResult(
                InsulinModel.Pd(
                    a0 = a0,
                    a1 = a1,
                    b1 = b1,
                    concentrationScalesPeak = concentrationScalesPeak,
                    durationMinutes = durationMinutes
                )
            )
        }
        return DecodeResult(InsulinModel.Bilinear, unknownType = rawType)
    }

    fun decodeFromString(raw: String?): DecodeResult {
        if (raw.isNullOrBlank()) return DecodeResult(InsulinModel.Bilinear)
        val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return DecodeResult(InsulinModel.Bilinear, parseError = true)
        return decode(obj)
    }

    fun fromJsonObject(json: JsonObject?, onUnknownType: ((String) -> Unit)? = null): InsulinModel {
        val result = decode(json)
        result.unknownType?.let { onUnknownType?.invoke(it) }
        return result.model
    }

    fun fromJsonString(raw: String?, onUnknownType: ((String) -> Unit)? = null): InsulinModel {
        val result = decodeFromString(raw)
        result.unknownType?.let { onUnknownType?.invoke(it) }
        return result.model
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putFiniteOrNull(key: String, value: Double) {
        if (value.isFinite()) put(key, JsonPrimitive(value)) else put(key, JsonNull)
    }

    private fun readDoubleOrNaN(json: JsonObject, key: String): Double {
        val primitive = json[key] as? JsonPrimitive ?: return Double.NaN
        if (primitive is JsonNull) return Double.NaN
        return primitive.doubleOrNull ?: Double.NaN
    }
}

package app.aaps.core.objects.extensions

import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.InsulinModel
import app.aaps.core.data.model.InsulinModelCodec
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.utils.lenientDouble
import app.aaps.core.utils.lenientLong
import app.aaps.core.utils.lenientString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun ICfg.toJsonObject(): JsonObject = buildJsonObject {
    put("insulinLabel", insulinLabel)
    put("insulinEndTime", insulinEndTime)
    put("insulinPeakTime", insulinPeakTime)
    put("concentration", JsonPrimitive(concentration))
    put("insulinNickname", insulinNickname)
    if (insulinModel !is InsulinModel.Bilinear) {
        put("insulinModel", InsulinModelCodec.toJsonObject(insulinModel))
    }
}

/**
 * used to restore configuration within InsulinPlugin and insulin Editor
 *
 * Reads leniently on purpose. `longOrNull` is `content.toLongOrNull()`, which answers null for
 * `1.8E7` and for `18000000.5` - forms that occur in stored documents.
 * Falling back to 0 there would mean `insulinEndTime == 0`, i.e. DIA 0, so
 * IOB would decay at once and the loop would believe there is no insulin on board. See
 * [lenientLong].
 */
fun ICfg.Companion.fromJsonObject(json: JsonObject, aapsLogger: AAPSLogger? = null): ICfg {
    val modelElement = json["insulinModel"]
    val insulinModel = when (modelElement) {
        null, is JsonNull -> InsulinModel.Bilinear
        is JsonObject -> {
            val decoded = InsulinModelCodec.decode(modelElement)
            if (decoded.unknownType != null) {
                aapsLogger?.warn(LTag.CORE, "Unknown insulinModel type '${decoded.unknownType}', falling back to Bilinear")
            }
            decoded.model
        }
        else -> {
            aapsLogger?.warn(LTag.CORE, "Malformed insulinModel entry in ICfg JSON, falling back to Bilinear")
            InsulinModel.Bilinear
        }
    }

    val icfg = ICfg(
        insulinLabel = json.lenientString("insulinLabel"),
        insulinEndTime = json.lenientLong("insulinEndTime"),
        insulinPeakTime = json.lenientLong("insulinPeakTime"),
        concentration = json.lenientDouble("concentration", 1.0),
        insulinModel = insulinModel
    )

    icfg.insulinNickname = json.lenientString("insulinNickname")

    return icfg
}

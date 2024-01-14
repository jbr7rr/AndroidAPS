package app.aaps.core.interfaces.source

interface CGMSSource {

    /**
     *  Send calibration to CGM Service
     */
    fun isEnabled(): Boolean
    fun sendCalibration(bg: Double): Boolean
}

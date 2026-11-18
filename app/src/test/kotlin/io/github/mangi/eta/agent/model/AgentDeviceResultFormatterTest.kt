package io.github.mangi.eta.agent.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AgentDeviceResultFormatterTest {
    @Test
    fun currentContextShowsTheDeviceTimeOffsetAndTheAgeOfItsLastLocation() {
        val json = JSONObject("""
            {"datetime":"2026-10-28T21:35:42+08:00","timezone":"Asia/Shanghai",
             "location":{"latitude":31.23041,"longitude":121.4737,"age_s":42}}
        """)
        assertEquals("2026-10-28 21:35 +08:00 · Recent location: 31.23041, 121.4737 (42 s ago)",
            AgentDeviceResultFormatter.summarize("get_current_context", json))
        json.getJSONObject("location").put("age_s", 172800)
        assertEquals("2026-10-28 21:35 +08:00 · Recent location: 31.23041, 121.4737 (2 d ago)",
            AgentDeviceResultFormatter.summarize("get_current_context", json))
        json.getJSONObject("location").remove("age_s")
        assertEquals("2026-10-28 21:35 +08:00 · Recent location: 31.23041, 121.4737",
            AgentDeviceResultFormatter.summarize("get_current_context", json))
    }

    @Test
    fun unavailableLocationDoesNotHideValidTimeOrInventCoordinates() {
        val statuses = mapOf(
            "permission_required" to "Location permission required",
            "background_permission_required" to "Background location permission required",
            "location_disabled" to "Location service is off",
            "unavailable" to "No recent location",
        )
        statuses.forEach { (status, label) ->
            val json = JSONObject().put("datetime", "2026-10-28T08:30:00-05:00")
                .put("location", JSONObject().put("status", status))
            assertEquals("2026-10-28 08:30 -05:00 · $label", AgentDeviceResultFormatter.summarize("get_current_context", json))
        }
        assertEquals("2026-10-28 08:30 -05:00 · Location unavailable", AgentDeviceResultFormatter.summarize("get_current_context", JSONObject(
            """{"datetime":"2026-10-28T08:30:00-05:00","location":{"latitude":91,"longitude":120}}""",
        )))
        assertNull(AgentDeviceResultFormatter.summarize("get_current_context", JSONObject("""{"datetime":"private-invalid-time"}""")))
    }

    @Test
    fun booleanChangesKeepUnknownAndUnchangedStatesDistinct() {
        assertEquals("Auto-rotate: Off → On", summarize("set_device_state", """{"target":"auto_rotate","before":false,"after":true}"""))
        assertEquals("Flashlight: kept Off", summarize("set_flashlight", """{"before":false,"after":false}"""))
        assertEquals("Personal hotspot: turned On", summarize("set_hotspot", """{"before":null,"after":true}"""))
        assertNull(summarize("set_device_state", """{"target":"private-target","after":true}"""))
        assertNull(summarize("set_flashlight", """{"after":"false"}"""))
        assertNull(summarize("set_flashlight", """{"expected":true}"""))
        assertNull(summarize("set_flashlight", """{"verified":false,"after":true}"""))
        assertNull(summarize("set_flashlight", """{"verified":"true","after":true}"""))
        assertNull(summarize("set_flashlight", """{"status":"unconfirmed","after":true}"""))
    }

    @Test
    fun volumeUsesActualLevelsAndFallsBackWhenScaleIsUnavailable() {
        assertEquals("Media volume: 33% → 53%", summarize("set_volume", """{"stream":"media","before":5,"after":8,"min_level":0,"max_level":15,"percent":50}"""))
        assertEquals("Call volume: 0% → 50%", summarize("set_volume", """{"stream":"voice_call","before":1,"after":3,"min_level":1,"max_level":5}"""))
        assertEquals("Ring volume: Level 2 → Level 4", summarize("set_volume", """{"stream":"ring","before":2,"after":4}"""))
        assertEquals("System volume: kept Level 0", summarize("set_volume", """{"stream":"system","before":0,"after":0,"min_level":0,"max_level":0}"""))
        assertEquals("Media volume: set to 50%", summarize("set_volume", """{"stream":"media","before":500,"after":501,"min_level":0,"max_level":1000}"""))
        assertNull(summarize("set_volume", """{"stream":"media","percent":50,"level":7}"""))
    }

    @Test
    fun numericChangesUseReadbackUnitsAndTreatMissingPreviousValuesAsUnknown() {
        assertEquals("Brightness: 30% → 59.5%", summarize("set_brightness", """{"before":30,"after":59.5,"expected":60}"""))
        assertEquals("Screen timeout: 30 s → 2 min", summarize("set_screen_timeout", """{"before":30000,"after":120000}"""))
        assertEquals("Screen timeout: set to 45 s", summarize("set_screen_timeout", """{"before":-1,"after":45000}"""))
        assertNull(summarize("set_screen_timeout", """{"after":-1}"""))
        assertNull(summarize("set_brightness", """{"actual_percent":60,"expected":60}"""))
        assertEquals("Sound mode: Normal → Vibrate", summarize("set_sound_mode", """{"before":2,"after":1,"mode":"silent"}"""))
        assertEquals("Do Not Disturb: Off → Alarms only", summarize("set_do_not_disturb", """{"before":0,"after":3}"""))
        assertNull(summarize("set_do_not_disturb", """{"after":99}"""))
    }

    @Test
    fun settingSummariesExposeOnlyKnownSafeKeysAndNeverArbitraryValues() {
        assertEquals("Auto-brightness: Off → On", summarize("set_setting", """{"namespace":"system","key":"screen_brightness_mode","before":"0","after":"1"}"""))
        assertEquals("Screen timeout: 15 s → 1 min", summarize("set_setting", """{"namespace":"system","key":"screen_off_timeout","before":"15000","after":"60000"}"""))
        assertEquals("Battery saver: Off → On", summarize("set_setting", """{"namespace":"global","key":"low_power","before":"0","after":"1"}"""))
        assertEquals("Do Not Disturb: Off → Total silence", summarize("set_setting", """{"namespace":"global","key":"zen_mode","before":"0","after":"2"}"""))
        assertEquals("System setting updated", summarize("set_setting", """{"namespace":"secure","key":"secret-key","before":"old-secret","after":"new-secret"}"""))
        assertEquals("System setting unchanged", summarize("set_setting", """{"namespace":"global","key":"private-key","before":"secret","after":"secret"}"""))
        assertEquals("System setting updated", summarize("set_setting", """{"namespace":"secure","key":"screen_brightness_mode","before":"secret-one","after":"secret-two"}"""))
        assertNull(summarize("set_setting", """{"namespace":"system","key":"accelerometer_rotation","after":"secret"}"""))
    }

    @Test
    fun applicationAndMediaSummariesDoNotInventRunningOrPlaybackState() {
        assertEquals("Stopped App com.example.app", summarize("app_state_control", """{"package_name":"com.example.app","action":"force_stop","state_field":"stopped","before":false,"after":true}"""))
        assertEquals("Froze App", summarize("app_state_control", """{"package_name":"private\nbody","action":"freeze","state_field":"enabled","before":true,"after":false}"""))
        assertNull(summarize("app_state_control", """{"action":"force_stop","state_field":"enabled","after":true}"""))
        val longPackage = "com.example." + "a".repeat(80)
        val summary = summarize("app_state_control", JSONObject().put("package_name", longPackage)
            .put("action", "unfreeze").put("state_field", "enabled").put("before", false).put("after", true).toString())!!
        assertFalse(summary.contains(longPackage))
        assertEquals("Unfroze App ${longPackage.take(59)}…", summary)
        assertEquals("Sent Pause command", summarize("media_control", """{"action":"pause"}"""))
        assertNull(summarize("media_control", """{"action":"private-action"}"""))
    }

    @Test
    fun deviceReadSummariesUseStrictPublicFieldsWithoutNetworkNames() {
        assertEquals("Bluetooth: On", summarize("get_device_state", """{"target":"bluetooth","enabled":true}"""))
        assertEquals("Flashlight: Off", summarize("get_flashlight", """{"enabled":false}"""))
        assertEquals("Wi-Fi / VPN: Connected to Internet", summarize("network_info", """{"connected":true,"validated":true,"transports":["wifi","vpn","private-network"],"ssid":"private-ssid"}"""))
        assertEquals("Cellular: Connected, Internet not validated", summarize("network_info", """{"connected":true,"validated":false,"transports":["cellular"]}"""))
        assertEquals("Network: disconnected", summarize("network_info", """{"connected":false,"validated":false,"transports":[]}"""))
        assertEquals("Battery 73% · Charging", summarize("device_status", """{"battery_percent":73,"charging":true}"""))
        assertEquals("Recent location: 31.23041, 121.4737 (42 s ago)", summarize("get_current_location", """{"latitude":31.23041,"longitude":121.4737,"age_ms":42000}"""))
        assertEquals("Device environment read", summarize("get_device_environment", """{"interactive":true}"""))
        assertNull(summarize("network_info", """{"connected":"true","validated":true,"transports":[]}"""))
        assertNull(summarize("device_status", """{"battery_percent":-1,"charging":true}"""))
        assertNull(summarize("device_status", """{"battery_percent":73}"""))
        assertNull(summarize("get_hotspot", """{"enabled":"false"}"""))
    }

    private fun summarize(toolName: String, fields: String): String? {
        val json = JSONObject(fields)
        if (!json.has("ok")) json.put("ok", true)
        if (!json.has("verified")) json.put("verified", true)
        return AgentDeviceResultFormatter.summarize(toolName, json)
    }
}

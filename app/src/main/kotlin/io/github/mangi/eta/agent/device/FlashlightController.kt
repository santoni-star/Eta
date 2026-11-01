package io.github.mangi.eta.agent.device

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.mangi.eta.agent.tool.DeviceMutationResult
import org.json.JSONObject

/** Процес застосування має світлове з’єднання, і його не можна знищити викликом інструменту, інакше система негайно вимкне світло.*/
internal class FlashlightController private constructor(context: Context) {
    private val manager = context.applicationContext.getSystemService(CameraManager::class.java)
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val monitor = Object()
    private val states = mutableMapOf<String, Boolean?>()
    private val cameraId: String?

    init {
        cameraId =
            try {
                val cameras =
                    manager?.cameraIdList.orEmpty().filter {
                        manager
                            ?.getCameraCharacteristics(it)
                            ?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    }
                cameras.firstOrNull {
                    manager?.getCameraCharacteristics(it)?.get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_BACK
                } ?: cameras.firstOrNull()
            } catch (_: CameraAccessException) {
                null
            } catch (_: SecurityException) {
                null
            }
        if (cameraId != null)
            manager?.registerTorchCallback(
                object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(id: String, enabled: Boolean) =
                        publish(id, enabled)

                    override fun onTorchModeUnavailable(id: String) = publish(id, null)
                },
                Handler(Looper.getMainLooper()),
            )
    }

    private fun publish(id: String, value: Boolean?) =
        synchronized(monitor) {
            states[id] = value
            monitor.notifyAll()
        }

    private fun awaitState(expected: Boolean? = null): Boolean? =
        synchronized(monitor) {
            val end = SystemClock.elapsedRealtime() + 1_500
            while (cameraId !in states || expected != null && states[cameraId] != expected) {
                val remaining = end - SystemClock.elapsedRealtime()
                if (remaining <= 0 || Looper.myLooper() == Looper.getMainLooper()) break
                try {
                    monitor.wait(remaining)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            states[cameraId]
        }

    fun state(): JSONObject {
        if (cameraId == null)
            return DeviceToolContract.failure("FLASHLIGHT_UNAVAILABLE", "налаштуватиПриготуйтебезматиможевикористовуватизспалахсвітлолампа")
        val enabled = awaitState()
        return JSONObject()
            .put("ok", enabled != null)
            .put("tool", "get_flashlight")
            .put("enabled", enabled ?: JSONObject.NULL)
            .put("available", enabled != null)
            .also {
                if (enabled == null)
                    it.put("code", "FLASHLIGHT_BUSY").put("message", "спалахсвітлолампатимчасовонемаєможевикористовувати，можездатнийпростоковдраВзаємномашиназаймативикористовувати")
            }
    }

    fun set(enabled: Boolean, userId: Int): JSONObject {
        val id =
            cameraId ?: return DeviceToolContract.failure("FLASHLIGHT_UNAVAILABLE", "налаштуватиПриготуйтебезматиможевикористовуватизспалахсвітлолампа")
        val before = awaitState()
        if (before == enabled)
            return DeviceMutationResult.observed(
                "set_flashlight",
                userId,
                "device",
                before,
                before,
                enabled,
                true,
            )
        return try {
            manager!!.setTorchMode(id, enabled)
            DeviceMutationResult.observed(
                "set_flashlight",
                userId,
                "device",
                before,
                awaitState(enabled),
                enabled,
                true,
            )
        } catch (_: CameraAccessException) {
            DeviceToolContract.failure("FLASHLIGHT_BUSY", "КраваткасистеманемаєЗаконТримайтеробитиспалахсвітлолампа，будь ласкаперевіритиперевіритиВзаємномашиназаймативикористовуватиіналаштуватиПриготуйтеформустан")
        } catch (_: SecurityException) {
            DeviceToolContract.failure("FLASHLIGHT_ACCESS_DENIED", "КраваткасистемавідхилитиАбсолютнорукуелектроенергіяциліндрКОНТРОЛЬсистема")
        } catch (_: IllegalArgumentException) {
            DeviceToolContract.failure("FLASHLIGHT_UNAVAILABLE", "спалахсвітлолампаналаштуватиПриготуйтевженемаєможевикористовувати")
        }
    }

    companion object {
        @Volatile private var instance: FlashlightController? = null

        fun get(context: Context): FlashlightController =
            instance
                ?: synchronized(this) {
                    instance ?: FlashlightController(context).also { instance = it }
                }
    }
}

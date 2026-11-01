package io.github.mangi.eta.agent.terminal

import java.io.File
import org.json.JSONObject

/** Запуск фіксованого каталогу та середовища Linux одночасно; Стандартний ідентифікатор Android вибирається на основі підтвердженої авторизації.*/
internal data class TerminalExecutionContext(
    val linuxEnvironment: TerminalEnvironment,
    val linuxRootfsPath: String?,
    val userWorkspacePath: String = TerminalRuntime.userWorkspacePath,
) {
    init {
        require(linuxEnvironment.isLinux)
    }

    val androidWorkspacePath: String
        get() = userWorkspacePath

    val linuxBackend: LinuxExecutionBackend
        get() = LinuxEnvironmentPaths.backendOf(linuxRootfsPath)

    val linuxIdentity: String
        get() = if (linuxBackend == LinuxExecutionBackend.PROOT) "user" else "root"

    val linuxWorkspacePath: String
        get() = TerminalRuntime.LINUX_WORKSPACE_PATH

    val linuxHostWorkspacePath: String
        get() = if (linuxIdentity == "root") TerminalRuntime.ROOT_WORKSPACE_PATH else userWorkspacePath

    val linuxReady: Boolean
        get() = LinuxEnvironmentPaths.rootfsReady(linuxRootfsPath)

    val linuxCommonToolsReady: Boolean
        get() = AlpineEnvironmentPaths.commonToolsReady(linuxRootfsPath)

    val linuxInstalledProfiles: List<String>
        get() {
            if (!linuxReady || linuxRootfsPath.isNullOrBlank()) return emptyList()
            val rootfs = File(linuxRootfsPath)
            return buildList {
                LinuxPackageProfiles.ALL.forEach { profile ->
                    if (linuxPackageProfileReady(rootfs, profile)) add(profile.id)
                }
                if (linuxApkAnalysisReady(rootfs)) add(APK_ANALYSIS_PROFILE_ID)
            }
        }

    fun identityFor(environment: TerminalEnvironment, rootAvailable: Boolean): String =
        if (environment.isLinux) linuxIdentity else if (rootAvailable) "root" else "user"

    fun description(rootAvailable: Boolean): String = buildString {
        append("книгаДругосортнийтранспортдобрезжиттязробитиімистецтваштукпрацюватиІнструментпоширенийвикористовуватимовчазнийрозпізнатиВиконатидобреначальникавнизмистецтва：environment=android，identity=")
        append(identityFor(TerminalEnvironment.ANDROID, rootAvailable))
        append("，працюватиробитирайон=")
        append(JSONObject.quote(androidWorkspacePath))
        append("。вженавчатиправильно Root годинапрямозловитизробитивикористовувати Root，ще ніправильнорозпізнатинавчатиправильногодиназробитивикористовувати App тілочастка。identity=user можешоуРежимвибративибратизагальнийПастілочастка；працюватиробитиГоловазаписнемаєтому щотілочасткаЗміназмінитиізмінарухатися。\n")
        append("environment=linux зробитивикористовувативжевибративибратиз ")
        append(linuxEnvironment.wireName)
        append("，backend=")
        append(linuxBackend.wireName)
        append("，identity=")
        append(linuxIdentity)
        append("，ready=")
        append(linuxReady)
        append("，common_tools=")
        append(linuxCommonToolsReady)
        append("，profiles=")
        append(linuxInstalledProfiles.ifEmpty { listOf("none") }.joinToString("/"))
        append("。Linux працюватиробитирайон ")
        append(JSONObject.quote(linuxWorkspacePath))
        append(" відображатистрілятипроживанняхостГоловазапис ")
        append(JSONObject.quote(linuxHostWorkspacePath))
        append("；ТількиматипроживанняхостГоловазаписВзаємноте самегодинаталантімовчазнийрозпізнати Android працюватиробитирайонпоширенийНасолоджуйтесьмистецтваштук。Linux дорогашляхвідповідно до Linux кільцетериторіїрозв'язатиреліз。")
    }

    private companion object {
        const val APK_ANALYSIS_PROFILE_ID = "apk_analysis"
    }
}

package io.github.mangi.eta.agent.tool

import android.content.Context
import android.net.Uri
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.BoundedFileCopy
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.media.MAX_AGENT_IMAGE_BYTES
import io.github.mangi.eta.agent.model.AgentModelClient
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import org.json.JSONObject

/** Прочитайте окреме зображення, яке було явно вказано користувачем, і передайте його поточній моделі як тимчасове візуальне прикріплення.*/
internal class AgentImageTools(
    private val context: Context,
    private val root: BoundedRootCommandExecutor,
    private val rootAvailable: () -> Boolean = { RootAccess.isGranted },
) {
    fun readImage(args: JSONObject): AgentModelClient.ToolResult {
        val source = args.getString("path").removePrefix("file://")
        val sourceKind = when {
            source.startsWith("content://") -> ImageSourceKind.ContentUri
            source.startsWith("/") && !source.contains('\u0000') -> ImageSourceKind.File
            else -> return sensitive(error("IMAGE_PATH_DENIED", "малюнокшматокдорогашляхповиненОбов'язковотакАбсолютноправильнодорогашлях、file URI абовженавчатиправильноз content URI"))
        }
        val temporaryFile = runCatching {
            File.createTempFile("eta-read-image-", ".img", imageCacheDirectory())
        }.getOrElse {
            return sensitive(error("IMAGE_TEMPORARY_FILE_FAILED", "немаєЗаконстворитивстановитималюнокшматокProгодинамистецтваштук"))
        }
        return try {
            val staged = copyAsApp(source, sourceKind, temporaryFile)
            if (!staged) {
                if (!rootAvailable()) {
                    return sensitive(error("IMAGE_ACCESS_DENIED", "Eta немаєЗакончитатиВиберітьцемалюнокшматок；будь ласкапершеПасПасмистецтваштуквибративибратипристрійкерівництвовведітьабонавчатидатичитатиВиберітьправильнообмеження"))
                }
                val copyResult = root.execute(
                    imageCopyCommand(source, sourceKind, temporaryFile),
                    timeoutMillis = READ_TIMEOUT_MS,
                    maxOutputBytes = 8 * 1024,
                )
                if (!copyResult.ok) return sensitive(copyFailure(copyResult))
            }
            val image = AgentImageCodec.fromToolFile(
                file = temporaryFile,
                source = "tool_read_image",
            ) ?: return sensitive(error("IMAGE_UNSUPPORTED", "мистецтваштукнемаєтакможезнанняне требазмалюнокшматок"))
            sensitive(
                content = JSONObject()
                    .put("ok", true)
                    .put("tool", "read_image")
                    .put("path", source)
                    .put("image_attached", true)
                    .toString(),
                images = listOf(image),
            )
        } catch (_: BoundedFileCopy.TooLargeException) {
            sensitive(error("IMAGE_TOO_LARGE", "малюнокшматокобганятиПасвеликийМаленькийобмеженнясистема"))
        } finally {
            temporaryFile.delete()
        }
    }

    private fun copyAsApp(source: String, sourceKind: ImageSourceKind, destination: File): Boolean = try {
        val input = when (sourceKind) {
            ImageSourceKind.File -> File(source).takeIf { it.isFile && it.canRead() }?.inputStream()
            ImageSourceKind.ContentUri -> context.contentResolver.openInputStream(Uri.parse(source))
        }
        if (input == null) {
            false
        } else {
            input.use { sourceStream ->
                destination.outputStream().use { target ->
                    BoundedFileCopy.copy(sourceStream, target, MAX_IMAGE_FILE_BYTES)
                }
            }
            true
        }
    } catch (tooLarge: BoundedFileCopy.TooLargeException) {
        throw tooLarge
    } catch (interrupted: InterruptedIOException) {
        throw interrupted
    } catch (_: SecurityException) {
        false
    } catch (_: IOException) {
        false
    }

    private fun imageCopyCommand(
        source: String,
        sourceKind: ImageSourceKind,
        destination: File,
    ): String = when (sourceKind) {
        ImageSourceKind.File -> "[ -f ${shellQuote(source)} ] || exit 21; " +
            "[ \"$(stat -c %s ${shellQuote(source)})\" -le $MAX_IMAGE_FILE_BYTES ] || exit 22; " +
            "cp ${shellQuote(source)} ${shellQuote(destination.absolutePath)} || exit 23"
        ImageSourceKind.ContentUri -> "content read --uri ${shellQuote(source)} 2>/dev/null | " +
            "head -c ${MAX_IMAGE_FILE_BYTES + 1L} > ${shellQuote(destination.absolutePath)} && " +
            "[ \"$(stat -c %s ${shellQuote(destination.absolutePath)})\" -le $MAX_IMAGE_FILE_BYTES ]"
    }

    private fun imageCacheDirectory(): File =
        context.externalCacheDir
            ?.takeIf { it.isDirectory || it.mkdirs() }
            ?: context.cacheDir

    private fun copyFailure(result: BoundedRootCommandExecutor.Result): String = when (result.exitCode) {
        21 -> error("IMAGE_SOURCE_UNAVAILABLE", "малюнокшматокджереломистецтваштукнемаєжитиіснуютьабоколивпереднемаєможечитати")
        22 -> error("IMAGE_TOO_LARGE", "малюнокшматокобганятиПасвеликийМаленькийобмеженнясистема")
        23 -> error("IMAGE_STAGE_FAILED", "Root немаєЗаконВолямалюнокшматокскладнісистемаприбути Eta Proгодинаповільнийжити")
        else -> error("IMAGE_UNAVAILABLE", "малюнокшматокчитатиВиберітьвтратитипоразка")
    }

    private fun error(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("message", message).toString()

    private fun sensitive(
        content: String,
        images: List<AgentModelClient.ModelImage> = emptyList(),
    ) = AgentModelClient.ToolResult(content = content, images = images, sensitive = true)

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private enum class ImageSourceKind {
        File,
        ContentUri,
    }

    private companion object {
        const val READ_TIMEOUT_MS = 15_000L
        const val MAX_IMAGE_FILE_BYTES = MAX_AGENT_IMAGE_BYTES.toLong()
    }
}

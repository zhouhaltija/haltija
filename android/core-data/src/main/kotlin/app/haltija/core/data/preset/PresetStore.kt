package app.haltija.core.data.preset

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** 预设文件格式错误。 */
class PresetFormatException(message: String) : IllegalArgumentException(message)

/**
 * 预设的读取与落盘。
 *
 * SillyTavern 把预设放在 `data/<user>/presets/<kind>/<名字>.json`，
 * **文件名（去掉扩展名）就是预设 id**，文件内部的 `name` 字段是显示名。
 * 两者可能不一致，所以 [Preset.name] 与 [Preset.fileName] 分开保留。
 *
 * 与其他数据模型一致：原始 JSON 无损保留，未知字段原样写回。
 */
object PresetStore {

    private val READER: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowTrailingComma = true
    }

    private val COMPACT: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 按类型解析单个预设。 */
    fun parse(kind: PresetKind, text: String, fileName: String? = null): Preset {
        val root = try {
            READER.parseToJsonElement(text.removePrefix("\uFEFF")) as? JsonObject
        } catch (e: Exception) {
            throw PresetFormatException("预设不是合法 JSON：${e.message}")
        } ?: throw PresetFormatException("预设的顶层不是 JSON 对象")

        return when (kind) {
            PresetKind.CONTEXT -> ContextTemplate(root, fileName)
            PresetKind.INSTRUCT -> InstructTemplate(root, fileName)
            PresetKind.SYSPROMPT -> SystemPrompt(root, fileName)
            PresetKind.REASONING -> ReasoningTemplate(root, fileName)
        }
    }

    fun parse(kind: PresetKind, bytes: ByteArray, fileName: String? = null): Preset =
        parse(kind, bytes.toString(StandardCharsets.UTF_8), fileName)

    /** 读取某个预设目录下的全部 `.json`，按文件名排序（与 ST 的列举顺序一致）。 */
    fun readDirectory(root: Path): List<Preset> {
        if (!Files.isDirectory(root)) return emptyList()
        val files = Files.list(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
                .sorted()
                .toList()
        }
        return files.map { path ->
            parse(
                kind = kindOf(root),
                text = Files.readString(path, StandardCharsets.UTF_8),
                fileName = path.fileName.toString().removeSuffix(".json"),
            )
        }
    }

    fun readDirectory(root: File): List<Preset> = readDirectory(root.toPath())

    /** 在用户数据目录下读取某一类预设：`<userDataRoot>/presets/<kind>/`。 */
    fun readAll(userDataRoot: Path, kind: PresetKind): List<Preset> =
        readDirectory(userDataRoot.resolve("presets").resolve(kind.directory))

    /** 序列化为文件内容（紧凑 JSON + 结尾换行）。 */
    fun encode(preset: Preset): String =
        COMPACT.encodeToString(JsonObject.serializer(), preset.toJsonObject()) + "\n"

    fun write(path: Path, preset: Preset) {
        val parent = path.parent
        if (parent != null) Files.createDirectories(parent)
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.write(tmp, encode(preset).toByteArray(StandardCharsets.UTF_8))
        Files.move(
            tmp, path,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
    }

    /** 从目录名反推预设类型；无法识别时按上下文模板处理。 */
    private fun kindOf(directory: Path): PresetKind {
        val dirName = directory.fileName?.toString()
        return PresetKind.entries.firstOrNull { it.directory == dirName } ?: PresetKind.CONTEXT
    }
}

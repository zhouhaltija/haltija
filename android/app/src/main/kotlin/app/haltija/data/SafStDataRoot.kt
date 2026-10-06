package app.haltija.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.haltija.core.data.store.StDataRoot
import java.io.IOException
import java.util.UUID

/**
 * 基于 SAF（Storage Access Framework）的数据根。
 *
 * 用户可以在设置里把数据根**直接指到桌面 SillyTavern 的 `data/default-user/`**
 * （放在共享存储里，例如 `Documents/SillyTavern/data/default-user`）。
 * 配合 Syncthing，手机与电脑就能共用同一份角色卡与聊天记录 ——
 * 这是「数据格式严格对齐」最实在的兑现方式。
 *
 * ## 性能上的取舍
 *
 * SAF 的每次操作都会跨进程走到 DocumentsProvider，比 `java.io.File` 慢一个量级。
 * 所以：
 *  - 列表与读取照常走 SAF；
 *  - **不要**在 UI 线程调用；
 *  - 角色卡的批量加载需要缓存（PNG 卡必须整份读入才能拿到名字）。
 *
 * 这里不做缓存，把优化留给上层决定。
 */
class SafStDataRoot(
    private val context: Context,
    private val treeUri: Uri,
) : StDataRoot {

    override val label: String = runCatching {
        DocumentFile.fromTreeUri(context, treeUri)?.name
    }.getOrNull() ?: treeUri.lastPathSegment.orEmpty()

    private fun rootDocument(): DocumentFile? = DocumentFile.fromTreeUri(context, treeUri)

    /** 逐级找到路径对应的文档。路径不存在时返回 null。 */
    private fun find(path: String): DocumentFile? {
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
        var current: DocumentFile = rootDocument() ?: return null
        for (segment in segments) {
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    /** 找到（必要时创建）路径对应的目录。 */
    private fun ensureDirectory(path: String): DocumentFile? {
        var current: DocumentFile = rootDocument() ?: return null
        for (segment in path.trim('/').split('/').filter { it.isNotEmpty() }) {
            current = current.findFile(segment)
                ?: current.createDirectory(segment)
                ?: return null
            if (!current.isDirectory) return null
        }
        return current
    }

    override fun exists(path: String): Boolean = find(path)?.exists() == true || find(backupPath(path))?.exists() == true

    override fun isDirectory(path: String): Boolean = find(path)?.isDirectory == true

    override fun list(path: String): List<String> =
        find(path)?.takeIf { it.isDirectory }?.listFiles()?.mapNotNull { it.name }
            ?.map { name ->
                if (name.startsWith('.') && name.endsWith(".haltija-backup")) {
                    name.removePrefix(".").removeSuffix(".haltija-backup")
                } else name
            }?.distinct()?.sorted()
            ?: emptyList()

    override fun read(path: String): ByteArray? {
        // 若上次覆盖中途被杀，完整的备份优先于可能截断的目标文件。
        val parentPath = path.substringBeforeLast('/', "")
        val fileName = path.substringAfterLast('/')
        val backup = find(StDataRoot.joinPath(parentPath, backupName(fileName)))
        val file = (backup ?: find(path))?.takeIf { it.isFile } ?: return null
        return readDocument(file)
    }

    override fun write(path: String, bytes: ByteArray) {
        val parentPath = path.substringBeforeLast('/', "")
        val fileName = path.substringAfterLast('/')
        val parent = ensureDirectory(parentPath) ?: throw IOException("无法创建目录：$parentPath")

        // DocumentsProvider 不保证原子覆盖。先完成可恢复的备份再覆盖目标，
        // 成功后删除备份；中断时 read() 会使用备份，下一次写入可重试。
        val existing = parent.findFile(fileName)
        val backup = parent.findFile(backupName(fileName)) ?: run {
            val temporary = parent.createFile("application/octet-stream", ".haltija-${UUID.randomUUID()}.tmp")
                ?: throw IOException("无法准备保存：$fileName")
            try {
                writeDocument(temporary, existing?.let(::readDocument) ?: bytes)
                if (!temporary.renameTo(backupName(fileName))) throw IOException("无法准备备份：$fileName")
                temporary
            } catch (e: Exception) {
                temporary.delete()
                throw e
            }
        }
        val target = existing ?: parent.createFile(guessMimeType(fileName), fileName)
            ?: throw IOException("无法创建文件：$fileName")
        writeDocument(target, bytes)
        if (!backup.delete()) throw IOException("保存未完成，请重试：$fileName")
    }

    private fun backupName(fileName: String): String = ".$fileName.haltija-backup"

    private fun backupPath(path: String): String = StDataRoot.joinPath(
        path.substringBeforeLast('/', ""), backupName(path.substringAfterLast('/')),
    )

    private fun readDocument(file: DocumentFile): ByteArray =
        context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
            ?: throw IOException("无法读取文件：${file.name}")

    private fun writeDocument(file: DocumentFile, bytes: ByteArray) {
        val output = context.contentResolver.openOutputStream(file.uri, "wt")
            ?: throw IOException("无法写入文件：${file.name}")
        output.use { it.write(bytes) }
    }

    override fun delete(path: String) {
        find(path)?.delete()
        val parentPath = path.substringBeforeLast('/', "")
        find(StDataRoot.joinPath(parentPath, backupName(path.substringAfterLast('/'))))?.delete()
    }

    override fun mkdirs(path: String) {
        ensureDirectory(path) ?: throw IOException("无法创建目录：$path")
    }

    private fun guessMimeType(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "json" -> "application/json"
        // 使用 octet-stream，避免 DocumentsProvider 自动追加 .json / .zip。
        "jsonl", "charx" -> "application/octet-stream"
        else -> "application/octet-stream"
    }

    companion object {
        /**
         * 该 URI 是否是一个可长期访问的目录授权。
         *
         * 选目录时必须带上 `FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION`，
         * 再 `takePersistableUriPermission`，否则重启后就失效了。
         */
        fun isValidTreeUri(uri: Uri?): Boolean =
            uri != null && uri.scheme == "content" && uri.authority?.isNotEmpty() == true
    }
}

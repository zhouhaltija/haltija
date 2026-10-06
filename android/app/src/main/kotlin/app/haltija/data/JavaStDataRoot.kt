package app.haltija.data

import app.haltija.core.data.store.StDataRoot
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 基于 `java.io.File` 的数据根。
 *
 * 用在**应用私有目录**上：`/data/data/app.haltija/files/st-data/`。
 * 好处是读写快、不受分区存储限制；代价是卸载即丢，
 * 而且别的 App（比如 Syncthing、桌面 ST）看不见。
 *
 * 想和桌面共用同一份数据，用 [SafStDataRoot]。
 */
class JavaStDataRoot(private val base: File) : StDataRoot {

    override val label: String get() = base.name

    override fun exists(path: String): Boolean = resolve(path).exists()

    override fun isDirectory(path: String): Boolean = resolve(path).isDirectory

    override fun list(path: String): List<String> =
        resolve(path).takeIf { it.isDirectory }?.list()?.sorted() ?: emptyList()

    override fun read(path: String): ByteArray? =
        resolve(path).takeIf { it.isFile }?.readBytes()

    override fun write(path: String, bytes: ByteArray) {
        val file = resolve(path)
        file.parentFile?.mkdirs()
        val temporary = Files.createTempFile(requireNotNull(file.parentFile).toPath(), ".haltija-", ".tmp")
        try {
            java.io.FileOutputStream(temporary.toFile()).use {
                it.write(bytes)
                it.fd.sync()
            }
            Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun delete(path: String) {
        val file = resolve(path)
        if (file.isDirectory) file.deleteRecursively() else file.delete()
    }

    override fun mkdirs(path: String) {
        resolve(path).mkdirs()
    }

    /** 把相对路径解析成绝对文件；同时挡住 `..` 逃逸。 */
    private fun resolve(path: String): File {
        val cleaned = path.trim('/')
        val file = if (cleaned.isEmpty()) base else File(base, cleaned)
        // 防御性检查：不允许跳出数据根
        val canonicalBase = base.canonicalFile
        val canonical = runCatching { file.canonicalFile }.getOrElse { return file }
        return if (canonical.path == canonicalBase.path || canonical.path.startsWith(canonicalBase.path + File.separator)) {
            canonical
        } else {
            File(canonicalBase, canonical.name)
        }
    }
}

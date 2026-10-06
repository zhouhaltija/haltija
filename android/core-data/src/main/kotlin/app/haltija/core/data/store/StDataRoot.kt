package app.haltija.core.data.store

/**
 * SillyTavern 数据目录的访问抽象。
 *
 * 把「怎么读文件」和「文件是什么格式」分开：
 * 上层仓库只认这个接口，于是同一套逻辑既能跑在应用私有目录（`java.io.File`），
 * 也能跑在用户通过 SAF 选中的目录上，测试里还能用内存实现。
 *
 * 路径一律是**相对数据根**的，用 `/` 分隔，例如
 * `characters/Seraphina.png`、`chats/Seraphina/Seraphina - 2026-10-05.jsonl`。
 *
 * 实现只需要保证「读写和列举」，不需要理解任何格式。
 */
interface StDataRoot {

    /** 给用户看的名字，例如目录名。 */
    val label: String

    fun exists(path: String): Boolean

    fun isDirectory(path: String): Boolean

    /** 列出一个目录下的**直接**子项名字（不含路径）。目录不存在时返回空列表。 */
    fun list(path: String): List<String>

    /** 读整个文件；不存在返回 null。 */
    fun read(path: String): ByteArray?

    /** 写整个文件，父目录不存在时自动创建。 */
    fun write(path: String, bytes: ByteArray)

    fun delete(path: String)

    /** 递归创建目录。 */
    fun mkdirs(path: String)

    /** 该路径是否是普通文件。 */
    fun isFile(path: String): Boolean = exists(path) && !isDirectory(path)

    /**
     * 列出目录下指定扩展名的文件。
     *
     * 扩展名不带点；**两边都做小写归一**，所以传 `setOf("PNG")` 或 `setOf("png")` 等价。
     */
    fun listFiles(path: String, extensions: Set<String>): List<String> {
        val normalized = extensions.map { it.lowercase().removePrefix(".") }.toSet()
        return list(path).filter { name ->
            isFile(joinPath(path, name)) &&
                name.substringAfterLast('.', "").lowercase() in normalized
        }
    }

    companion object {
        fun joinPath(parent: String, child: String): String =
            if (parent.isEmpty()) child else "$parent/$child"
    }
}

/** 目录不存在。 */
class StDataRootException(message: String) : RuntimeException(message)

/**
 * 内存实现。
 *
 * 测试用：把整份 fixtures 塞进去就能验证仓库逻辑，不用碰真实文件系统。
 */
class MemoryStDataRoot(
    override val label: String = "memory",
    initial: Map<String, ByteArray> = emptyMap(),
) : StDataRoot {

    private val files = LinkedHashMap<String, ByteArray>()

    init {
        initial.forEach { (path, bytes) -> files[normalize(path)] = bytes }
    }

    override fun exists(path: String): Boolean {
        val key = normalize(path)
        return files.containsKey(key) || hasChildren(key)
    }

    override fun isDirectory(path: String): Boolean = hasChildren(normalize(path))

    override fun list(path: String): List<String> {
        val prefix = normalize(path).let { if (it.isEmpty()) "" else "$it/" }
        return files.keys
            .filter { it.startsWith(prefix) && it != prefix }
            .map { it.removePrefix(prefix).substringBefore('/') }
            .distinct()
            .sorted()
    }

    override fun read(path: String): ByteArray? = files[normalize(path)]

    override fun write(path: String, bytes: ByteArray) {
        files[normalize(path)] = bytes
    }

    override fun delete(path: String) {
        val key = normalize(path)
        files.remove(key)
        files.keys.filter { it.startsWith("$key/") }.forEach(files::remove)
    }

    override fun mkdirs(path: String) {
        // 内存实现里目录是隐含的，无需显式创建
    }

    /** 供测试断言用：当前所有文件路径。 */
    fun paths(): List<String> = files.keys.toList()

    private fun hasChildren(path: String): Boolean =
        path.isNotEmpty() && files.keys.any { it.startsWith("$path/") }

    private fun normalize(path: String): String = path.trim('/').replace("//", "/")
}

package app.haltija.data

import app.haltija.core.data.store.CharacterRepository
import app.haltija.core.data.store.SillyTavernData
import app.haltija.core.data.store.StDataRoot
import app.haltija.core.data.world.WorldBook
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [JavaStDataRoot] 的真实文件系统测试。
 *
 * 这是唯一碰真实磁盘的一层，所以单独测：路径拼接、目录创建、越界防护。
 * 仓库层的逻辑用内存实现测（见 `core-data` 的 `RepositoriesTest`）。
 */
class JavaStDataRootTest {

    private val base: File = Files.createTempDirectory("haltija-root").toFile()

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    private fun root() = JavaStDataRoot(base)

    @Test
    fun `写入会自动建父目录`() {
        val root = root()
        root.write("characters/a/b.png", byteArrayOf(1, 2, 3))

        assertTrue(File(base, "characters/a/b.png").isFile)
        assertTrue(root.read("characters/a/b.png")!!.contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `列出与过滤`() {
        val root = root()
        root.write("characters/b.png", byteArrayOf(1))
        root.write("characters/a.png", byteArrayOf(2))
        root.write("characters/note.txt", byteArrayOf(3))

        assertEquals(listOf("a.png", "b.png", "note.txt"), root.list("characters"))
        assertEquals(listOf("a.png", "b.png"), root.listFiles("characters", setOf("png")))
    }

    @Test
    fun `不存在的路径安全返回`() {
        val root = root()
        assertTrue(!root.exists("nope"))
        assertTrue(!root.isDirectory("nope"))
        assertEquals(emptyList(), root.list("nope"))
        assertEquals(null, root.read("nope/x.png"))
    }

    @Test
    fun `删除目录会递归删掉内容`() {
        val root = root()
        root.write("chats/c/a.jsonl", byteArrayOf(1))
        root.write("chats/c/b.jsonl", byteArrayOf(2))

        root.delete("chats/c")

        assertTrue(!root.exists("chats/c"))
        assertTrue(!root.exists("chats/c/a.jsonl"))
    }

    @Test
    fun `相对路径不能跳出数据根`() {
        val outside = File(base.parentFile, "outside-${base.name}.txt")
        outside.writeText("secret")
        try {
            val root = root()
            val leaked = root.read("../${outside.name}")
            assertTrue(leaked == null || !leaked.toString(Charsets.UTF_8).contains("secret"))
        } finally {
            outside.delete()
        }
    }

    @Test
    fun `仓库层在真实文件系统上可用`() {
        val data = SillyTavernData(root(), userHandle = "")
        assertTrue(!data.looksLikeStData(), "空目录不该被认成 ST 数据")

        val book = WorldBook.parse("""{"entries":{"0":{"uid":0,"key":["a"],"content":"x"}}}""", "test")
        data.worlds.save(book, "test")

        assertTrue(data.looksLikeStData(), "有 worlds/ 之后应被认出来")
        assertEquals(listOf("test"), data.worlds.listNames())
        assertEquals(book.toJsonObject(), data.worlds.load("test")!!.toJsonObject())

        assertEquals(emptyList(), CharacterRepository(root()).list(), "还没有角色卡")
    }

    @Test
    fun `userHandle 层在真实文件系统上生效`() {
        val data = SillyTavernData(root(), userHandle = "default-user")
        data.worlds.save(WorldBook.parse("""{"entries":{}}""", "b"), "b")

        assertTrue(File(base, "default-user/worlds/b.json").isFile, "应落在 default-user/ 下")
        assertTrue(data.looksLikeStData())
    }

    @Test
    fun `joinPath 的拼接规则`() {
        assertEquals("a/b", StDataRoot.joinPath("a", "b"))
        assertEquals("b", StDataRoot.joinPath("", "b"))
    }
}

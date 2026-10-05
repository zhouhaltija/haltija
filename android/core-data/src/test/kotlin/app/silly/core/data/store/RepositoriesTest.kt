package app.silly.core.data.store

import app.silly.core.data.card.CharacterCardCodec
import app.silly.core.data.preset.PresetKind
import app.silly.core.data.preset.SystemPrompt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 仓库层的验收测试。
 *
 * 用的是**真实的 fixtures 布局**：`fixtures/MANIFEST.json` 列出了全部样本，
 * 把它们塞进 [MemoryStDataRoot] 就得到一棵和 SillyTavern `data/<user>/`
 * 同构的树。于是「目录命名规则对不对」这类问题能被真正测到 ——
 * 比如聊天子目录用的是**头像文件名**而不是角色显示名。
 */
class RepositoriesTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 从 fixtures 的清单搭一棵内存数据树。 */
    private fun fixtureRoot(): MemoryStDataRoot {
        val manifestBytes = javaClass.getResourceAsStream("/MANIFEST.json")?.use { it.readBytes() }
            ?: fail("找不到 fixtures/MANIFEST.json —— 请运行 `node tools/gen-fixtures.mjs`")
        val manifest = json.parseToJsonElement(manifestBytes.toString(Charsets.UTF_8)).jsonObject

        val files = LinkedHashMap<String, ByteArray>()
        for (element in manifest["files"]!!.jsonArray) {
            val path = element.jsonObject["path"]!!.jsonPrimitive.content
            // MANIFEST 里含 README 与清单自身，只要二进制/数据样本
            if (path.endsWith(".md") || path.endsWith("MANIFEST.json")) continue
            val bytes = javaClass.getResourceAsStream("/$path")?.use { it.readBytes() } ?: continue
            files[path] = bytes
        }
        return MemoryStDataRoot("fixtures", files)
    }

    private fun data() = SillyTavernData(fixtureRoot(), userHandle = "")

    // ------------------------------------------------------------ 角色卡

    @Test
    fun `列出全部角色卡`() {
        val entries = data().characters.list()

        val names = entries.map { it.fileName }.sorted()
        assertEquals(
            listOf(
                "charx-test.charx",
                "legacy-v1.json",
                "precedence-test.png",
                "seraphina-v2.png",
                "seraphina-v3.png",
            ),
            names,
        )
    }

    @Test
    fun `PNG 卡的头像就是文件本身`() {
        val entry = data().characters.load("seraphina-v3.png") ?: fail("应能加载")

        assertNotNull(entry.avatar)
        assertTrue(entry.avatar.size > 1000, "头像应是一张真实图片，实际 ${entry.avatar.size} 字节")
        assertTrue(entry.displayName.isNotEmpty())
    }

    @Test
    fun `JSON 与 CharX 卡没有内嵌头像字节`() {
        val entry = data().characters.load("legacy-v1.json") ?: fail("应能加载")
        assertNull(entry.avatar, "纯 JSON 卡没有图片")
    }

    @Test
    fun `坏卡不会让整个列表挂掉`() {
        val root = MemoryStDataRoot(
            initial = mapOf(
                "characters/good.png" to requireFixture("characters/seraphina-v3.png"),
                "characters/broken.png" to "这不是 PNG".toByteArray(),
            ),
        )
        val entries = CharacterRepository(root).list()

        assertEquals(listOf("good.png"), entries.map { it.fileName })
    }

    @Test
    fun `写回 PNG 卡后能再读出来`() {
        val root = fixtureRoot()
        val repo = CharacterRepository(root)
        val original = repo.load("seraphina-v3.png") ?: fail("应能加载")

        repo.savePng("seraphina-v3.png", original.card, original.avatar!!)

        val reloaded = repo.load("seraphina-v3.png") ?: fail("应能重新加载")
        assertEquals(original.card.toJsonObject(), reloaded.card.toJsonObject())
        assertTrue(reloaded.avatar!!.contentEquals(original.avatar), "图像字节不应被改动")
    }

    // ------------------------------------------------------------ 聊天

    @Test
    fun `聊天子目录用的是头像文件名而不是角色显示名`() {
        // fixtures 里聊天在 chats/default_Seraphina/，而角色卡是 seraphina-v3.png
        val repo = data().chats

        assertEquals(3, repo.list("default_Seraphina.png").size)
        assertTrue(
            repo.list("seraphina-v3.png").isEmpty(),
            "用显示名去找聊天目录会找不到 —— ST 用的是头像名",
        )
    }

    @Test
    fun `列出聊天时最近的在前`() {
        val chats = data().chats.list("default_Seraphina.png")

        assertEquals(
            listOf("fixture-integrity.jsonl", "fixture-imported.jsonl", "fixture-basic.jsonl"),
            chats.map { it.fileName },
        )
    }

    @Test
    fun `加载聊天并保留不变量`() {
        val chat = data().chats.load("default_Seraphina.png", "fixture-basic.jsonl") ?: fail("应能加载")

        assertEquals(3, chat.size)
        assertTrue(chat.swipeInconsistencies().isEmpty(), "fixture 应满足 swipe 不变量")
        assertNull(chat.header.integrity, "这个样本没有 integrity")
    }

    @Test
    fun `聊天写回后逐字段一致`() {
        val root = fixtureRoot()
        val repo = ChatRepository(root)
        val original = repo.load("default_Seraphina.png", "fixture-integrity.jsonl") ?: fail("应能加载")

        repo.save("default_Seraphina.png", "copy.jsonl", original)

        val copy = repo.load("default_Seraphina.png", "copy.jsonl") ?: fail("应能读回")
        assertEquals(original.header.toJsonObject(), copy.header.toJsonObject())
        assertEquals(original.messages.size, copy.messages.size)
        assertEquals(
            original.header.integrity,
            copy.header.integrity,
            "integrity 丢了的话，ST 那边会拒绝覆盖这个聊天文件",
        )
    }

    @Test
    fun `新聊天文件名会过滤非法字符`() {
        val repo = ChatRepository(MemoryStDataRoot())

        val name = repo.newChatFileName(
            "A/B:C",
            java.time.Instant.parse("2026-10-05T08:30:45.123Z"),
            java.time.ZoneId.of("Asia/Shanghai"),
        )
        assertEquals("A_B_C - 2026-10-05@16h30m45s123ms.jsonl", name)
    }

    @Test
    fun `给不存在的角色写聊天会自动建目录`() {
        val root = MemoryStDataRoot()
        val repo = ChatRepository(root)

        repo.save("newchar.png", "c.jsonl", app.silly.core.data.chat.Chat.empty())

        assertTrue(root.exists("chats/newchar/c.jsonl"))
    }

    // ------------------------------------------------------------ 世界书

    @Test
    fun `世界书列出与加载`() {
        val repo = data().worlds

        assertEquals(listOf("fixture-book"), repo.listNames())
        val book = repo.load("fixture-book") ?: fail("应能加载")
        assertEquals(4, book.size)
    }

    @Test
    fun `世界书写回后结构一致`() {
        val root = fixtureRoot()
        val repo = WorldInfoRepository(root)
        val book = repo.load("fixture-book") ?: fail("应能加载")

        repo.save(book, "copy")

        val copy = repo.load("copy") ?: fail("应能读回")
        assertEquals(book.toJsonObject(), copy.toJsonObject())
    }

    // ------------------------------------------------------------ 预设

    @Test
    fun `按类型列预设`() {
        val repo = data().presets

        val sysprompts = repo.list(PresetKind.SYSPROMPT)
        assertTrue(sysprompts.size >= 3, "应有 3 个 sysprompt 样本，实际 ${sysprompts.size}")
        assertTrue(sysprompts.all { it is SystemPrompt })

        assertEquals(sysprompts.size, sysprompts.distinctBy { it.fileName }.size)
    }

    @Test
    fun `四种预设都能列出`() {
        for (kind in PresetKind.entries) {
            val presets = data().presets.list(kind)
            assertTrue(presets.isNotEmpty(), "$kind 应有样本")
        }
    }

    // ------------------------------------------------------------ 数据根与用户目录

    @Test
    fun `userHandle 会折进相对路径`() {
        val inner = MemoryStDataRoot(
            initial = mapOf("default-user/characters/c.png" to requireFixture("characters/seraphina-v3.png")),
        )
        val data = SillyTavernData(inner, userHandle = "default-user")

        assertEquals(listOf("c.png"), data.characters.list().map { it.fileName })
        assertTrue(data.looksLikeStData())
    }

    @Test
    fun `userHandle 为空时直接看根目录`() {
        val data = SillyTavernData(fixtureRoot(), userHandle = "")
        assertTrue(data.looksLikeStData())
        assertTrue(data.characters.list().isNotEmpty())
    }

    @Test
    fun `不像 ST 数据的目录会被识别出来`() {
        val empty = SillyTavernData(MemoryStDataRoot(), userHandle = "")
        assertTrue(!empty.looksLikeStData())
        assertTrue(empty.characters.list().isEmpty())
    }

    // ------------------------------------------------------------ 内存数据根自身

    @Test
    fun `MemoryStDataRoot 的目录语义正确`() {
        val root = MemoryStDataRoot(
            initial = mapOf(
                "a/b.txt" to byteArrayOf(1),
                "a/c.txt" to byteArrayOf(2),
                "a/d/e.txt" to byteArrayOf(3),
                "top.txt" to byteArrayOf(4),
            ),
        )

        assertTrue(root.isDirectory("a"))
        assertTrue(root.isFile("a/b.txt"))
        assertTrue(root.isFile("top.txt"))

        assertEquals(listOf("b.txt", "c.txt", "d"), root.list("a"))
        assertEquals(listOf("a", "top.txt"), root.list(""))
        assertEquals(listOf("b.txt", "c.txt"), root.listFiles("a", setOf("txt")))

        root.delete("a")
        assertTrue(!root.exists("a/b.txt"))
        assertTrue(!root.exists("a/d/e.txt"))
        assertTrue(root.exists("top.txt"))
    }

    @Test
    fun `listFiles 的大小写与扩展名过滤`() {
        val root = MemoryStDataRoot(
            initial = mapOf(
                "d/A.PNG" to byteArrayOf(1),
                "d/b.json" to byteArrayOf(2),
                "d/c.txt" to byteArrayOf(3),
            ),
        )

        assertEquals(listOf("A.PNG"), root.listFiles("d", setOf("png")))
        assertEquals(listOf("A.PNG"), root.listFiles("d", setOf("PNG")))
        assertEquals(listOf("b.json"), root.listFiles("d", setOf("json")))
    }

    private fun requireFixture(path: String): ByteArray =
        javaClass.getResourceAsStream("/$path")?.use { it.readBytes() }
            ?: fail("找不到 fixture: $path")
}

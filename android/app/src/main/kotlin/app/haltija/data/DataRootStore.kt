package app.haltija.data

import android.content.Context
import android.net.Uri
import app.haltija.core.data.store.SillyTavernData
import app.haltija.core.data.store.StDataRoot
import java.io.File

/**
 * 数据根的选择与持久化。
 *
 * 两种来源：
 *  - **应用私有目录**（默认）：快，但别的 App 看不见，卸载即丢；
 *  - **SAF 选中的目录**：可以把数据根指到桌面 ST 的 `data/default-user/`，
 *    配合 Syncthing 就能手机与电脑共用同一份角色卡与聊天。
 *
 * SAF 授权必须 `takePersistableUriPermission`，否则重启后就失效。
 */
class DataRootStore(private val context: Context) {

    private val prefs = context.getSharedPreferences("haltija_dataroot", Context.MODE_PRIVATE)

    /** 当前生效的数据根。 */
    fun current(): StDataRoot {
        val uriString = prefs.getString(KEY_SAF_URI, null)
        if (uriString != null) {
            val uri = runCatching { Uri.parse(uriString) }.getOrNull()
            if (SafStDataRoot.isValidTreeUri(uri)) {
                return SafStDataRoot(context, uri!!)
            }
        }
        return privateRoot()
    }

    /** 默认的私有目录数据根。 */
    fun privateRoot(): StDataRoot = JavaStDataRoot(File(context.filesDir, "st-data"))

    /** 记住用户选中的 SAF 目录，并申请长期访问权限。 */
    fun useSafTree(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        prefs.edit().putString(KEY_SAF_URI, uri.toString()).apply()
    }

    /** 回到应用私有目录。 */
    fun usePrivate() {
        prefs.edit().remove(KEY_SAF_URI).apply()
    }

    fun selectionKey(): String = prefs.getString(KEY_SAF_URI, null) ?: "private"

    /** 用当前数据根开一个门面。用户目录这一层交给调用方决定（选的是 data/ 还是 data/default-user/）。 */
    fun open(): SillyTavernData = SillyTavernData(current(), userHandle = detectUserHandle())

    /**
     * 推断用户目录层级。
     *
     * 用户可能选 `data/`（里面有 `default-user/`），也可能直接选 `data/default-user/`。
     * 两者都支持会省掉一次「你选错了」的来回。
     */
    private fun detectUserHandle(): String {
        val root = current()
        if (root.isDirectory(DEFAULT_USER)) return DEFAULT_USER
        return ""
    }

    companion object {
        private const val KEY_SAF_URI = "saf_tree_uri"
        private const val DEFAULT_USER = "default-user"
    }
}

package app.haltija.data

import android.content.Context

/** 记住每个数据目录、每个角色最近打开的会话。 */
class SessionSelectionStore(context: Context) {
    private val prefs = context.getSharedPreferences("haltija_sessions", Context.MODE_PRIVATE)

    fun character(rootKey: String): String? = prefs.getString("character:$rootKey", null)
    fun chat(rootKey: String, characterFile: String): String? = prefs.getString("chat:$rootKey:$characterFile", null)

    fun save(rootKey: String, characterFile: String, chatFile: String) {
        prefs.edit()
            .putString("character:$rootKey", characterFile)
            .putString("chat:$rootKey:$characterFile", chatFile)
            .apply()
    }
}

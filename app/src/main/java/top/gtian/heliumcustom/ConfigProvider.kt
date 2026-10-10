package top.gtian.heliumcustom

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * 跨 UID 配置投递：Helium 进程（不同 UID）通过 ContentResolver.call 读取本模块配置。
 *
 * 直接读 SharedPreferences 文件在严格 SELinux ROM 上会被拦截
 * （见 HideRecent 日志 "all prefs channels failed"），ContentProvider 走 binder IPC，
 * 对 exported 组件稳定可用，不受文件权限/SELinux 跨域读限制。
 */
class ConfigProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "top.gtian.heliumcustom.config"
        const val PATH = "prefs"
        const val METHOD_GET = "get"

        fun uri(): Uri = Uri.parse("content://$AUTHORITY/$PATH")
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_GET) return null
        val ctx = context ?: return null
        val prefs = ctx.getSharedPreferences(ConfigActivity.PREFS_NAME, Context.MODE_PRIVATE)
        return Bundle().apply {
            putBoolean(
                ConfigActivity.KEY_FULLSCREEN,
                prefs.getBoolean(ConfigActivity.KEY_FULLSCREEN, false)
            )
            putString(
                ConfigActivity.KEY_TARGET_URL,
                prefs.getString(ConfigActivity.KEY_TARGET_URL, ConfigActivity.DEFAULT_URL)
            )
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = 0
}

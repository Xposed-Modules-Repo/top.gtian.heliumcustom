package top.gtian.heliumcustom

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import android.widget.TextView

/**
 * 配置 Activity — 用户设置目标 URL 和启用开关。
 * 配置写入 world-readable SharedPreferences，供 MainHook 通过 XSharedPreferences 读取。
 */
class ConfigActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "custom_ntp_config"
        const val KEY_ENABLED = "enabled"
        const val KEY_TARGET_URL = "target_url"
        const val KEY_FULLSCREEN = "fullscreen"
        const val DEFAULT_URL = "chrome-native://bookmarks/folder/10397"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)

        val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val enableSwitch = findViewById<MaterialSwitch>(R.id.enableSwitch)
        val fullscreenSwitch = findViewById<MaterialSwitch>(R.id.fullscreenSwitch)
        val urlInput = findViewById<EditText>(R.id.urlInput)
        val saveButton = findViewById<Button>(R.id.saveButton)
        val statusText = findViewById<TextView>(R.id.statusText)

        // Load current values
        val enabled = prefs.getBoolean(KEY_ENABLED, true)
        val fullscreen = prefs.getBoolean(KEY_FULLSCREEN, true)
        val url = prefs.getString(KEY_TARGET_URL, DEFAULT_URL)
        enableSwitch.isChecked = enabled
        fullscreenSwitch.isChecked = fullscreen
        urlInput.setText(url)
        updateStatus(statusText, enabled, url)

        saveButton.setOnClickListener {
            var targetUrl = urlInput.text.toString().trim()
            if (targetUrl.isEmpty()) {
                Toast.makeText(this, "URL 不能为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Auto-add https:// only for plain web URLs (preserve chrome-native://, chrome://, about:, etc.)
            if (!targetUrl.startsWith("http://", true) && !targetUrl.startsWith("https://", true)
                && !targetUrl.startsWith("chrome-native://", true)
                && !targetUrl.startsWith("chrome://", true)
                && !targetUrl.startsWith("about:", true)
                && !targetUrl.startsWith("file://", true)
                && !targetUrl.startsWith("content://", true)) {
                targetUrl = "https://$targetUrl"
                urlInput.setText(targetUrl)
            }

            prefs.edit()
                .putBoolean(KEY_ENABLED, enableSwitch.isChecked)
                .putBoolean(KEY_FULLSCREEN, fullscreenSwitch.isChecked)
                .putString(KEY_TARGET_URL, targetUrl)
                .apply()

            // Make world-readable so Xposed can read it (legacy but works on most setups)
            try {
                val prefsDir = applicationInfo.dataDir + "/shared_prefs"
                val prefsFile = java.io.File(prefsDir, "$PREFS_NAME.xml")
                prefsFile.setReadable(true, false)
                java.io.File(prefsDir).setReadable(true, false)
                java.io.File(prefsDir).setExecutable(true, false)
            } catch (e: Exception) {
                // ignore
            }

            updateStatus(statusText, enableSwitch.isChecked, targetUrl)
            Toast.makeText(this, R.string.saved_toast, Toast.LENGTH_LONG).show()
        }
    }

    private fun updateStatus(text: TextView, enabled: Boolean, url: String?) {
        if (enabled) {
            text.text = getString(R.string.status_active, url ?: DEFAULT_URL)
        } else {
            text.text = getString(R.string.status_inactive)
        }
    }
}

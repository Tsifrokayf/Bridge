package app.bridge

import android.app.Activity
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast

/**
 * Android only lets the foreground app read the clipboard, so this invisible activity
 * comes to the foreground, reads it, sends it to the Mac and closes.
 */
class SendClipboardActivity : Activity() {
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        val m = Bridge.get(this)
        val item = getSystemService(ClipboardManager::class.java).primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val imageUri = item?.uri?.takeIf { contentResolver.getType(it)?.startsWith("image/") == true }
        val text = if (imageUri == null) item?.coerceToText(this)?.toString() else null
        when {
            imageUri != null && m.isConnected -> {
                val out = m.openOutgoing(imageUri)
                if (out == null) {
                    Toast.makeText(this, "Не удалось прочитать картинку", Toast.LENGTH_SHORT).show()
                } else {
                    m.runBackground { m.sendOutgoingBlocking(out, kind = "clipimg", showProgress = false) }
                    Toast.makeText(this, "Картинка отправлена на Mac", Toast.LENGTH_SHORT).show()
                }
            }
            !m.isConnected -> Toast.makeText(this, "Mac не подключён", Toast.LENGTH_SHORT).show()
            text.isNullOrEmpty() -> Toast.makeText(this, "Буфер обмена пуст", Toast.LENGTH_SHORT).show()
            else -> {
                m.sendClipboard(text)
                Toast.makeText(this, "Буфер отправлен на Mac", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }
}

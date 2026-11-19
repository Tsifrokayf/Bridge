package app.bridge

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/** Target of the system "Share" menu: sends files (or shared text) to the Mac. */
class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        finish()
    }

    @Suppress("DEPRECATION")
    private fun uris(i: Intent): List<Uri> = when (i.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri,
        )
        Intent.ACTION_SEND_MULTIPLE ->
            (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)) ?: emptyList()
        else -> emptyList()
    }

    private fun handle(i: Intent) {
        val m = Bridge.get(this)
        if (!m.isConnected) {
            Toast.makeText(this, "Mac не подключён", Toast.LENGTH_LONG).show()
            return
        }
        val list = uris(i)
        if (list.isEmpty()) {
            val text = i.getStringExtra(Intent.EXTRA_TEXT)
            val trimmed = text?.trim().orEmpty()
            if (Regex("^https?://\\S+$").matches(trimmed)) {
                // Handoff: a shared link opens on the Mac.
                m.sendJson(org.json.JSONObject().put("t", "open_url").put("url", trimmed))
                Toast.makeText(this, "Ссылка отправлена на Mac", Toast.LENGTH_SHORT).show()
                return
            }
            if (!text.isNullOrEmpty()) {
                m.sendClipboard(text)
                Toast.makeText(this, "Текст отправлен в буфер Mac", Toast.LENGTH_SHORT).show()
            }
            return
        }
        // The URI permission ends with this activity, so descriptors are opened right now.
        val files = list.mapNotNull { m.openOutgoing(it) }
        if (files.isEmpty()) {
            Toast.makeText(this, "Не удалось открыть файл", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Отправка на Mac: ${files.size}", Toast.LENGTH_SHORT).show()
        m.sendFiles(files)
    }
}

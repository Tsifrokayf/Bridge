package app.bridge

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast

/**
 * "Take a photo on the phone" requested from the Mac (like Continuity Camera): opens the camera, and the picture is sent
 * to the Mac as soon as it is taken. The picture also stays in the phone's gallery.
 */
class CameraRequestActivity : Activity() {
    private var target: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) { target = savedInstanceState.getParcelable("target"); return }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Bridge_" + System.currentTimeMillis() + ".jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) { finish(); return }
        target = uri
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivityForResult(intent, 1)
        } catch (e: Exception) {
            Toast.makeText(this, "На телефоне нет приложения камеры", Toast.LENGTH_SHORT).show()
            runCatching { contentResolver.delete(uri, null, null) }
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable("target", target)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = target
        if (resultCode == RESULT_OK && uri != null) {
            val m = Bridge.get(this)
            val out = m.openOutgoing(uri)
            if (out != null && m.isConnected) {
                m.runBackground { m.sendOutgoingBlocking(out, kind = "camera", showProgress = false) }
                Toast.makeText(this, "Снимок отправлен на Mac", Toast.LENGTH_SHORT).show()
            } else {
                out?.pfd?.close()
                Toast.makeText(this, "Mac не подключён — снимок остался в галерее", Toast.LENGTH_LONG).show()
            }
        } else if (uri != null) {
            runCatching { contentResolver.delete(uri, null, null) }   // cancelled: drop the empty entry
        }
        finish()
    }
}

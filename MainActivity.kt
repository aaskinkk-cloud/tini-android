package app.tini.player

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream

/**
 * Tını arayüzünü bir WebView içinde gösterir.
 * Şarkılar MediaStore'dan okunur ve WebView'e https://appassets.androidplatform.net/media/<id>
 * adresi üzerinden (Range destekli) sunulur. Dosyalar kopyalanmaz.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private val main = Handler(Looper.getMainLooper())
    private var pendingChange: Runnable? = null
    private val host = "appassets.androidplatform.net"

    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            pendingChange?.let { main.removeCallbacks(it) }
            val r = Runnable { emit("changed", "true") }
            pendingChange = r
            main.postDelayed(r, 1500)
        }
    }

    private fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasAudioPermission(): Boolean =
        checkSelfPermission(audioPermission()) == PackageManager.PERMISSION_GRANTED

    /** Sayfadaki window.nativeBridgeEvent(type, value) işlevini çağırır. Ana iş parçacığından çağrılmalı. */
    private fun emit(type: String, value: String) {
        if (!::web.isInitialized) return
        web.evaluateJavascript(
            "window.nativeBridgeEvent&&window.nativeBridgeEvent('$type',$value)",
            null
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#0d1118"))
        setContentView(web)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.allowFileAccess = false
        s.allowContentAccess = false

        web.addJavascriptInterface(Bridge(), "Android")

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val u = request.url
                if (u.host != host) return null
                val seg = u.pathSegments
                if (seg.size == 2 && seg[0] == "media") return serveAudio(seg[1].toLongOrNull(), request)
                if (seg.size == 2 && seg[0] == "art") return serveArt(seg[1].toLongOrNull())
                return loader.shouldInterceptRequest(u)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == host) return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                } catch (e: Exception) {
                    // açacak uygulama yoksa sessizce geç
                }
                return true
            }
        }

        contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer
        )

        web.loadUrl("https://$host/assets/www/index.html")
    }

    override fun onResume() {
        super.onResume()
        emit("resume", "true")
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        web.evaluateJavascript("(window.nativeBack?window.nativeBack():false)") { r ->
            if (r != "true") moveTaskToBack(true)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        emit("permission", hasAudioPermission().toString())
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(observer)
        pendingChange?.let { main.removeCallbacks(it) }
        stopService(Intent(this, PlayService::class.java))
        web.destroy()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------
    // Ses ve kapak sunucusu
    // ---------------------------------------------------------------------------------------

    private fun empty(): InputStream = ByteArrayInputStream(ByteArray(0))

    private fun notFound(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), empty())

    private fun serveAudio(id: Long?, req: WebResourceRequest): WebResourceResponse {
        if (id == null || !hasAudioPermission()) return notFound()
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
        val afd = try {
            contentResolver.openAssetFileDescriptor(uri, "r")
        } catch (e: Exception) {
            null
        }
        if (afd == null) return notFound()

        val total = afd.length
        val mime = contentResolver.getType(uri) ?: "audio/mpeg"
        val rangeHeader = req.requestHeaders["Range"] ?: req.requestHeaders["range"]

        var start = 0L
        var end = if (total > 0) total - 1 else -1L
        var partial = false

        if (rangeHeader != null && total > 0) {
            val m = Regex("bytes=(\\d*)-(\\d*)").find(rangeHeader)
            if (m != null) {
                val a = m.groupValues[1]
                val b = m.groupValues[2]
                if (a.isNotEmpty()) {
                    start = a.toLong()
                    if (b.isNotEmpty()) end = minOf(b.toLong(), total - 1)
                } else if (b.isNotEmpty()) {
                    start = maxOf(0L, total - b.toLong())
                }
                partial = true
            }
        }

        if (total > 0 && start >= total) {
            afd.close()
            return WebResourceResponse(
                mime, null, 416, "Range Not Satisfiable",
                mapOf("Content-Range" to "bytes */$total"), empty()
            )
        }

        val raw = afd.createInputStream()
        if (start > 0) skipFully(raw, start)
        val length = if (end >= 0) end - start + 1 else -1L
        val stream: InputStream = if (length >= 0) LimitedInputStream(raw, length) else raw

        val headers = HashMap<String, String>()
        headers["Accept-Ranges"] = "bytes"
        if (length >= 0) headers["Content-Length"] = length.toString()

        if (partial && total > 0) {
            headers["Content-Range"] = "bytes $start-$end/$total"
            return WebResourceResponse(mime, null, 206, "Partial Content", headers, stream)
        }
        return WebResourceResponse(mime, null, 200, "OK", headers, stream)
    }

    private fun skipFully(s: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val k = s.skip(left)
            if (k <= 0) {
                if (s.read() < 0) break
                left--
            } else {
                left -= k
            }
        }
    }

    private fun serveArt(albumId: Long?): WebResourceResponse {
        if (albumId == null || albumId <= 0 || Build.VERSION.SDK_INT < 29 || !hasAudioPermission()) {
            return notFound()
        }
        return try {
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId)
            val bmp = contentResolver.loadThumbnail(uri, Size(512, 512), null)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
            WebResourceResponse(
                "image/jpeg", null, 200, "OK",
                mapOf("Cache-Control" to "max-age=86400"),
                ByteArrayInputStream(out.toByteArray())
            )
        } catch (e: Exception) {
            notFound()
        }
    }

    private class LimitedInputStream(inp: InputStream, private var left: Long) : FilterInputStream(inp) {
        override fun read(): Int {
            if (left <= 0) return -1
            val r = super.read()
            if (r >= 0) left--
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val r = super.read(b, off, minOf(len.toLong(), left).toInt())
            if (r > 0) left -= r
            return r
        }

        override fun skip(n: Long): Long {
            val k = super.skip(minOf(n, left))
            if (k > 0) left -= k
            return k
        }

        override fun available(): Int = minOf(super.available().toLong(), left).toInt()
    }

    // ---------------------------------------------------------------------------------------
    // JavaScript köprüsü (sayfada window.Android olarak görünür)
    // ---------------------------------------------------------------------------------------

    inner class Bridge {

        @JavascriptInterface
        fun hasPermission(): Boolean = hasAudioPermission()

        @JavascriptInterface
        fun requestPermission() {
            runOnUiThread {
                val list = arrayListOf(audioPermission())
                if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
                requestPermissions(list.toTypedArray(), 7)
            }
        }

        /** Telefondaki müzikleri en yeni eklenen başta olacak şekilde JSON olarak döndürür. */
        @JavascriptInterface
        fun scan(): String {
            val arr = JSONArray()
            if (!hasAudioPermission()) return arr.toString()

            val a = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val proj = arrayOf(
                MediaStore.Audio.Media._ID,          // 0
                MediaStore.Audio.Media.TITLE,        // 1
                MediaStore.Audio.Media.ARTIST,       // 2
                MediaStore.Audio.Media.ALBUM,        // 3
                MediaStore.Audio.Media.ALBUM_ID,     // 4
                MediaStore.Audio.Media.DURATION,     // 5
                MediaStore.Audio.Media.SIZE,         // 6
                MediaStore.Audio.Media.DATE_ADDED,   // 7
                MediaStore.Audio.Media.TRACK,        // 8
                MediaStore.Audio.Media.YEAR,         // 9
                MediaStore.Audio.Media.MIME_TYPE,    // 10
                MediaStore.Audio.Media.DISPLAY_NAME  // 11
            )
            val sel = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 20000"
            val order = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

            try {
                contentResolver.query(a, proj, sel, null, order)?.use { c ->
                    while (c.moveToNext()) {
                        val o = JSONObject()
                        o.put("id", c.getLong(0))
                        o.put("title", c.getString(1) ?: "")
                        o.put("artist", c.getString(2) ?: "")
                        o.put("album", c.getString(3) ?: "")
                        o.put("albumId", c.getLong(4))
                        o.put("duration", c.getLong(5) / 1000.0)
                        o.put("size", c.getLong(6))
                        o.put("added", c.getLong(7) * 1000L)
                        o.put("track", c.getInt(8))
                        o.put("year", c.getInt(9))
                        o.put("mime", c.getString(10) ?: "")
                        o.put("name", c.getString(11) ?: "")
                        arr.put(o)
                    }
                }
            } catch (e: Exception) {
                // izin ya da sorgu hatasında boş liste döner
            }
            return arr.toString()
        }

        /** Çalma durumunu bildirir; çalarken bildirim ve ön plan hizmeti sesi canlı tutar. */
        @JavascriptInterface
        fun setPlaying(playing: Boolean, title: String, artist: String) {
            PlayService.update(this@MainActivity, playing, title, artist)
        }
    }
}

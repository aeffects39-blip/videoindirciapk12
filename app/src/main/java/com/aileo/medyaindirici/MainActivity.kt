package com.aileo.medyaindirici

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.graphics.Color
import android.os.Bundle
import android.provider.MediaStore
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.WebView
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val io = Executors.newCachedThreadPool()
    @Volatile private var hazir = false
    private val aktif = AtomicBoolean(false)
    private val linkRegex = Regex("^https?://\\S+$")
    private val kodRegex = Regex("[A-Za-z0-9_\\-+/.,\\[\\]=*]+")

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")

        io.execute {
            try {
                YoutubeDL.getInstance().init(application)
                FFmpeg.getInstance().init(application)
                try { // yt-dlp'yi güncel tut (siteler sık değişir); internet yoksa sessizce geç
                    YoutubeDL.getInstance().updateYoutubeDL(application, YoutubeDL.UpdateChannel._STABLE)
                } catch (e: Throwable) { }
                hazir = true
            } catch (e: Throwable) {
                js("onHata(${JSONObject.quote("Başlatılamadı: " + e.message)})")
            }
        }
    }

    private fun js(kod: String) = runOnUiThread { web.evaluateJavascript(kod, null) }

    private fun hataMetni(e: Throwable): String {
        val m = e.message ?: "Bilinmeyen hata"
        return m.lines().lastOrNull { it.contains("ERROR") }?.trim() ?: m.take(300)
    }

    private fun formatTuru(detay: String): String {
        val d = detay.lowercase()
        return when {
            "audio only" in d -> "ses"
            "video only" in d -> "video"
            "images" in d || "storyboard" in d || "mhtml" in d -> "diger"
            else -> "birlesik"
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun formatlar(link: String) {
            io.execute {
                val sonuc = try { formatListesi(link.trim()) } catch (e: Throwable) {
                    JSONObject().put("hata", hataMetni(e))
                }
                js("onFormatlar($sonuc)")
            }
        }

        @JavascriptInterface
        fun indir(link: String, mod: String, kod: String, ftur: String) {
            if (!aktif.compareAndSet(false, true)) {
                js("onHata('Devam eden bir indirme var, bitmesini bekleyin.')")
                return
            }
            io.execute {
                try {
                    indirmeyiYap(link.trim(), mod, kod.trim(), ftur)
                } catch (e: Throwable) {
                    js("onHata(${JSONObject.quote(hataMetni(e))})")
                } finally {
                    aktif.set(false)
                    runOnUiThread { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                }
            }
        }
    }

    private fun formatListesi(link: String): JSONObject {
        if (!hazir) throw Exception("Uygulama hazırlanıyor, birkaç saniye sonra tekrar deneyin.")
        if (!linkRegex.matches(link)) throw Exception("Geçerli bir http(s) linki girin.")
        val req = YoutubeDLRequest(link)
        req.addOption("--no-playlist")
        req.addOption("-F")
        val cikti = YoutubeDL.getInstance().execute(req).out
        val arr = JSONArray()
        var basladi = false
        for (satir in cikti.lines()) {
            if (satir.contains("ID") && satir.contains("EXT")) { basladi = true; continue }
            if (!basladi || satir.isBlank()) continue
            val p = satir.trim().split(Regex("\\s+"))
            if (!p[0][0].isLetterOrDigit()) continue // ayırıcı çizgileri atla
            val detay = p.drop(1).joinToString(" ")
            arr.put(JSONObject().put("kod", p[0]).put("detay", detay).put("tur", formatTuru(detay)))
        }
        if (arr.length() == 0) throw Exception("Format listesi alınamadı veya link geçersiz.")
        return JSONObject().put("formatlar", arr)
    }

    private fun indirmeyiYap(link: String, mod: String, kod: String, ftur: String) {
        if (!hazir) throw Exception("Uygulama hazırlanıyor, birkaç saniye sonra tekrar deneyin.")
        if (!linkRegex.matches(link)) throw Exception("Geçerli bir http(s) linki girin.")
        if (kod.isNotEmpty() && !kodRegex.matches(kod)) throw Exception("Geçersiz format kodu.")
        if (ftur == "diger") throw Exception("Bu satır indirilebilir bir format değil.")

        val dir = File(cacheDir, "dl_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val req = YoutubeDLRequest(link)
            req.addOption("--no-playlist")
            req.addOption("--windows-filenames")
            req.addOption("-o", "${dir.absolutePath}/%(title).100B.%(ext)s")
            when (mod) {
                "ses" -> {
                    if (ftur == "video") throw Exception("Bu satır sessiz bir video. 'audio only' satırına dokunun.")
                    if (kod.isNotEmpty()) req.addOption("-f", kod)
                    req.addOption("-x")
                    req.addOption("--audio-format", "mp3")
                }
                "video" -> {
                    if (kod.isEmpty()) throw Exception("Video format kodunu girin (Örn: 137).")
                    if (ftur == "ses" || ftur == "birlesik") throw Exception("Sessiz video için 'video only' satırına dokunun.")
                    req.addOption("-f", kod)
                }
                "birlesik" -> {
                    if (kod.isEmpty()) throw Exception("Video ve ses kodunu + ile yazın (Örn: 137+140).")
                    if (ftur == "ses") throw Exception("Birleşik indirme için bir video satırına dokunun.")
                    val f = if (ftur == "video") "$kod+bestaudio[ext=m4a]/$kod+bestaudio" else kod
                    req.addOption("-f", f)
                    req.addOption("--merge-output-format", "mp4")
                }
                else -> throw Exception("Geçersiz indirme türü.")
            }

            runOnUiThread { window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            YoutubeDL.getInstance().execute(req, "dl") { p, _, satir ->
                if (satir.contains("[Merger]") || satir.contains("[ExtractAudio]") || satir.contains("[VideoConvertor]")) {
                    js("onIlerleme(100, ${JSONObject.quote("Birleştiriliyor / dönüştürülüyor…")})")
                } else if (p >= 0f) {
                    val y = p.coerceIn(0f, 100f)
                    js("onIlerleme($y, ${JSONObject.quote("İndiriliyor…  %" + y.toInt())})")
                }
            }

            val dosya = dir.listFiles()
                ?.filter { it.isFile && it.extension !in listOf("part", "ytdl", "temp") }
                ?.maxByOrNull { it.lastModified() }
                ?: throw Exception("İndirilen dosya bulunamadı.")
            js("onIlerleme(100, 'Telefona kaydediliyor…')")
            publicKaydet(dosya)
            js("onBitti(${JSONObject.quote(dosya.name)})")
        } finally {
            dir.deleteRecursively()
        }
    }

    // Dosyayı telefonun "İndirilenler/MedyaIndirici" klasörüne kopyalar (Android 10+)
    private fun publicKaydet(f: File) {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, f.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/MedyaIndirici")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
            ?: throw Exception("Dosya kaydedilemedi.")
        contentResolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
    }
}

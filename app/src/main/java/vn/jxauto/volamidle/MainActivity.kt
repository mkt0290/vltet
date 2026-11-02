package vn.jxauto.volamidle

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Võ Lâm Idle — client Android.
 * - Mở trang game thật, tự nạp JX Auto, tự cập nhật JX Auto từ gist.
 * - Nghỉ màn: phủ màn đen + hạ độ sáng thấp nhất; màn hình vẫn "bật" nên game chạy như thường.
 * - Tự cập nhật app: đọc bản phát hành mới nhất trên GitHub Releases, tải APK, mở màn hình Cài đặt.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val GAME_URL = "https://volam.vinarpg.com/"
        const val GAME_HOST = "volam.vinarpg.com"
        const val GIST_BASE = "https://gist.githubusercontent.com/mkt0290/221eb8244768c606fb583eb6e4dd88c3/raw"
        // Repo GitHub CÔNG KHAI chứa bản phát hành APK (đổi nếu anh đặt tên repo khác)
        const val UPDATE_REPO = "mkt0290/VoLamIdle-Android"
        const val APK_NAME = "VoLamIdle.apk"
        const val ASSET_FILE = "jx-auto.user.js"
        const val CACHE_FILE = "jx-auto.cache.js"
        const val PREFS = "app"
        const val CHECK_EVERY_MS = 3L * 60 * 60 * 1000
    }

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var sleepLayer: FrameLayout
    private lateinit var sleepText: TextView
    private lateinit var moonBtn: TextView

    private var jxSrc = ""
    private var jxVer = "0"
    private var sleeping = false
    private val ui = Handler(Looper.getMainLooper())
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        fileCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        fileCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this)
        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        buildMoonButton()
        buildSleepLayer()
        setContentView(root)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        // Giữ ưu tiên cao cho trang game kể cả khi không nhìn thấy
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        // Cầu nối cho JX Auto: window.JXApp.sleep() / wake() / version() / checkUpdate()
        web.addJavascriptInterface(Bridge(), "JXApp")

        pickBest()

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val host = req.url.host ?: return false
                if (host == GAME_HOST || host.endsWith(".vinarpg.com")) return false
                try { startActivity(Intent(Intent.ACTION_VIEW, req.url)) } catch (_: Exception) {}
                return true
            }
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                injectJX()
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView, callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try { pickFile.launch(arrayOf("*/*")); true } catch (_: Exception) { fileCallback = null; false }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (sleeping) { wake(); return }
                AlertDialog.Builder(this@MainActivity)
                    .setMessage("Thoát game?")
                    .setPositiveButton("Thoát") { _, _ -> finish() }
                    .setNegativeButton("Ở lại", null)
                    .setNeutralButton("Nghỉ màn") { _, _ -> sleep() }
                    .show()
            }
        })

        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(GAME_URL)

        ui.postDelayed(object : Runnable {
            override fun run() {
                checkUpdate(false)
                checkAppUpdate(false)
                ui.postDelayed(this, CHECK_EVERY_MS)
            }
        }, 5000)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        web.destroy()
        super.onDestroy()
    }

    /* ================= Nghỉ màn ================= */

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildMoonButton() {
        moonBtn = TextView(this).apply {
            text = "☾"
            textSize = 18f
            setTextColor(Color.parseColor("#F6E7D8"))
            gravity = Gravity.CENTER
            alpha = 0.45f
            setBackgroundColor(Color.parseColor("#AA0D1512"))
            setOnClickListener { sleep() }
        }
        val lp = FrameLayout.LayoutParams(dp(34), dp(34), Gravity.BOTTOM or Gravity.START)
        lp.setMargins(dp(6), 0, 0, dp(70))
        root.addView(moonBtn, lp)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildSleepLayer() {
        sleepLayer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            isClickable = true   // chặn mọi chạm xuống game
        }
        sleepText = TextView(this).apply {
            setTextColor(Color.parseColor("#3A4A42"))   // chữ rất tối, đỡ hao pin và đỡ lưu ảnh màn
            textSize = 13f
            gravity = Gravity.CENTER
        }
        sleepLayer.addView(sleepText, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean { wake(); return true }
            override fun onDown(e: MotionEvent) = true
        })
        sleepLayer.setOnTouchListener { _, ev -> gd.onTouchEvent(ev); true }
        root.addView(sleepLayer, FrameLayout.LayoutParams(-1, -1))
    }

    private val sleepTick = object : Runnable {
        override fun run() {
            if (!sleeping) return
            val t = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            sleepText.text = "$t\nĐang treo game · chạm 2 lần để mở"
            // dời vị trí chữ mỗi phút để tránh lưu ảnh trên màn OLED
            val w = root.width.coerceAtLeast(1)
            val h = root.height.coerceAtLeast(1)
            sleepText.x = Random.nextInt(0, (w - dp(220)).coerceAtLeast(1)).toFloat()
            sleepText.y = Random.nextInt(dp(40), (h - dp(80)).coerceAtLeast(dp(41))).toFloat()
            ui.postDelayed(this, 60_000)
        }
    }

    private fun applyImmersive(on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val c = window.insetsController ?: return
            if (on) {
                c.hide(WindowInsets.Type.systemBars())
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else c.show(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (on)
                (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
            else View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    /** Màn đen + độ sáng thấp nhất. Màn hình vẫn bật nên game và JX Auto chạy bình thường. */
    fun sleep() {
        if (sleeping) return
        sleeping = true
        val lp = window.attributes
        lp.screenBrightness = 0.01f
        window.attributes = lp
        applyImmersive(true)
        sleepLayer.visibility = View.VISIBLE
        moonBtn.visibility = View.GONE
        ui.removeCallbacks(sleepTick)
        sleepTick.run()
    }

    fun wake() {
        if (!sleeping) return
        sleeping = false
        val lp = window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
        applyImmersive(false)
        sleepLayer.visibility = View.GONE
        moonBtn.visibility = View.VISIBLE
        ui.removeCallbacks(sleepTick)
    }

    inner class Bridge {
        @JavascriptInterface fun sleep() { ui.post { this@MainActivity.sleep() } }
        @JavascriptInterface fun wake() { ui.post { this@MainActivity.wake() } }
        @JavascriptInterface fun isSleeping(): Boolean = sleeping
        @JavascriptInterface fun version(): String = BuildConfig.VERSION_NAME
        @JavascriptInterface fun checkUpdate() { ui.post { checkUpdate(true); checkAppUpdate(true) } }
    }

    /* ================= JX Auto (từ gist) ================= */

    private fun verOf(src: String): String {
        Regex("@version\\s+([\\d.]+)").find(src)?.let { return it.groupValues[1] }
        Regex("const VER = '([\\d.]+)'").find(src)?.let { return it.groupValues[1] }
        return "0"
    }

    private fun cmpVer(a: String, b: String): Int {
        val pa = a.trimStart('v').split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.trimStart('v').split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
            if (d != 0) return if (d > 0) 1 else -1
        }
        return 0
    }

    private fun pickBest() {
        val bundled = try { assets.open(ASSET_FILE).bufferedReader().use { it.readText() } } catch (_: Exception) { "" }
        val cacheF = File(filesDir, CACHE_FILE)
        val cached = if (cacheF.exists()) cacheF.readText() else ""
        val vb = if (bundled.isNotEmpty()) verOf(bundled) else "0"
        val vc = if (cached.isNotEmpty()) verOf(cached) else "0"
        if (cached.isNotEmpty() && cmpVer(vc, vb) > 0) { jxSrc = cached; jxVer = vc } else { jxSrc = bundled; jxVer = vb }
    }

    private fun injectJX() { if (jxSrc.isNotEmpty()) web.evaluateJavascript(jxSrc, null) }

    private fun httpText(url: String, bust: Boolean = true): String {
        val u = if (bust) "$url?t=${System.currentTimeMillis()}" else url
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 20000
        c.useCaches = false
        c.setRequestProperty("User-Agent", "VoLamIdle-Android")
        try {
            if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    @Volatile private var checking = false
    private fun checkUpdate(manual: Boolean) {
        if (checking) return
        if (!manual && !getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("autoUpdate", true)) return
        checking = true
        thread {
            try {
                val remote = verOf(httpText("$GIST_BASE/jx-auto.meta.js"))
                if (cmpVer(remote, jxVer) <= 0) {
                    if (manual) ui.post { toast("JX Auto đang là bản mới nhất ($jxVer)") }
                    return@thread
                }
                val src = httpText("$GIST_BASE/jx-auto.user.js")
                if (!src.contains("==UserScript==") || src.length < 20000 || cmpVer(verOf(src), jxVer) <= 0)
                    throw RuntimeException("file tải về không hợp lệ")
                File(filesDir, CACHE_FILE).writeText(src)
                val old = jxVer
                ui.post {
                    pickBest()
                    AlertDialog.Builder(this)
                        .setTitle("JX Auto")
                        .setMessage("Đã tải JX Auto $jxVer (đang chạy $old).\nTải lại game để dùng bản mới?")
                        .setPositiveButton("Tải lại") { _, _ -> web.reload() }
                        .setNegativeButton("Để sau", null)
                        .show()
                }
            } catch (e: Exception) {
                if (manual) ui.post { toast("Không kiểm tra được JX Auto: ${e.message}") }
            } finally { checking = false }
        }
    }

    /* ================= Tự cập nhật app (GitHub Releases) ================= */

    @Volatile private var checkingApp = false
    private fun checkAppUpdate(manual: Boolean) {
        if (checkingApp) return
        checkingApp = true
        thread {
            try {
                val j = JSONObject(httpText("https://api.github.com/repos/$UPDATE_REPO/releases/latest", bust = false))
                val tag = j.optString("tag_name")
                if (tag.isEmpty() || cmpVer(tag, BuildConfig.VERSION_NAME) <= 0) {
                    if (manual) ui.post { toast("App đang là bản mới nhất (${BuildConfig.VERSION_NAME})") }
                    return@thread
                }
                val list = j.optJSONArray("assets")
                var apkUrl = ""
                if (list != null) for (i in 0 until list.length()) {
                    val a = list.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) { apkUrl = a.optString("browser_download_url"); break }
                }
                if (apkUrl.isEmpty()) throw RuntimeException("bản phát hành không có file APK")
                val note = j.optString("body").take(300)
                ui.post {
                    AlertDialog.Builder(this)
                        .setTitle("Có bản app mới ${tag.trimStart('v')}")
                        .setMessage((if (note.isNotBlank()) "$note\n\n" else "") +
                            "Đang dùng ${BuildConfig.VERSION_NAME}. Cập nhật ngay? Dữ liệu game giữ nguyên.")
                        .setPositiveButton("Cập nhật") { _, _ -> downloadAndInstall(apkUrl) }
                        .setNegativeButton("Để sau", null)
                        .show()
                }
            } catch (e: Exception) {
                if (manual) ui.post { toast("Không kiểm tra được bản app mới: ${e.message}") }
            } finally { checkingApp = false }
        }
    }

    private fun downloadAndInstall(url: String) {
        // Android 8+: app phải được phép "cài ứng dụng không rõ nguồn gốc"
        if (!packageManager.canRequestPackageInstalls()) {
            toast("Cho phép app cài bản cập nhật, rồi bấm Cập nhật lại")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        toast("Đang tải bản cập nhật…")
        thread {
            try {
                val dir = File(cacheDir, "update").apply { mkdirs() }
                val out = File(dir, APK_NAME)
                val c = URL(url).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = true
                c.connectTimeout = 15000
                c.readTimeout = 60000
                c.setRequestProperty("User-Agent", "VoLamIdle-Android")
                c.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
                c.disconnect()
                if (out.length() < 100_000) throw RuntimeException("file tải về quá nhỏ")
                ui.post {
                    val uri = FileProvider.getUriForFile(this, "$packageName.files", out)
                    val i = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(i)
                }
            } catch (e: Exception) {
                ui.post { toast("Tải bản cập nhật lỗi: ${e.message}") }
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

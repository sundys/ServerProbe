package com.serverprobe.manager.remote

import android.annotation.SuppressLint
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.serverprobe.manager.R
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ValueCallback
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

class RemoteActivity : androidx.fragment.app.FragmentActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorView: View
    private lateinit var flContainer: ViewGroup
    private lateinit var tvTitle: TextView
    private lateinit var link: Link

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var pendingWebPermission: PermissionRequest? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var desktopUa = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applySystemBarInsets(findViewById(R.id.rootRemote))

        val l = readLink(intent)
        if (l == null) {
            Toast.makeText(this, R.string.link_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        link = l

        tvTitle = findViewById(R.id.tvTitle)
        progressBar = findViewById(R.id.progressBar)
        errorView = findViewById(R.id.errorView)
        flContainer = findViewById(R.id.flContainer)
        findViewById<TextView>(R.id.tvSubtitle).text = link.host

        webView = WebView(this)
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        flContainer.addView(webView, 0)

        setupWeb()

        findViewById<View>(R.id.btnBack).setOnClickListener {
            if (webView.canGoBack()) webView.goBack() else finish()
        }
        findViewById<View>(R.id.btnReload).setOnClickListener { reload() }
        findViewById<View>(R.id.btnMore).setOnClickListener { showMenu(it) }
        errorView.findViewById<View>(R.id.btnRetry).setOnClickListener { reload() }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(link.url)
        }
        LinkStore(this).markOpened(link.id)
    }

    /** Android 15 强制边到边：顶栏/底栏避让系统状态栏与键盘 */
    private fun applySystemBarInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                    or WindowInsetsCompat.Type.displayCutout()
                    or WindowInsetsCompat.Type.ime(),
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun readLink(intent: Intent): Link? {
        val url = intent.getStringExtra(EXTRA_URL) ?: return null
        return Link(
            id = intent.getStringExtra(EXTRA_ID).orEmpty(),
            url = url,
            name = intent.getStringExtra(EXTRA_NAME).orEmpty(),
            version = intent.getStringExtra(EXTRA_VERSION).orEmpty(),
            host = intent.getStringExtra(EXTRA_HOST).orEmpty(),
            createdAt = 0L,
            lastOpenedAt = 0L
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = false
            allowContentAccess = true
            textZoom = 100
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.setBackgroundColor(0xFF17191E.toInt())

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                errorView.visibility = View.GONE
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                ensureViewport(view)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                val u = request.url
                return when (u.scheme?.lowercase()) {
                    "http", "https" -> false
                    else -> {
                        openExternal(u)
                        true
                    }
                }
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    showError(error.description?.toString() ?: getString(R.string.load_failed))
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: android.webkit.WebResourceResponse
            ) {
                if (request.isForMainFrame) {
                    showError("服务器返回 HTTP ${errorResponse.statusCode}")
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                progressBar.progress = newProgress
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { handleWebPermission(request) }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                // 上一次未完成的回调先释放，否则网页的文件输入框会"点了没反应"
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback

                requestStoragePermissionIfNeeded()

                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = resolveMimeType(params.acceptTypes)
                    if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                }
                val chooser = Intent.createChooser(intent, null)
                return try {
                    startActivityForResult(chooser, REQ_FILE_CHOOSER)
                    true
                } catch (_: ActivityNotFoundException) {
                    filePathCallback = null
                    callback.onReceiveValue(null)
                    Toast.makeText(this@RemoteActivity, R.string.no_file_manager, Toast.LENGTH_SHORT).show()
                    false
                }
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                flContainer.addView(
                    view, ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                customView = view
                customViewCallback = callback
            }

            override fun onHideCustomView() {
                customView?.let { flContainer.removeView(it) }
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                super.onHideCustomView()
            }
        }
    }

    /** 根据网页声明的 accept 类型选择选择器的 MIME 过滤 */
    private fun resolveMimeType(acceptTypes: Array<out String>?): String {
        val types = acceptTypes?.filter { it.isNotBlank() }.orEmpty()
        if (types.isEmpty()) return "*/*"
        val all = types.map { it.lowercase() }
        return when {
            all.all { it.startsWith("image/") || it == ".png" || it == ".jpg" || it == ".jpeg" || it == ".gif" || it == ".webp" } -> "image/*"
            all.all { it.startsWith("video/") || it == ".mp4" || it == ".mov" } -> "video/*"
            all.all { it.startsWith("audio/") || it == ".mp3" || it == ".wav" } -> "audio/*"
            else -> "*/*"
        }
    }

    /** 选择文件前按需请求媒体读取权限（拒绝也不影响系统选择器可用） */
    private fun requestStoragePermissionIfNeeded() {
        val perms = if (Build.VERSION.SDK_INT >= 33)
            arrayOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO
            )
        else
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_STORAGE)
        }
    }

    /** 解析选择结果，支持多选（clipData） */
    private fun extractPickedUris(resultCode: Int, data: Intent?): Array<Uri>? {
        if (resultCode != RESULT_OK || data == null) return null
        val clip = data.clipData
        if (clip != null && clip.itemCount > 0) {
            val list = ArrayList<Uri>(clip.itemCount)
            for (i in 0 until clip.itemCount) {
                val uri = clip.getItemAt(i).uri ?: continue
                list.add(uri)
            }
            if (list.isNotEmpty()) return list.toTypedArray()
        }
        return data.data?.let { arrayOf(it) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_FILE_CHOOSER) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val callback = filePathCallback
        filePathCallback = null
        callback?.onReceiveValue(extractPickedUris(resultCode, data))
    }

    private fun handleWebPermission(request: PermissionRequest) {
        val grants = ArrayList<String>()
        for (res in request.resources) {
            val appPerm = when (res) {
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> android.Manifest.permission.RECORD_AUDIO
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> android.Manifest.permission.CAMERA
                else -> null
            }
            if (appPerm == null) {
                grants.add(res)
                continue
            }
            if (checkSelfPermission(appPerm) == PackageManager.PERMISSION_GRANTED) {
                grants.add(res)
            } else {
                pendingWebPermission = request
                requestPermissions(arrayOf(appPerm), REQ_PERM)
                return
            }
        }
        request.grant(grants.toTypedArray())
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERM) return
        val pending = pendingWebPermission
        pendingWebPermission = null
        if (pending == null) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            pending.grant(pending.resources)
        } else {
            pending.deny()
            Toast.makeText(this, R.string.perm_denied, Toast.LENGTH_LONG).show()
        }
    }

    /** 页面缺少 viewport meta 时补一个，保证按手机屏宽自适应缩放 */
    private fun ensureViewport(view: WebView) {
        val js = "(function(){try{if(document.querySelector('meta[name=viewport]'))return;" +
            "var m=document.createElement('meta');m.setAttribute('name','viewport');" +
            "m.setAttribute('content','width=device-width, initial-scale=1');" +
            "(document.head||document.documentElement).appendChild(m);}catch(e){}})();"
        view.evaluateJavascript(js, null)
    }

    private fun showError(desc: String) {
        progressBar.visibility = View.GONE
        errorView.visibility = View.VISIBLE
        errorView.findViewById<TextView>(R.id.tvError).text = desc
    }

    private fun reload() {
        errorView.visibility = View.GONE
        if (webView.url.isNullOrEmpty()) webView.loadUrl(link.url) else webView.reload()
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, R.string.menu_copy)
        popup.menu.add(0, 2, 0, if (desktopUa) R.string.menu_mobile_ua else R.string.menu_desktop_ua)
        popup.menu.add(0, 3, 0, R.string.menu_open_browser)
        popup.menu.add(0, 4, 0, R.string.menu_close)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("zcode", webView.url ?: link.url))
                    Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
                }
                2 -> {
                    desktopUa = !desktopUa
                    webView.settings.userAgentString = if (desktopUa)
                        DESKTOP_UA
                    else
                        WebSettings.getDefaultUserAgent(this)
                    reload()
                }
                3 -> openExternal(Uri.parse(webView.url ?: link.url))
                4 -> finish()
            }
            true
        }
        popup.show()
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onBackPressed() {
        when {
            customView != null -> (webView.webChromeClient as? WebChromeClient)?.onHideCustomView()
            webView.canGoBack() -> webView.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            flContainer.removeAllViews()
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"
        const val EXTRA_VERSION = "version"
        const val EXTRA_HOST = "host"
        private const val REQ_PERM = 4001
        private const val REQ_FILE_CHOOSER = 5002
        private const val REQ_STORAGE = 5003

        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"

        fun intent(context: Context, link: Link): Intent =
            Intent(context, RemoteActivity::class.java)
                .putExtra(EXTRA_ID, link.id)
                .putExtra(EXTRA_URL, link.url)
                .putExtra(EXTRA_NAME, link.name)
                .putExtra(EXTRA_VERSION, link.version)
                .putExtra(EXTRA_HOST, link.host)
    }
}

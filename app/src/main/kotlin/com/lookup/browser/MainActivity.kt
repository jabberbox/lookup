package com.lookup.browser

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var scraper: WebView          // sold search; also reads one listing's page
    private lateinit var scraperActive: WebView    // listed search, loaded at the same time
    private lateinit var homePane: View
    private lateinit var resultsPane: View
    private lateinit var bottomBar: View
    private lateinit var titleView: TextView
    private lateinit var queryField: EditText
    private lateinit var summary: TextView
    private lateinit var btnLeft: View
    private lateinit var btnRight: View
    private lateinit var btnScan: View
    private lateinit var scanPane: View
    private lateinit var previewView: PreviewView
    private var cameraProvider: ProcessCameraProvider? = null
    private var scanHandled = false
    private lateinit var labelLeft: TextView
    private lateinit var labelRight: TextView
    private lateinit var resultList: ListView
    private lateinit var historyList: ListView
    private var resultsTitle = "Lookup"

    // One listing's pictures. The item page is read in the hidden scraper; only its picture addresses are used.
    private lateinit var detailPane: View
    private lateinit var detailImage: android.widget.ImageView
    private lateinit var detailPrice: TextView
    private lateinit var detailTitle: TextView
    private lateinit var detailCondition: TextView
    private var detailId = 0
    private var detailImages: List<String> = emptyList()
    private var detailIndex = 0
    private var detailItem: Listing? = null
    private val imageThread = java.util.concurrent.Executors.newSingleThreadExecutor()

    // Photo search: true from the IMAGE tap until we leave the eBay page; the results page is turned into our own
    // page by itself once a photo has been handed to eBay.
    private var imageFlow = false
    private var imageResultsExpected = false

    // Lookup's own results page: what a thing sold for and what it is listed for, nothing else.
    private var searchBase: String? = null
    private var searchId = 0
    private var soldPage: Listings.Page? = null
    private var activePage: Listings.Page? = null
    private var showSold = true
    private var blockedNote: String? = null

    // Holds the pending callback from WebView while the system file picker is open
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris: Array<Uri>? = if (result.resultCode == RESULT_OK) {
            result.data?.let { data ->
                when {
                    data.clipData != null ->
                        Array(data.clipData!!.itemCount) { i -> data.clipData!!.getItemAt(i).uri }
                    data.data != null -> arrayOf(data.data!!)
                    else -> null
                }
            }
        } else null
        if (imageFlow && uris != null) imageResultsExpected = true
        fileChooserCallback?.onReceiveValue(uris)
        fileChooserCallback = null
    }

    // ──────────────────────────────────────────────────────────────────────────
    // ALLOWLIST
    //
    // Each string covers that bare domain AND every subdomain beneath it.
    //   "ebay.com"       →  ebay.com, www.ebay.com, signin.ebay.com, …
    //   "ebaystatic.com" →  ir.ebaystatic.com, q.ebaystatic.com, …
    //
    // To add a site:    append its apex domain (and any separate CDN domain).
    // To remove a site: delete its entries.
    // Recompile after any change — there is no runtime UI to edit this list.
    //
    // data:, about:, and blob: URIs are always allowed (inline/page-internal).
    // Everything else — including google, social, and analytics domains — is blocked.
    // ──────────────────────────────────────────────────────────────────────────
    // The Chrome major version the WebView announces itself as.
    //
    // The LP3's system WebView is Chrome 113 (May 2023) and can not be updated, and eBay's edge network now
    // answers "Sorry, something went wrong on our end" to a browser that old. The engine is still Chrome 113; only
    // the label changes, in Chrome's own reduced user-agent form ("Chrome/<major>.0.0.0"). Raise this to the
    // current stable Chrome (https://versionhistory.googleapis.com/v1/chrome/platforms/android/channels/stable/versions)
    // if the error page comes back. 159 was current in November 2026.
    private val SPOOFED_CHROME_MAJOR = 159

    private val ALLOWED_DOMAINS = setOf(
        "ebay.com",
        "ebaystatic.com",
        "ebayimg.com",
        "cloudflare.com",
        // eBay's "please verify yourself" check is an hCaptcha widget. Without this domain the check page loads
        // but its widget never appears, so the search can not be reached. Only the captcha service is allowed.
        "hcaptcha.com",
    )

    // ── Bookmark URLs ─────────────────────────────────────────────────────────
    //
    // eBay: sold/completed-listings view with an empty search query.
    //   LH_Sold=1     → show only items that actually sold
    //   LH_Complete=1 → include all completed listings (sold + unsold)
    //   _nkw=         → blank keyword, ready for the user to type
    //
    private val URL_EBAY = "https://www.ebay.com/sch/i.html?_nkw=&LH_Sold=1&LH_Complete=1"

    // When the IMAGE button is tapped we load the same eBay search page but set this
    // flag so onPageFinished knows to inject the camera-bypass overlay.
    private var imageSearchPending = false

    // ── eBay camera-page bypass ───────────────────────────────────────────────
    //
    // When the user taps eBay's camera icon the WebView navigates to eBay's own
    // image-search page, which shows a live camera feed, a barcode scanner, and a
    // "pick from gallery" button.  We want to skip all that and open the gallery
    // immediately.
    //
    // Approach: once eBay's camera page finishes loading we inject this script.
    // It finds the gallery/upload trigger by scanning button text (eBay calls it
    // "Gallery", "Photo Library", or "Camera roll" depending on locale/version),
    // stretches it to fill the entire viewport at opacity 0, and paints a plain
    // black label behind it.  The first tap anywhere becomes a real user gesture
    // on eBay's own element, which fires onShowFileChooser → gallery opens.
    // No change to the file-picker flow or the allowlist is needed.
    //
    // When IMAGE is tapped:
    //  1. Click eBay's "Search by image" button (aria-label / class gh-search__camera-btn)
    //  2. eBay opens its visual-search modal and adds gallery-picker__input to the DOM
    //  3. Poll until that input appears, then stretch it to fill the viewport at opacity 0
    //     and paint a black label behind it so the first tap goes straight to gallery
    private val EBAY_CAMERA_JS = """
(function() {
    if (document.getElementById('__lb_overlay')) return;

    function showOverlay(inp) {
        if (document.getElementById('__lb_overlay')) return;
        // Reparent to body so position:fixed escapes any transformed ancestor
        document.body.appendChild(inp);
        // Replace all inline styles — don't append, so nothing from eBay's modal survives
        inp.style.cssText = 'display:block!important;position:fixed!important;'
            + 'top:0!important;left:0!important;'
            + 'width:100vw!important;height:100vh!important;'
            + 'min-width:100vw!important;min-height:100vh!important;'
            + 'z-index:2147483647!important;opacity:0!important;'
            + 'margin:0!important;padding:0!important;border:0!important;'
            + 'pointer-events:auto!important;cursor:pointer!important;'
            + 'font-size:300px!important;';
        var bg = document.createElement('div');
        bg.id = '__lb_overlay';
        bg.style.cssText = 'position:fixed;top:0;left:0;width:100vw;height:100vh;'
            + 'background:#000;z-index:2147483646;display:flex;align-items:center;'
            + 'justify-content:center;pointer-events:none;';
        var lbl = document.createElement('span');
        lbl.style.cssText = 'color:#fff;font-family:sans-serif;font-size:16px;'
            + 'letter-spacing:.08em;text-align:center;';
        lbl.textContent = 'TAP TO PICK FROM GALLERY';
        bg.appendChild(lbl);
        document.body.appendChild(bg);
    }

    // Poll for gallery-picker__input (added by eBay after camera-btn click)
    var attempts = 0;
    var poll = setInterval(function() {
        var inp = document.querySelector('.gallery-picker__input')
               || document.querySelector('input[type=file]');
        if (inp) { clearInterval(poll); showOverlay(inp); return; }
        if (++attempts > 30) clearInterval(poll);
    }, 100);

    // Trigger eBay's visual-search modal
    var camBtn = document.querySelector('.gh-search-input__camera-btn')
              || document.querySelector('[aria-label="Camera icon"]')
              || document.querySelector('[aria-label="Search by image"]')
              || document.querySelector('.gh-search__camera-btn');
    if (camBtn) camBtn.dispatchEvent(new MouseEvent('click', {bubbles:true, cancelable:true, view:window}));
})();
""".trimIndent()

    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        // Force dark mode so WebView algorithmic darkening activates
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        scraper = findViewById(R.id.scraper)
        scraperActive = findViewById(R.id.scraperActive)
        homePane = findViewById(R.id.homePane)
        detailPane = findViewById(R.id.detailPane)
        detailImage = findViewById(R.id.detailImage)
        detailPrice = findViewById(R.id.detailPrice)
        detailTitle = findViewById(R.id.detailTitle)
        detailCondition = findViewById(R.id.detailCondition)
        resultsPane = findViewById(R.id.resultsPane)
        bottomBar = findViewById(R.id.bottomBar)
        titleView = findViewById(R.id.title)
        queryField = findViewById(R.id.queryField)
        summary = findViewById(R.id.summary)
        btnLeft = findViewById(R.id.btnLeft)
        btnRight = findViewById(R.id.btnRight)
        btnScan = findViewById(R.id.btnScan)
        scanPane = findViewById(R.id.scanPane)
        previewView = findViewById(R.id.previewView)
        labelLeft = findViewById(R.id.labelLeft)
        labelRight = findViewById(R.id.labelRight)
        resultList = findViewById(R.id.resultList)
        historyList = findViewById(R.id.historyList)
        setupWebView(webView, AllowlistClient())
        setupWebView(scraper, SideClient(sold = true))
        setupWebView(scraperActive, SideClient(sold = false))
        setupLookup()
        listOf(titleView, queryField, summary, labelLeft, labelRight, detailPrice, detailTitle, detailCondition).forEach { it.typeface = akkurat() }

        findViewById<View>(R.id.btnBack).setOnClickListener { goBack() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })

        setMode(Mode.HOME)
    }

    // -- Screens ----------------------------------------------------------------

    private enum class Mode { HOME, RESULTS, DETAIL, WEB, SCAN }

    private var mode = Mode.HOME

    /** Akkurat is a system font on the Light Phone III; anywhere else this falls back to the default sans serif. */
    private fun akkurat(): Typeface {
        val f = java.io.File("/system/fonts/AkkuratLLTT-Regular.ttf")
        return if (f.exists()) runCatching { Typeface.createFromFile(f) }.getOrDefault(Typeface.SANS_SERIF) else Typeface.SANS_SERIF
    }

    private fun setMode(m: Mode) {
        mode = m
        homePane.visibility = if (m == Mode.HOME) View.VISIBLE else View.GONE
        resultsPane.visibility = if (m == Mode.RESULTS) View.VISIBLE else View.GONE
        detailPane.visibility = if (m == Mode.DETAIL) View.VISIBLE else View.GONE
        scanPane.visibility = if (m == Mode.SCAN) View.VISIBLE else View.GONE
        webView.visibility = if (m == Mode.WEB) View.VISIBLE else View.GONE
        bottomBar.visibility = if (m == Mode.WEB || m == Mode.SCAN) View.GONE else View.VISIBLE
        val home = m == Mode.HOME
        btnLeft.visibility = if (home) View.VISIBLE else View.GONE
        btnRight.visibility = if (home) View.VISIBLE else View.GONE
        btnScan.visibility = if (home) View.VISIBLE else View.GONE
        labelLeft.visibility = if (home) View.GONE else View.VISIBLE
        labelRight.visibility = if (home) View.GONE else View.VISIBLE
        if (m == Mode.DETAIL) {
            labelLeft.text = "PREV"
            labelRight.text = "NEXT"
            labelLeft.setTextColor(0xFFFFFFFF.toInt())
            labelRight.setTextColor(0xFFFFFFFF.toInt())
        } else if (m == Mode.RESULTS) {
            labelLeft.text = "SOLD"
            labelRight.text = "LISTED"
        }
        titleView.text = when (m) {
            Mode.HOME -> "Lookup"
            Mode.RESULTS -> resultsTitle
            Mode.DETAIL -> if (detailImages.size > 1) "" + (detailIndex + 1) + " of " + detailImages.size else "Listing"
            Mode.WEB -> "eBay"
            Mode.SCAN -> "Scan barcode"
        }
        if (m == Mode.HOME) refreshHistory()
        if (m != Mode.SCAN) stopScan()
        if (m != Mode.HOME) hideKeyboard()
        if (m == Mode.RESULTS) paintTabs()
    }

    private fun goBack() {
        when (mode) {
            Mode.HOME -> finish()
            Mode.SCAN -> setMode(Mode.HOME)
            Mode.RESULTS -> {
                // A fresh page: the earlier searches are one tap away below the field.
                queryField.setText("")
                setMode(Mode.HOME)
            }
            Mode.DETAIL -> {
                detailId++
                setMode(Mode.RESULTS)
            }
            Mode.WEB -> {
                if (webView.canGoBack() && !imageFlow) webView.goBack()
                else {
                    imageFlow = false
                    imageResultsExpected = false
                    setMode(if (soldPage != null) Mode.RESULTS else Mode.HOME)
                }
            }
        }
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(queryField.windowToken, 0)
    }

    private fun submitQuery() {
        val q = queryField.text.toString().trim()
        if (q.isEmpty()) return
        resultsTitle = q
        rememberSearch(q)
        startSearch("https://www.ebay.com/sch/i.html?_nkw=" + Uri.encode(q))
    }

    private fun startImageSearch() {
        imageFlow = true
        imageResultsExpected = false
        setMode(Mode.WEB)
        val currentUrl = webView.url ?: ""
        if (currentUrl.contains("ebay.com/sch/", ignoreCase = true)) {
            // Already on an eBay search page (the camera button lives in its header; an item page has none).
            imageSearchPending = false
            webView.evaluateJavascript(EBAY_CAMERA_JS, null)
        } else {
            imageSearchPending = true
            load(URL_EBAY)
        }
    }

    private fun setupLookup() {
        queryField.setOnEditorActionListener { _, action, event ->
            if (action == EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                submitQuery()
                true
            } else false
        }
        queryField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = refreshHistory()
        })
        historyList.setOnItemClickListener { _, _, position, _ ->
            val q = historyList.adapter?.getItem(position) as? String ?: return@setOnItemClickListener
            if (q == CLEAR_ROW) {
                if (clearArmed) { clearArmed = false; saveHistory(emptyList()) } else clearArmed = true
                refreshHistory()
                return@setOnItemClickListener
            }
            clearArmed = false
            queryField.setText(q)
            queryField.setSelection(q.length)
            submitQuery()
        }
        historyList.setOnItemLongClickListener { _, _, position, _ ->
            val q = historyList.adapter?.getItem(position) as? String ?: return@setOnItemLongClickListener true
            if (q != CLEAR_ROW) forgetSearch(q)
            true
        }
        btnLeft.setOnClickListener { startImageSearch() }
        btnScan.setOnClickListener { startScan() }
        btnRight.setOnClickListener { submitQuery() }
        labelLeft.setOnClickListener {
            if (mode == Mode.DETAIL) step(-1) else { showSold = true; paintTabs(); render() }
        }
        labelRight.setOnClickListener {
            if (mode == Mode.DETAIL) step(1) else { showSold = false; paintTabs(); render() }
        }
        val swipe = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent) = true
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
                if (Math.abs(vx) > Math.abs(vy)) { step(if (vx < 0) 1 else -1); return true }
                return false
            }
            override fun onSingleTapUp(e: android.view.MotionEvent): Boolean { step(1); return true }
        })
        detailImage.setOnTouchListener { _, ev -> swipe.onTouchEvent(ev) }
        summary.setOnClickListener {
            if (blockedNote != null) {
                // eBay wants a check: show the real page so it can be done once, the cookie is shared.
                imageFlow = false
                setMode(Mode.WEB)
                webView.loadUrl(variant(searchBase ?: URL_EBAY, sold = true))
            }
        }
        resultList.setOnItemClickListener { _, _, position, _ ->
            val item = resultList.adapter?.getItem(position) as? Listing ?: return@setOnItemClickListener
            imageFlow = false
            openDetail(item)
        }
    }

    // -- Barcode scan ---------------------------------------------------------------
    // The camera preview with CameraX; every frame's brightness plane goes through ZXing. The first code found
    // becomes an eBay search for that number. Nothing is stored and no picture is kept.

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginScan() else {
            summary.text = ""
            android.widget.Toast.makeText(this, "Camera permission is needed to scan", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun startScan() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            beginScan()
        } else {
            cameraPermission.launch(android.Manifest.permission.CAMERA)
        }
    }

    private fun beginScan() {
        scanHandled = false
        setMode(Mode.SCAN)
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(ContextCompat.getMainExecutor(this).let { java.util.concurrent.Executors.newSingleThreadExecutor() }) { image ->
                try {
                    if (!scanHandled) {
                        val plane = image.planes[0]
                        val buf = plane.buffer
                        val bytes = ByteArray(buf.remaining())
                        buf.get(bytes)
                        val code = Barcodes.decode(bytes, plane.rowStride, image.width, image.height)
                        if (code != null) runOnUiThread { onBarcode(code) }
                    }
                } finally {
                    image.close()
                }
            }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.onFailure {
                android.util.Log.i("LookupScan", "camera did not start: " + it.javaClass.simpleName)
                setMode(Mode.HOME)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopScan() {
        cameraProvider?.unbindAll()
    }

    private fun onBarcode(code: String) {
        if (scanHandled || mode != Mode.SCAN) return
        scanHandled = true
        stopScan()
        resultsTitle = code
        startSearch("https://www.ebay.com/sch/i.html?_nkw=" + Uri.encode(code))
    }

    // -- Search history ----------------------------------------------------------
    // Text searches only, kept on the phone (app-private preferences), newest first. Hold a row to forget it.

    private val CLEAR_ROW = "\u0000clear"
    private var clearArmed = false

    private val historyPrefs by lazy { getSharedPreferences("lookup_history", MODE_PRIVATE) }

    private fun loadHistory(): List<String> {
        val raw = historyPrefs.getString("searches", null) ?: return emptyList()
        return runCatching {
            val a = org.json.JSONArray(raw)
            (0 until a.length()).map { a.getString(it) }
        }.getOrDefault(emptyList())
    }

    private fun saveHistory(list: List<String>) {
        historyPrefs.edit().putString("searches", org.json.JSONArray(list).toString()).apply()
    }

    private fun rememberSearch(q: String) {
        val list = loadHistory().filterNot { it.equals(q, ignoreCase = true) }
        saveHistory((listOf(q) + list).take(30))
    }

    private fun forgetSearch(q: String) {
        saveHistory(loadHistory().filterNot { it == q })
        refreshHistory()
    }

    private fun refreshHistory() {
        val typed = queryField.text.toString().trim()
        val all = loadHistory()
        val matches = if (typed.isEmpty()) all else all.filter { it.contains(typed, ignoreCase = true) && !it.equals(typed, ignoreCase = true) }
        // With the field empty, the last row clears the whole history (asks twice).
        val shown = if (typed.isEmpty() && matches.isNotEmpty()) matches + CLEAR_ROW else matches
        val font = akkurat()
        val vpad = (12 * resources.displayMetrics.density).toInt()
        historyList.adapter = object : ArrayAdapter<String>(this, 0, shown) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = (convertView as? TextView) ?: TextView(context).apply {
                    textSize = 20f
                    setTextColor(0xFFBDBDBD.toInt())
                    typeface = font
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, vpad, 0, vpad)
                }
                val text = getItem(position)
                if (text == CLEAR_ROW) {
                    row.text = if (clearArmed) "Tap again to clear history" else "Clear history"
                    row.setTextColor(0xFF6E6E6E.toInt())
                } else {
                    row.text = text
                    row.setTextColor(0xFFBDBDBD.toInt())
                }
                return row
            }
        }
    }

    private fun paintTabs() {
        labelLeft.setTextColor(if (showSold) 0xFFFFFFFF.toInt() else 0xFF6E6E6E.toInt())
        labelRight.setTextColor(if (showSold) 0xFF6E6E6E.toInt() else 0xFFFFFFFF.toInt())
    }

    /** Same search, sold or still listed. Keeps every other filter (photo match, category) from [base]. */
    private fun variant(base: String, sold: Boolean): String {
        val u = Uri.parse(base)
        val b = u.buildUpon().clearQuery()
        val drop = setOf("LH_Sold", "LH_Complete", "_pgn", "_ipg")
        u.queryParameterNames.filter { it !in drop }.forEach { n ->
            u.getQueryParameters(n).forEach { b.appendQueryParameter(n, it) }
        }
        if (sold) b.appendQueryParameter("LH_Sold", "1").appendQueryParameter("LH_Complete", "1")
        b.appendQueryParameter("_ipg", "60")
        return b.build().toString()
    }

    private fun startSearch(base: String) {
        searchBase = base
        searchId++
        soldPage = null
        activePage = null
        blockedNote = null
        showSold = true
        summary.text = "Looking..."
        resultList.adapter = null
        soldDone = false
        activeDone = false
        setMode(Mode.RESULTS)
        // Both searches load at once.
        scraper.loadUrl(variant(base, sold = true))
        scraperActive.loadUrl(variant(base, sold = false))
    }

    private var soldDone = false
    private var activeDone = false

    // -- One listing ------------------------------------------------------------

    private fun openDetail(item: Listing) {
        detailItem = item
        detailImages = emptyList()
        detailIndex = 0
        detailId++
        detailImage.setImageDrawable(null)
        detailPrice.text = item.priceText
        detailTitle.text = item.title
        detailCondition.visibility = View.GONE
        setMode(Mode.DETAIL)
        scraper.loadUrl(item.url)
    }

    private fun step(by: Int) {
        if (mode != Mode.DETAIL || detailImages.size < 2) return
        detailIndex = (detailIndex + by + detailImages.size) % detailImages.size
        showImage()
    }

    private fun showImage() {
        titleView.text = if (detailImages.size > 1) "" + (detailIndex + 1) + " of " + detailImages.size else "Listing"
        val url = detailImages.getOrNull(detailIndex) ?: return
        val id = detailId
        imageThread.execute {
            val bmp = runCatching { fetchBitmap(url) }.getOrNull()
            runOnUiThread {
                if (id == detailId && detailImages.getOrNull(detailIndex) == url) {
                    if (bmp != null) detailImage.setImageBitmap(bmp) else detailTitle.text = (detailItem?.title ?: "") + "\n(picture did not load)"
                }
            }
        }
    }

    private fun fetchBitmap(url: String): android.graphics.Bitmap? {
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 15000
        }
        val bytes = conn.inputStream.use { it.readBytes() }
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1080) sample *= 2
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun onDetailExtracted(id: Int, raw: String?) {
        if (id != detailId || mode != Mode.DETAIL) return
        val o = runCatching {
            org.json.JSONObject(org.json.JSONTokener(raw ?: return).nextValue() as String)
        }.getOrNull() ?: return
        val arr = o.optJSONArray("images") ?: org.json.JSONArray()
        // Counts only in the log.
        android.util.Log.i("LookupScrape", "detail images=" + arr.length() + " href=" + (o.optString("href").contains("/itm/")))
        if (!o.optString("href").contains("/itm/")) return  // still the old page; wait for the item page
        val condition = o.optString("condition").replace(Regex("""^Condition:?\s*"""), "").trim()
        if (condition.isNotEmpty()) {
            detailCondition.text = "Condition: " + condition
            detailCondition.visibility = View.VISIBLE
        }
        if (detailImages.isNotEmpty()) return
        val list = ArrayList<String>()
        for (i in 0 until arr.length()) list.add(arr.getString(i))
        if (list.isEmpty()) { detailTitle.text = (detailItem?.title ?: "") + "\n(no pictures found)"; return }
        detailImages = list
        detailIndex = 0
        showImage()
    }

    /** One of the two hidden search pages. The sold one also reads a listing's page when a listing is open. */
    private inner class SideClient(private val sold: Boolean) : AllowlistClient() {
        override fun onPageFinished(view: WebView, url: String) {
            if (mode == Mode.DETAIL) {
                if (!sold) return
                val did = detailId
                // The gallery fills in after load; try twice, the first read may come too early.
                for (delay in longArrayOf(1500, 3500)) view.postDelayed({
                    if (did == detailId) view.evaluateJavascript(Listings.IMAGES_JS) { raw -> onDetailExtracted(did, raw) }
                }, delay)
                return
            }
            // eBay sometimes answers with its "please verify" page instead of results. Say so instead of waiting forever.
            if (url.contains("/splashui/") || url.contains("captcha", ignoreCase = true)) {
                val id = searchId
                blockedNote = "eBay wants a check. Tap here, do it once, then search again."
                onExtracted(id, sold, null)
                return
            }
            if (url.contains("LH_Sold=1") != sold) return  // a redirect or the previous page, not ours
            val id = searchId
            // eBay fills the list in after the page loads: look every 250 ms and take the cards as soon as they are there.
            poll(view, id, 0)
        }

        private fun poll(view: WebView, id: Int, attempt: Int) {
            view.postDelayed({
                if (id != searchId || (if (sold) soldDone else activeDone)) return@postDelayed
                view.evaluateJavascript(Listings.EXTRACT_JS) { raw ->
                    if (id != searchId || (if (sold) soldDone else activeDone)) return@evaluateJavascript
                    val page = Listings.parse(raw)
                    val ready = page != null && page.items.isNotEmpty() && (page.href.isEmpty() || page.href.contains("LH_Sold=1") == sold)
                    when {
                        ready -> onExtracted(id, sold, page)
                        attempt >= 24 -> onExtracted(id, sold, page)  // six seconds: nothing found, say so
                        else -> poll(view, id, attempt + 1)
                    }
                }
            }, if (attempt == 0) 400L else 250L)
        }
    }

    private fun onExtracted(id: Int, sold: Boolean, page: Listings.Page?) {
        if (id != searchId) return
        if (sold) soldDone = true else activeDone = true
        // Counts and class names only, never titles or prices, so nothing personal reaches the log.
        android.util.Log.i("LookupScrape", "step=" + (if (sold) "sold" else "active") + " cards=" + page?.cards +
            " items=" + page?.items?.size + " sample='" + page?.sample + "' title='" + page?.pageTitle + "'")
        val result = page ?: Listings.Page(0, "", "", emptyList())
        if (result.items.isEmpty() && result.cards == 0) {
            val t = result.pageTitle.lowercase()
            if (t.contains("verify") || t.contains("security") || t.contains("robot") || t.contains("challenge") || t.contains("sorry"))
                blockedNote = "eBay wants a check. Tap here, do it once, then search again."
        }
        if (sold) soldPage = result else activePage = result
        // Sold results show as soon as they are in; the listed ones fill in beside them without moving the list
        // you are looking at.
        render(rebuildList = (sold == showSold) || !(if (showSold) soldDone else activeDone))
    }

    private fun render(rebuildList: Boolean = true) {
        val sold = soldPage
        val active = activePage
        if (sold == null && active == null) return
        fun line(label: String, page: Listings.Page?) =
            if (page == null) label + " looking..."
            else label + " " + page.items.size + ", typically " + Listings.money(Listings.median(page.items.mapNotNull { it.price }))
        summary.text = if (blockedNote != null) "Sold: eBay wants a check. Tap here, do it once, then search again.\n" + line("Listed", active)
            else if (soldDone && activeDone && sold?.items?.isEmpty() == true && active?.items?.isEmpty() == true) "Nothing found."
            else line("Sold", sold) + "\n" + line("Listed", active)
        if (!rebuildList) return
        val items = (if (showSold) sold else active)?.items ?: emptyList()
        val font = akkurat()
        val pad = (31 * resources.displayMetrics.density).toInt()
        val vpad = (14 * resources.displayMetrics.density).toInt()
        resultList.adapter = object : ArrayAdapter<Listing>(this, 0, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = (convertView as? android.widget.LinearLayout) ?: android.widget.LinearLayout(context).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(pad, vpad, pad, vpad)
                    addView(TextView(context).apply { textSize = 24f; setTextColor(0xFFFFFFFF.toInt()); typeface = font })
                    addView(TextView(context).apply { textSize = 16f; setTextColor(0xFFFFFFFF.toInt()); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; typeface = font })
                    addView(TextView(context).apply { textSize = 14f; setTextColor(0xFF6E6E6E.toInt()); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; typeface = font })
                }
                val item = getItem(position)!!
                (row.getChildAt(0) as TextView).text = item.priceText
                (row.getChildAt(1) as TextView).text = item.title
                (row.getChildAt(2) as TextView).apply { text = item.note; visibility = if (item.note.isBlank()) View.GONE else View.VISIBLE }
                return row
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(view: WebView, client: WebViewClient) {
        with(view.settings) {
            // JavaScript is required by all three sites
            javaScriptEnabled   = true
            // DOM storage (localStorage/sessionStorage) — required by eBay and Discogs
            domStorageEnabled   = true
            // No local file or content-provider access
            allowFileAccess     = false
            allowContentAccess  = false
            // Pinch-to-zoom without the visible +/- overlay buttons
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            // Never load HTTP sub-resources from an HTTPS page
            mixedContentMode    = WebSettings.MIXED_CONTENT_NEVER_ALLOW

            // Algorithmic dark mode (API 33+): inverts colours on pages that don't
            // natively support prefers-color-scheme: dark. Works in tandem with
            // AppCompatDelegate.MODE_NIGHT_YES set in onCreate.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                isAlgorithmicDarkeningAllowed = true
            }

            // Cloudflare (and other bot-detection) identifies Android WebView by two
            // markers that the system injects into the default UA string:
            //   "Version/4.0 "  — WebView legacy version prefix
            //   "; wv"          — explicit WebView flag added since Chrome 65+
            // Strip both so the UA is indistinguishable from a real Chrome browser.
            // We read the live UA rather than hardcoding so the Chrome version stays
            // current with whatever system WebView the LP3 ships.
            userAgentString = userAgentString
                .replace("Version/4.0 ", "")
                .replace("; wv", "")
                .replace(Regex("""Chrome/\d+(\.\d+){3}"""), "Chrome/$SPOOFED_CHROME_MAJOR.0.0.0")
        }

        view.setBackgroundColor(Color.BLACK)  // no white flash before page paints
        view.webViewClient   = client
        view.webChromeClient = MinimalChromeClient()

        // Discard every download attempt, we never open a file manager or external app
        view.setDownloadListener { _, _, _, _, _ -> }
    }

    private fun load(url: String) = webView.loadUrl(url)

    /**
     * Returns true when [url] is permitted to load.
     *
     * Always allowed:  about:, data:, blob: (internal/inline page resources)
     * Domain check:    http/https host must equal an ALLOWED_DOMAINS entry or be a subdomain of one
     * Everything else: blocked (other schemes, non-listed domains)
     */
    private fun isAllowed(url: String): Boolean {
        if (url.startsWith("about:")) return true
        if (url.startsWith("data:"))  return true
        // blob: URLs carry an embedded origin ("blob:https://www.ebay.com/uuid") — check it
        if (url.startsWith("blob:"))  return isAllowed(url.removePrefix("blob:"))
        val host = runCatching { Uri.parse(url).host }
            .getOrNull()
            ?.lowercase()
            ?: return false   // unparseable or no host → block
        return ALLOWED_DOMAINS.any { domain ->
            host == domain || host.endsWith(".$domain")
        }
    }

    /** An empty response body used to satisfy blocked sub-resource requests. */
    private fun emptyResponse() =
        WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))

    // Any eBay page — the visual search modal lives on the same page as the search bar
    private fun isEbaySearchPage(url: String) = url.contains("ebay.com", ignoreCase = true)

    // ── WebViewClient ─────────────────────────────────────────────────────────

    private open inner class AllowlistClient : WebViewClient() {

        /**
         * Intercepts every top-level navigation: link taps, JS location changes,
         * server-side redirects that land on a new URL within the page flow, etc.
         *
         * return true  = "we handled it" = silently block (stay on current page)
         * return false = allow WebView to proceed with the navigation
         */
        override fun shouldOverrideUrlLoading(
            view: WebView, request: WebResourceRequest,
        ): Boolean {
            val allowed = isAllowed(request.url.toString())
            if (!allowed) android.util.Log.i("LookupBlocked", "navigation blocked: ${request.url.host}")
            return !allowed
        }

        override fun onPageFinished(view: WebView, url: String) {
            // Only a page with eBay's search header counts: the item page we came from may finish loading after IMAGE
            // was tapped, and used to use up the request before the search page arrived.
            // A photo was handed to eBay and its matches have loaded: read them into our own page.
            if (view === webView && imageResultsExpected && url.contains("ebay.com/sch/")) {
                imageResultsExpected = false
                imageFlow = false
                imageSearchPending = false
                resultsTitle = "Photo"
                startSearch(url)
                return
            }
            if (view === webView && imageSearchPending && (url.contains("ebay.com/sch/") || url.contains("ebay.com/n/"))) {
                view.postDelayed({ view.evaluateJavascript(EBAY_CAMERA_JS, null) }, 800)
                // Keep asking until the overlay really exists (a redirect can replace the page after we inject).
                view.postDelayed({
                    view.evaluateJavascript("!!document.getElementById('__lb_overlay')") { r ->
                        if (r == "true") imageSearchPending = false
                    }
                }, 4000)
            }
        }

        /**
         * Intercepts every sub-resource request: scripts, stylesheets, images,
         * XHR/fetch calls, fonts, iframes, etc.
         *
         * Called on a background thread — isAllowed() is pure and thread-safe.
         *
         * return null           = allow (WebView fetches the resource normally)
         * return emptyResponse() = block (WebView receives an empty body)
         *
         * Analytics, social widgets, and ad scripts on eBay/Discogs/RYM will
         * hit this filter and be silently dropped as their domains are not listed.
         */
        override fun shouldInterceptRequest(
            view: WebView, request: WebResourceRequest,
        ): WebResourceResponse? {
            val url = request.url.toString()
            if (isAllowed(url)) return null
            // Host only, never the path or query, so nothing personal is logged. Lets us see what a page needed.
            android.util.Log.i("LookupBlocked", "blocked: ${request.url.host}")
            return emptyResponse()
        }
    }

    // ── WebChromeClient ───────────────────────────────────────────────────────

    private inner class MinimalChromeClient : WebChromeClient() {

        /**
         * Block all popup / new-tab requests (target="_blank" links, window.open()).
         * Returning false tells the system there is no window to receive the content.
         */
        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: android.os.Message?,
        ): Boolean = false

        /**
         * Handles eBay's image-search file input.
         * Opens the gallery only — no camera option.
         */
        override fun onShowFileChooser(
            view: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            fileChooserCallback?.onReceiveValue(null)
            fileChooserCallback = filePathCallback
            // ACTION_OPEN_DOCUMENT shows the system document picker (Recent + Browse),
            // so photos from third-party camera apps appear immediately without waiting
            // for MediaStore to index them.
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            return try {
                filePickerLauncher.launch(intent)
                true
            } catch (e: ActivityNotFoundException) {
                fileChooserCallback = null
                false
            }
        }
    }
}

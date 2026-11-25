package com.lookup.browser

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
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
    private lateinit var scraper: WebView
    private lateinit var lookupPane: View
    private lateinit var queryField: EditText
    private lateinit var summary: TextView
    private lateinit var tabSold: TextView
    private lateinit var tabActive: TextView
    private lateinit var resultList: ListView

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
    var camBtn = document.querySelector('[aria-label="Search by image"]')
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
        lookupPane = findViewById(R.id.lookupPane)
        queryField = findViewById(R.id.queryField)
        summary = findViewById(R.id.summary)
        tabSold = findViewById(R.id.tabSold)
        tabActive = findViewById(R.id.tabActive)
        resultList = findViewById(R.id.resultList)
        setupWebView(webView, AllowlistClient())
        setupWebView(scraper, ScraperClient())
        setupLookup()

        // On-screen back button — navigate within page history, do nothing at root
        findViewById<TextView>(R.id.btnBack).setOnClickListener { goBackOrHome() }

        // LOOKUP: on the web page, turn an eBay results page (for instance after a photo search) into our own
        // page; otherwise just show our page.
        findViewById<TextView>(R.id.btnEbay).setOnClickListener {
            val url = webView.url ?: ""
            if (webView.visibility == View.VISIBLE && url.contains("ebay.com/sch/")) {
                queryField.setText("")
                queryField.hint = "from photo"
                startSearch(url)
            } else {
                showLookup()
            }
        }
        findViewById<TextView>(R.id.btnImage).setOnClickListener {
            showWeb()
            val currentUrl = webView.url ?: ""
            if (currentUrl.contains("ebay.com", ignoreCase = true)) {
                // Already on eBay — inject directly, no navigation needed.
                // Avoids the Cloudflare-challenge redirect clearing imageSearchPending early.
                imageSearchPending = false
                webView.evaluateJavascript(EBAY_CAMERA_JS, null)
            } else {
                imageSearchPending = true
                load(URL_EBAY)
            }
        }

        // Hardware back key: mirror the on-screen button behaviour
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                goBackOrHome()
                // On our own page: swallow the event, do NOT exit the app
            }
        })

        if (savedInstanceState != null) {
            // Restore scroll position and URL after a process kill/recreate
            webView.restoreState(savedInstanceState)
        } else {
            showLookup()
        }
    }

    // -- Lookup page ----------------------------------------------------------

    private fun goBackOrHome() {
        if (webView.visibility != View.VISIBLE) return
        if (webView.canGoBack()) webView.goBack() else showLookup()
    }

    private fun showLookup() {
        webView.visibility = View.GONE
        lookupPane.visibility = View.VISIBLE
    }

    private fun showWeb() {
        lookupPane.visibility = View.GONE
        webView.visibility = View.VISIBLE
        hideKeyboard()
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(queryField.windowToken, 0)
    }

    private fun setupLookup() {
        queryField.setOnEditorActionListener { _, action, event ->
            if (action == EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                val q = queryField.text.toString().trim()
                if (q.isNotEmpty()) {
                    queryField.hint = "What is it?"
                    startSearch("https://www.ebay.com/sch/i.html?_nkw=" + Uri.encode(q))
                }
                true
            } else false
        }
        tabSold.setOnClickListener { showSold = true; render() }
        tabActive.setOnClickListener { showSold = false; render() }
        summary.setOnClickListener {
            if (blockedNote != null) {
                // eBay wants a check: show the real page so it can be done once, the cookie is shared.
                showWeb()
                webView.loadUrl(variant(searchBase ?: URL_EBAY, sold = true))
            }
        }
        resultList.setOnItemClickListener { _, _, position, _ ->
            val item = resultList.adapter?.getItem(position) as? Listing ?: return@setOnItemClickListener
            showWeb()
            webView.loadUrl(item.url)
        }
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
        hideKeyboard()
        showLookup()
        searchBase = base
        searchId++
        soldPage = null
        activePage = null
        blockedNote = null
        showSold = true
        summary.text = "Looking..."
        resultList.adapter = null
        scraper.loadUrl(variant(base, sold = true))
    }

    private inner class ScraperClient : AllowlistClient() {
        override fun onPageFinished(view: WebView, url: String) {
            val id = searchId
            // eBay fills the list in after the page loads; give it a moment, then read the cards.
            view.postDelayed({
                if (id != searchId) return@postDelayed
                view.evaluateJavascript(Listings.EXTRACT_JS) { raw -> onExtracted(id, Listings.parse(raw)) }
            }, 1500)
        }
    }

    private fun onExtracted(id: Int, page: Listings.Page?) {
        if (id != searchId || activePage != null) return
        val soldStep = soldPage == null
        // Counts and class names only, never titles or prices, so nothing personal reaches the log.
        android.util.Log.i("LookupScrape", "step=" + (if (soldStep) "sold" else "active") + " cards=" + page?.cards +
            " items=" + page?.items?.size + " sample='" + page?.sample + "' title='" + page?.pageTitle + "'")
        val result = page ?: Listings.Page(0, "", "", emptyList())
        if (result.items.isEmpty() && result.cards == 0) {
            val t = result.pageTitle.lowercase()
            blockedNote = if (t.contains("verify") || t.contains("security") || t.contains("robot") || t.contains("challenge") || t.contains("sorry"))
                "eBay wants a check. Tap here, do it once, then search again." else null
        }
        if (soldStep) {
            soldPage = result
            scraper.loadUrl(variant(searchBase ?: return, sold = false))
        } else {
            activePage = result
            render()
        }
    }

    private fun light() = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)

    private fun render() {
        tabSold.setTextColor(if (showSold) 0xFFFFFFFF.toInt() else 0xFF777777.toInt())
        tabActive.setTextColor(if (showSold) 0xFF777777.toInt() else 0xFFFFFFFF.toInt())
        val sold = soldPage ?: return
        val active = activePage
        val medSold = Listings.median(sold.items.mapNotNull { it.price })
        val medActive = Listings.median(active?.items?.mapNotNull { it.price } ?: emptyList())
        summary.text = blockedNote
            ?: if (sold.items.isEmpty() && (active?.items?.isEmpty() != false)) "Nothing found."
            else "Sold " + sold.items.size + ", typically " + Listings.money(medSold) + "\nListed " + (active?.items?.size ?: 0) + ", typically " + Listings.money(medActive)
        val items = if (showSold) sold.items else (active?.items ?: emptyList())
        resultList.adapter = object : ArrayAdapter<Listing>(this, 0, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = (convertView as? android.widget.LinearLayout) ?: android.widget.LinearLayout(context).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(48, 24, 48, 24)
                    addView(TextView(context).apply { textSize = 20f; setTextColor(0xFFFFFFFF.toInt()); typeface = light() })
                    addView(TextView(context).apply { textSize = 15f; setTextColor(0xFFBBBBBB.toInt()); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; typeface = light() })
                    addView(TextView(context).apply { textSize = 13f; setTextColor(0xFF777777.toInt()); maxLines = 1; typeface = light() })
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
            if (view === webView && imageSearchPending && isEbaySearchPage(url)) {
                imageSearchPending = false
                view.postDelayed({ view.evaluateJavascript(EBAY_CAMERA_JS, null) }, 800)
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

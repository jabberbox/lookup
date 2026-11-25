package com.lookup.browser

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** One eBay result, reduced to what Lookup shows. */
data class Listing(val title: String, val price: Double?, val priceText: String, val note: String, val url: String)

object Listings {
    /**
     * Runs inside the scraper page. Returns a JSON string with every result card found, plus the page title and a
     * sample class name, so a layout change on eBay's side can be diagnosed from the log without reading any page.
     */
    val EXTRACT_JS = """
(function() {
    var nodes = document.querySelectorAll('li.s-item, li.s-card, ul.srp-results > li');
    var items = [];
    nodes.forEach(function(li) {
        var a = li.querySelector('a[href*="/itm/"]');
        if (!a) return;
        function t(sel) { var e = li.querySelector(sel); return e ? e.textContent.trim() : ''; }
        var title = t('.s-item__title, .s-card__title, [role=heading]');
        if (!title) return;
        items.push({
            title: title,
            price: t('.s-item__price, .s-card__price'),
            note: [t('.s-item__caption, .s-card__caption, .POSITIVE'), t('.SECONDARY_INFO, .s-card__subtitle, .s-item__subtitle')]
                  .filter(function(x) { return x; }).join(' · '),
            url: a.href
        });
    });
    return JSON.stringify({cards: nodes.length, title: document.title,
        sample: nodes.length ? String(nodes[0].className) : '', items: items});
})()
""".trimIndent()

    class Page(val cards: Int, val pageTitle: String, val sample: String, val items: List<Listing>)

    /** evaluateJavascript hands back a JSON-quoted string; unwrap it and parse the inner JSON. */
    fun parse(raw: String?): Page? {
        if (raw == null || raw == "null") return null
        val inner = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: return null
        val o = runCatching { JSONObject(inner) }.getOrNull() ?: return null
        val arr: JSONArray = o.optJSONArray("items") ?: JSONArray()
        val list = ArrayList<Listing>()
        for (i in 0 until arr.length()) {
            val it = arr.getJSONObject(i)
            val title = clean(it.optString("title"))
            if (title.isBlank() || title.equals("Shop on eBay", ignoreCase = true)) continue
            val priceText = it.optString("price")
            list.add(Listing(title, firstPrice(priceText), shortPrice(priceText), clean(it.optString("note")), it.optString("url")))
        }
        return Page(o.optInt("cards"), o.optString("title"), o.optString("sample"), list)
    }

    private fun clean(s: String) = s
        .replace("Opens in a new window or tab", "")
        .replace(Regex("""^New Listing\s*"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private val money = Regex("""[\d,]+(\.\d+)?""")

    /** "$1,234.56" -> 1234.56; for a range "$5.00 to $9.00" the first figure. */
    fun firstPrice(text: String): Double? =
        money.find(text)?.value?.replace(",", "")?.toDoubleOrNull()

    private fun shortPrice(text: String) = text.replace(Regex("""\s+"""), " ").trim()

    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    fun money(v: Double?): String = if (v == null) "none" else "$" + String.format("%,.2f", v)
}

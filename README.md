# Lookup

A small eBay price lookup for the Light Phone III. Type what an item is, or pick a photo, and see what it
sold for and what it is listed for. Nothing else.

- **Search by text or photo.** Photo search hands the picture to eBay's own image matching, then reads the
  matches.
- **Sold and listed prices** on a plain black page in the style of the stock LightOS tools, with a typical
  (median) price for each.
- **A listing page** with its pictures (swipe, tap, or PREV and NEXT), price and title. No browser page.

## How it works

Lookup has no API keys. It loads eBay's own search pages in a hidden WebView that shares your eBay sign-in,
reads the result cards with a short script, and draws them itself. Only eBay, its image and static hosts, and
the hCaptcha check are allowed to load. This depends on eBay's page layout, so a redesign can break it. The app
logs counts and class names only (`adb logcat -s LookupScrape`), never titles or prices.

The LP3's system WebView is old (Chromium 113) and eBay turns it away, so the app presents itself as a current
Chrome (`SPOOFED_CHROME_MAJOR` in `MainActivity.kt`). Raise that number if eBay's error page comes back.

## Build

Android Studio, or Gradle with JDK 17. The debug build installs as `com.lookup.browser.debug`.

This is a personal tool. It reads eBay pages on your own signed-in session; use it in line with eBay's terms.

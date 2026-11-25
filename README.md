# Lookup

A small eBay price lookup for the Light Phone III. Look an item up by text, photo or barcode and see what it
sold for and what it is listed for. Nothing else.

- **Three ways in.** Type what it is, pick a photo (eBay's own image matching reads it), or scan a barcode
  (UPC, EAN and common shelf codes; the number becomes the search).
- **Sold and listed prices** on a plain black page in the style of the stock LightOS tools, with a typical
  (median) price for each. SOLD and LISTED tabs at the bottom. Results show as they arrive: sold first,
  listed a few seconds later.
- **A listing page** with its pictures (swipe, tap, or PREV and NEXT), price, title and condition. No browser
  page.
- **Search history.** Recent text searches sit under the field, newest first. Typing narrows them, a tap runs
  one again, holding a row forgets it, and the last row clears the lot (it asks twice). The history stays on
  the phone, in the app's private storage. Photo and barcode searches are not saved.

## How it works

Lookup has no API keys. It loads eBay's own search pages in two hidden WebViews (sold and listed, at the same
time) that share your eBay sign-in, reads the result cards with a short script as soon as they appear, and
draws them itself. A listing's pictures and condition are read the same way from its item page.

Only eBay, its image and static hosts, and the hCaptcha check are allowed to load. This depends on eBay's page
layout, so a redesign can break it. The app logs counts and class names only (`adb logcat -s LookupScrape`),
never titles or prices.

If eBay shows its "please verify" page, the results page says so. Tap that line, complete the check once on
the real page, and search again. The check sticks to your session.

The LP3's system WebView is old (Chromium 113) and eBay turns it away, so the app presents itself as a current
Chrome (`SPOOFED_CHROME_MAJOR` in `MainActivity.kt`). Raise that number if eBay's error page comes back.

**Barcode scan** uses CameraX for the camera and ZXing (pure Java, no Google services) to read each frame. It
asks for the camera permission the first time. No picture is kept.

## Build

Android Studio, or Gradle with JDK 17. The debug build installs as `com.lookup.browser.debug`. Unit tests for
the barcode decoder: `gradle :app:testDebugUnitTest`.

This is a personal tool. It reads eBay pages on your own signed-in session; use it in line with eBay's terms.

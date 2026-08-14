package de.tankradar.app

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface

/**
 * Saves in-page downloads to the device's Downloads folder.
 *
 * TankRadar's "Beschwerdeanlage als PDF" export is produced by Dash's `dcc.Download`
 * component, which builds the file in JavaScript and clicks a `blob:` link. A
 * WebView's `DownloadListener` never fires for `blob:` URLs, so without this bridge
 * the export button would silently do nothing on Android.
 * [BLOB_DOWNLOAD_HOOK_JS] intercepts those downloads and hands the bytes here.
 */
class DownloadBridge(
    private val context: Context,
    private val onResult: (fileName: String, success: Boolean) -> Unit,
) {

    @JavascriptInterface
    fun saveBase64(fileName: String, base64Data: String, mimeType: String) {
        val safeName = sanitizeFileName(fileName)
        val success = try {
            writeToDownloads(safeName, Base64.decode(base64Data, Base64.DEFAULT), mimeType)
            true
        } catch (error: Exception) {
            Log.e(TAG, "Download of $safeName failed", error)
            false
        }
        onResult(safeName, success)
    }

    private fun writeToDownloads(fileName: String, bytes: ByteArray, mimeType: String) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore returned no URI for $fileName")

        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("Could not open $uri for writing")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (error: Exception) {
            // A pending entry left behind would stay invisible to the user forever.
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun sanitizeFileName(fileName: String): String {
        val trimmed = fileName.substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = trimmed.filter { it.isLetterOrDigit() || it in "._- " }.trim()
        return cleaned.ifEmpty { "tankradar-download" }
    }

    companion object {
        private const val TAG = "TankRadarDownload"

        const val BRIDGE_NAME = "TankRadarDownloads"

        /**
         * Injected after every page load.
         *
         * Two entry points are needed. `dcc.Download` triggers the save by calling
         * `.click()` on an anchor it builds in JavaScript, and depending on the
         * bundled downloadjs version that anchor may never be attached to the
         * document — events from a detached node do not reach a document-level
         * listener, so patching `HTMLAnchorElement.prototype.click` is what
         * actually catches the PDF export. The capturing click listener covers
         * ordinary download links the user taps themselves.
         *
         * Plain http(s) downloads are deliberately left alone so they keep flowing
         * through the WebView's DownloadListener.
         */
        val BLOB_DOWNLOAD_HOOK_JS = """
            (function () {
                if (window.__tankradarDownloadHook) { return; }
                window.__tankradarDownloadHook = true;

                function isInPageDownload(href) {
                    return href.indexOf('blob:') === 0 || href.indexOf('data:') === 0;
                }

                function forwardToAndroid(href, fileName) {
                    fetch(href)
                        .then(function (response) { return response.blob(); })
                        .then(function (blob) {
                            var reader = new FileReader();
                            reader.onloadend = function () {
                                var result = String(reader.result || '');
                                var comma = result.indexOf(',');
                                if (comma < 0) { return; }
                                ${BRIDGE_NAME}.saveBase64(
                                    fileName,
                                    result.substring(comma + 1),
                                    blob.type || 'application/octet-stream'
                                );
                            };
                            reader.readAsDataURL(blob);
                        })
                        .catch(function () {});
                }

                var nativeClick = HTMLAnchorElement.prototype.click;
                HTMLAnchorElement.prototype.click = function () {
                    var href = this.getAttribute('href') || '';
                    var name = this.getAttribute('download');
                    if (name !== null && isInPageDownload(href)) {
                        forwardToAndroid(href, name || 'tankradar-download');
                        return;
                    }
                    return nativeClick.apply(this, arguments);
                };

                document.addEventListener('click', function (event) {
                    var target = event.target;
                    var anchor = target && target.closest ? target.closest('a[download]') : null;
                    if (!anchor) { return; }

                    var href = anchor.getAttribute('href') || '';
                    if (!isInPageDownload(href)) { return; }

                    event.preventDefault();
                    forwardToAndroid(href, anchor.getAttribute('download') || 'tankradar-download');
                }, true);
            })();
        """.trimIndent()
    }
}

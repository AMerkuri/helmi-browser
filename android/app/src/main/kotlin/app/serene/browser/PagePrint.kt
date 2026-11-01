package app.serene.browser

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintManager
import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import java.net.URI

/**
 * ⋮ › Print… (and the reader's print button): Android's print dialog for what a WebView shows.
 * Its "Save as PDF" printer makes the PDF, as in Chrome. The job is named after the page's title
 * (its address when it has none), and the PDF's file name is that name too: the dialog's file
 * picker drops the characters a file name can't have.
 */
object PagePrint {
    private const val MAX_NAME = 120

    /** The job's name: the title on one line, not too long for a file name. */
    fun jobName(title: String, url: String): String {
        val clean = title.map { if (it.isISOControl() || it.isWhitespace()) ' ' else it }.joinToString("")
            .replace(Regex(" {2,}"), " ").trim()
        val name = clean.ifEmpty { hostOf(url) }.ifEmpty { "Helmi page" }
        if (name.length <= MAX_NAME) return name
        // Not between the two halves of a surrogate pair (an emoji in the title).
        val end = if (name[MAX_NAME - 1].isHighSurrogate()) MAX_NAME - 1 else MAX_NAME
        return name.substring(0, end).trimEnd()
    }

    private fun hostOf(url: String): String =
        runCatching { URI(url).host }.getOrNull()?.removePrefix("www.").orEmpty()

    /**
     * Opens the print dialog for [webView]'s page. PrintManager prints only from an activity, so
     * [context] is one or wraps one. The page lays out for paper in the dialog's preview, with its
     * own print styles. [printing] is told true when the dialog starts on the page and false when
     * it is done with it (printed, saved or cancelled), some time after this returns.
     */
    fun print(context: Context, webView: WebView, title: String, url: String, printing: (Boolean) -> Unit = {}) {
        val activity = context.findActivity()
        val printer = activity?.getSystemService(PrintManager::class.java)
        if (printer == null) {
            Toast.makeText(context, "This phone can't print", Toast.LENGTH_SHORT).show()
            return
        }
        val name = jobName(title, url)
        try {
            printer.print(name, adapter(webView, name, printing), null)
        } catch (e: RuntimeException) {
            Log.w("Serene", "print failed", e)
            Toast.makeText(context, "Couldn't print this page", Toast.LENGTH_SHORT).show()
        }
    }

    /** What the dialog prints: [webView]'s page, for [print]. */
    fun adapter(webView: WebView, name: String, printing: (Boolean) -> Unit): PrintDocumentAdapter =
        Watched(webView.createPrintDocumentAdapter(name), printing)

    /** WebView's adapter, saying when the dialog starts on the page and when it is done with it. */
    private class Watched(private val inner: PrintDocumentAdapter, private val printing: (Boolean) -> Unit) : PrintDocumentAdapter() {
        override fun onStart() {
            printing(true)
            inner.onStart()
        }

        override fun onLayout(
            oldAttributes: PrintAttributes?, newAttributes: PrintAttributes, cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback, extras: Bundle?,
        ) = inner.onLayout(oldAttributes, newAttributes, cancellationSignal, callback, extras)

        override fun onWrite(pages: Array<out PageRange>, destination: ParcelFileDescriptor, cancellationSignal: CancellationSignal?, callback: WriteResultCallback) =
            inner.onWrite(pages, destination, cancellationSignal, callback)

        override fun onFinish() {
            inner.onFinish()
            printing(false)
        }
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

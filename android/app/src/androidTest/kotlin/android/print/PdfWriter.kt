package android.print

import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Prints a PrintDocumentAdapter to a PDF file, as the print dialog's "Save as PDF" does, without
 * the dialog. In android.print because the result callbacks' constructors are package-private.
 * Call on the main thread; [done] is told whether the file was written.
 */
object PdfWriter {
    fun write(adapter: PrintDocumentAdapter, file: File, done: (Boolean) -> Unit) {
        val attributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()
        adapter.onStart()
        adapter.onLayout(null, attributes, CancellationSignal(), object : PrintDocumentAdapter.LayoutResultCallback() {
            override fun onLayoutFinished(info: PrintDocumentInfo, changed: Boolean) {
                val out = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
                adapter.onWrite(arrayOf(PageRange.ALL_PAGES), out, CancellationSignal(), object : PrintDocumentAdapter.WriteResultCallback() {
                    override fun onWriteFinished(pages: Array<out PageRange>) = finish(true)
                    override fun onWriteFailed(error: CharSequence?) = finish(false)
                    override fun onWriteCancelled() = finish(false)

                    private fun finish(ok: Boolean) {
                        out.close()
                        adapter.onFinish()
                        done(ok)
                    }
                })
            }

            override fun onLayoutFailed(error: CharSequence?) { adapter.onFinish(); done(false) }
            override fun onLayoutCancelled() { adapter.onFinish(); done(false) }
        }, null)
    }
}

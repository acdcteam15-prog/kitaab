package com.pdfreader.app.data.pdf

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File

/** Thrown when the PDF can't be edited (password-protected, damaged…). The message is user-facing. */
class PdfEditException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Rewrites a PDF with only the chosen pages, in the chosen order.
 * Text, fonts and links stay intact (nothing is rasterised) and deleted pages are dropped
 * from the file, so it gets smaller.
 */
object PdfPageEditor {

    /** Page attributes a page may inherit from its parent node — copied onto each page before it moves. */
    private val INHERITABLE = listOf(COSName.RESOURCES, COSName.MEDIA_BOX, COSName.CROP_BOX, COSName.ROTATE)

    /** Walks up the page tree for an attribute the page doesn't carry itself. */
    private fun inherited(page: COSDictionary, key: COSName): COSBase? {
        var node = page.getDictionaryObject(COSName.PARENT) as? COSDictionary
        var guard = 0
        while (node != null && guard++ < 64) {
            node.getDictionaryObject(key)?.let { return it }
            node = node.getDictionaryObject(COSName.PARENT) as? COSDictionary
        }
        return null
    }

    /**
     * @param order original 0-based page indices, in the order they should appear in [target].
     */
    fun rewrite(context: Context, source: File, order: List<Int>, target: File) {
        PDFBoxResourceLoader.init(context.applicationContext)
        if (order.isEmpty()) throw PdfEditException("A PDF needs at least one page")

        val doc = try {
            // Scratch files instead of RAM: big scanned books must not cause an OutOfMemoryError
            PDDocument.load(source, MemoryUsageSetting.setupTempFileOnly())
        } catch (e: InvalidPasswordException) {
            throw PdfEditException("This PDF is password-protected, so its pages can't be edited", e)
        } catch (e: Exception) {
            throw PdfEditException("Couldn't open this PDF for editing: ${e.message}", e)
        }

        doc.use { d ->
            val count = d.numberOfPages
            if (order.any { it !in 0 until count }) throw PdfEditException("Page list doesn't match the file")

            val pages = (0 until count).map { d.getPage(it) }

            // A page that inherits MediaBox/Resources/Rotate from a parent node would lose them when
            // moved to the root of the page tree — pin them on the page itself first.
            pages.forEach { page ->
                val dict = page.cosObject
                for (key in INHERITABLE) {
                    if (dict.getDictionaryObject(key) == null) {
                        inherited(dict, key)?.let { dict.setItem(key, it) }
                    }
                }
            }

            for (i in count - 1 downTo 0) d.removePage(i)
            order.forEach { d.addPage(pages[it]) }

            // Bookmarks (outline) point at the old page layout — drop them rather than leave them wrong
            d.documentCatalog.setDocumentOutline(null)
            // Opened with the empty user password: save an unrestricted copy
            d.setAllSecurityToBeRemoved(true)

            try {
                d.save(target)
            } catch (e: Exception) {
                target.delete()
                throw PdfEditException("Couldn't save the edited PDF: ${e.message}", e)
            }
        }
    }
}

/** File-name hygiene shared by the picker, the editor and the uploader. Returns the name WITHOUT ".pdf". */
fun cleanBookName(raw: String): String {
    val cleaned = raw
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "-")
        .replace(Regex("\\s+"), " ")
        .trim()
        .removeSuffix(".pdf").removeSuffix(".PDF").trim()
        .take(120)
        .trim()
    return cleaned.ifEmpty { "Untitled" }
}

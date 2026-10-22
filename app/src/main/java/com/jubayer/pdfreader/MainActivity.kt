package com.jubayer.pdfreader

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

class MainActivity : AppCompatActivity() {

    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var currentUri: Uri? = null
    private var pageIndex = 0
    private var darkMode = false

    private lateinit var root: LinearLayout
    private lateinit var pageImage: ImageView
    private lateinit var status: TextView
    private lateinit var previous: Button
    private lateinit var next: Button
    private lateinit var searchInput: EditText
    private lateinit var pageInput: EditText

    private val blue = Color.rgb(23, 105, 224)

    private val picker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) {
            uri: Uri? ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }
                saveRecent(uri)
                openPdf(uri, null)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        showHome()
    }

    private fun prefs() =
        getSharedPreferences("pdf_reader", MODE_PRIVATE)

    private fun lastPageKey(uri: Uri) = "last_${uri}"

    private fun bookmarkKey(uri: Uri) = "bookmarks_${uri}"

    private fun saveRecent(uri: Uri) {
        val old = prefs().getString("recent", "") ?: ""
        val list = (listOf(uri.toString()) + old.split("\n")
            .filter { it.isNotBlank() && it != uri.toString() })
            .take(15)
        prefs().edit().putString("recent", list.joinToString("\n")).apply()
    }

    private fun showHome() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 24)
            setBackgroundColor(Color.rgb(245, 247, 251))
        }

        root.addView(TextView(this).apply {
            text = "PDF Reader"
            textSize = 30f
            setTextColor(Color.rgb(25, 45, 75))
        })

        root.addView(TextView(this).apply {
            text = "Read, search and save your documents"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 5, 0, 20)
        })

        root.addView(Button(this).apply {
            text = "＋ Open PDF"
            setTextColor(Color.WHITE)
            setBackgroundColor(blue)
            setOnClickListener {
                picker.launch(arrayOf("application/pdf"))
            }
        })

        root.addView(TextView(this).apply {
            text = "Recent PDFs"
            textSize = 20f
            setTextColor(Color.rgb(25, 45, 75))
            setPadding(0, 24, 0, 8)
        })

        val recent = prefs().getString("recent", "") ?: ""
        val items = recent.split("\n").filter { it.isNotBlank() }

        if (items.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "Your recent PDFs will appear here."
                setTextColor(Color.DKGRAY)
            })
        } else {
            items.forEach { value ->
                val uri = Uri.parse(value)
                root.addView(Button(this).apply {
                    text = "📄  ${uri.lastPathSegment ?: "PDF Document"}"
                    isAllCaps = false
                    setOnClickListener {
                        val savedPage = prefs().getInt(lastPageKey(uri), 0)
                        openPdf(uri, savedPage)
                    }
                })
            }
        }

        setContentView(root)
    }

    private fun openPdf(uri: Uri, savedPage: Int?) {
        closePdf()
        try {
            descriptor = contentResolver.openFileDescriptor(uri, "r")
            val fd = descriptor ?: throw Exception("Cannot access this file")
            renderer = PdfRenderer(fd)
            currentUri = uri
            val count = renderer?.pageCount ?: 1
            pageIndex = (savedPage ?: 0).coerceIn(0, count - 1)
            darkMode = prefs().getBoolean("dark_mode", false)
            showReader()
            renderPage()
        } catch (e: Exception) {
            closePdf()
            message("Unable to open PDF: ${e.message}")
        }
    }

    private fun showReader() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
            setBackgroundColor(if (darkMode) Color.rgb(30, 30, 30) else Color.rgb(245, 247, 251))
        }

        root.addView(Button(this).apply {
            text = "← Home"
            setOnClickListener {
                closePdf()
                showHome()
            }
        })

        status = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(4, 8, 4, 8)
            setTextColor(if (darkMode) Color.WHITE else Color.DKGRAY)
        }
        root.addView(status)

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        searchInput = EditText(this).apply {
            hint = "Search text in PDF"
            textSize = 14f
            setSingleLine(true)
            setTextColor(if (darkMode) Color.WHITE else Color.BLACK)
            setHintTextColor(Color.GRAY)
        }

        searchRow.addView(searchInput, LinearLayout.LayoutParams(0, -2, 1f))
        searchRow.addView(Button(this).apply {
            text = "Search"
            setOnClickListener { searchPdf() }
        })
        root.addView(searchRow)

        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        pageInput = EditText(this).apply {
            hint = "Page no."
            textSize = 14f
            inputType = 2
            setTextColor(if (darkMode) Color.WHITE else Color.BLACK)
        }
        tools.addView(pageInput, LinearLayout.LayoutParams(0, -2, 1f))

        tools.addView(Button(this).apply {
            text = "Go"
            setOnClickListener {
                val target = pageInput.text.toString().toIntOrNull()
                val count = renderer?.pageCount ?: 0
                if (target != null && target in 1..count) {
                    pageIndex = target - 1
                    renderPage()
                } else {
                    message("Enter a page from 1 to $count")
                }
            }
        })

        tools.addView(Button(this).apply {
            text = "Bookmark"
            setOnClickListener { toggleBookmark() }
        })

        tools.addView(Button(this).apply {
            text = "Saved"
            setOnClickListener { showBookmarks() }
        })

        root.addView(tools)

        root.addView(Button(this).apply {
            text = if (darkMode) "☀ Light Mode" else "🌙 Dark Mode"
            setOnClickListener {
                darkMode = !darkMode
                prefs().edit().putBoolean("dark_mode", darkMode).apply()
                showReader()
                renderPage()
            }
        })

        pageImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            contentDescription = "PDF page"
            setBackgroundColor(if (darkMode) Color.rgb(40, 40, 40) else Color.WHITE)
        }

        root.addView(pageImage, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val nav = LinearLayout(this).apply {
            gravity = Gravity.CENTER
        }

        previous = Button(this).apply {
            text = "Previous"
            setOnClickListener {
                if (pageIndex > 0) {
                    pageIndex--
                    renderPage()
                }
            }
        }

        next = Button(this).apply {
            text = "Next"
            setOnClickListener {
                val count = renderer?.pageCount ?: 0
                if (pageIndex < count - 1) {
                    pageIndex++
                    renderPage()
                }
            }
        }

        nav.addView(previous)
        nav.addView(next)
        root.addView(nav)
        setContentView(root)
    }

    private fun renderPage() {
        val pdf = renderer ?: return
        try {
            val page = pdf.openPage(pageIndex)
            val bitmap = Bitmap.createBitmap(
                page.width, page.height, Bitmap.Config.ARGB_8888
            )
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            pageImage.colorFilter = if (darkMode) {
                ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
                )))
            } else null

            pageImage.setImageBitmap(bitmap)
            status.text = "Page ${pageIndex + 1} of ${pdf.pageCount}"
            previous.isEnabled = pageIndex > 0
            next.isEnabled = pageIndex < pdf.pageCount - 1

            currentUri?.let { uri ->
                prefs().edit().putInt(lastPageKey(uri), pageIndex).apply()
            }
        } catch (_: Exception) {
            message("Could not render this page")
        }
    }

    private fun searchPdf() {
        val query = searchInput.text.toString().trim()
        val uri = currentUri
        if (query.isEmpty() || uri == null) {
            message("Enter a word to search")
            return
        }

        try {
            contentResolver.openInputStream(uri).use { stream ->
                if (stream == null) throw Exception("Cannot read PDF")
                PDDocument.load(stream).use { document ->
                    val stripper = PDFTextStripper()
                    for (i in 1..document.numberOfPages) {
                        stripper.startPage = i
                        stripper.endPage = i
                        val text = stripper.getText(document)
                        if (text.contains(query, ignoreCase = true)) {
                            pageIndex = i - 1
                            renderPage()
                            message("Found on page $i")
                            return
                        }
                    }
                }
            }
            message("Text not found in this PDF")
        } catch (e: Exception) {
            message("Search failed: ${e.message}")
        }
    }

    private fun toggleBookmark() {
        val uri = currentUri ?: return
        val saved = prefs().getStringSet(bookmarkKey(uri), emptySet())
            ?.toMutableSet() ?: mutableSetOf()
        val page = pageIndex.toString()

        if (saved.contains(page)) {
            saved.remove(page)
            message("Bookmark removed")
        } else {
            saved.add(page)
            message("Page bookmarked")
        }

        prefs().edit().putStringSet(bookmarkKey(uri), saved).apply()
    }

    private fun showBookmarks() {
        val uri = currentUri ?: return
        val saved = prefs().getStringSet(bookmarkKey(uri), emptySet())
            ?.mapNotNull { it.toIntOrNull() }
            ?.sorted() ?: emptyList()

        if (saved.isEmpty()) {
            message("No bookmarks saved yet")
            return
        }

        val labels = saved.map { "Page ${it + 1}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Bookmarks")
            .setItems(labels) { _, which ->
                pageIndex = saved[which]
                renderPage()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun closePdf() {
        try {
            renderer?.close()
        } catch (_: Exception) {
        }
        renderer = null
        try {
            descriptor?.close()
        } catch (_: Exception) {
        }
        descriptor = null
        currentUri = null
    }

    private fun message(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        closePdf()
        super.onDestroy()
    }
}

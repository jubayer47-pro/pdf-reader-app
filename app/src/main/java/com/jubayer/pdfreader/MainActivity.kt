package com.jubayer.pdfreader

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.OnBackPressedCallback
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
    private var touchStartX = 0f
    private var touchStartY = 0f

    private lateinit var pageImage: ImageView
    private lateinit var status: TextView
    private lateinit var previous: Button
    private lateinit var next: Button
    private lateinit var searchInput: EditText
    private lateinit var pageInput: EditText

    private val blue = Color.rgb(23, 105, 224)

    private fun prefs() =
        getSharedPreferences("pdf_reader", MODE_PRIVATE)

    private fun lastKey(uri: Uri) = "last_${uri}"
    private fun bookmarkKey(uri: Uri) = "bookmarks_${uri}"

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

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (renderer != null) {
                        closePdf()
                        showHome()
                    } else {
                        finish()
                    }
                }
            }
        )

        showHome()
    }

    private fun saveRecent(uri: Uri) {
        val old = prefs().getString("recent", "") ?: ""
        val items = (listOf(uri.toString()) + old.split("\n")
            .filter { it.isNotBlank() && it != uri.toString() })
            .take(20)

        prefs().edit()
            .putString("recent", items.joinToString("\n"))
            .apply()
    }

    private fun displayName(uri: Uri): String {
        val name = uri.lastPathSegment ?: "PDF Document"
        return Uri.decode(name.substringAfterLast('/'))
    }

    private fun showHome() {
        val dark = prefs().getBoolean("dark_mode", false)
        darkMode = dark

        val bg = if (dark) Color.rgb(24, 27, 33)
                 else Color.rgb(245, 247, 251)
        val fg = if (dark) Color.WHITE
                 else Color.rgb(32, 41, 56)

        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility =
            if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 28, 22, 24)
            setBackgroundColor(bg)
        }

        content.addView(TextView(this).apply {
            text = "PDF Reader"
            textSize = 30f
            setTextColor(fg)
        })

        content.addView(TextView(this).apply {
            text = "Your documents, your reading space"
            textSize = 14f
            setTextColor(if (dark) Color.LTGRAY else Color.DKGRAY)
            setPadding(0, 6, 0, 20)
        })

        content.addView(Button(this).apply {
            text = "＋  Open PDF"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(blue)
            setOnClickListener {
                picker.launch(arrayOf("application/pdf"))
            }
        })

        content.addView(Button(this).apply {
            text = if (dark) "☀  Light Mode" else "🌙  Dark Mode"
            isAllCaps = false
            setOnClickListener {
                prefs().edit().putBoolean("dark_mode", !dark).apply()
                showHome()
            }
        })

        content.addView(TextView(this).apply {
            text = "Recent PDFs"
            textSize = 21f
            setTextColor(fg)
            setPadding(0, 22, 0, 10)
        })

        val recent = prefs().getString("recent", "") ?: ""
        val items = recent.split("\n").filter { it.isNotBlank() }
        if (items.isNotEmpty()) {
    val lastUri = Uri.parse(items.first())

    content.addView(Button(this).apply {
        text = "▶  Continue Reading — ${displayName(lastUri)}"
        isAllCaps = false
        setOnClickListener {
            openPdf(
                lastUri,
                prefs().getInt(lastKey(lastUri), 0)
            )
        }
    })
        }
        if (items.isNotEmpty()) {
            content.addView(Button(this).apply {
                text = "Clear Recent PDFs"
                isAllCaps = false

                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Clear Recent PDFs?")
                        .setMessage(
                            "Remove all files from the recent list?"
                        )
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Clear") { _, _ ->
                            prefs().edit()
                                .putString("recent", "")
                                .apply()

                            showHome()
                        }
                        .show()
                }
            })
        }

        if (items.isEmpty()) {
            content.addView(TextView(this).apply {
                text = "No recent PDFs yet. Tap Open PDF to begin."
                textSize = 15f
                setTextColor(if (dark) Color.LTGRAY else Color.DKGRAY)
                setPadding(0, 12, 0, 12)
            })
        } else {
            items.forEach { value ->
                val uri = Uri.parse(value)
                content.addView(Button(this).apply {
                    text = "📄  ${displayName(uri)}"
                    isAllCaps = false
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setOnClickListener {
                        openPdf(uri, prefs().getInt(lastKey(uri), 0))
                    }
                })
            }
        }

        val scroll = ScrollView(this)
        scroll.addView(content)
        setContentView(scroll)
    }

    private fun openPdf(uri: Uri, savedPage: Int?) {
        closePdf()

        try {
            descriptor = contentResolver.openFileDescriptor(uri, "r")
                ?: throw Exception("Cannot access this file")

            renderer = PdfRenderer(descriptor!!)
            currentUri = uri

            val count = renderer!!.pageCount
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
        val bg = if (darkMode) Color.rgb(24, 27, 33)
                 else Color.rgb(245, 247, 251)
        val fg = if (darkMode) Color.WHITE
                 else Color.rgb(32, 41, 56)

        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility =
            if (darkMode) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
            setBackgroundColor(bg)
        }

        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }

        top.addView(Button(this).apply {
            text = "← Home"
            isAllCaps = false
            setOnClickListener {
                closePdf()
                showHome()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))

        top.addView(Button(this).apply {
            text = if (darkMode) "☀ Light" else "🌙 Dark"
            isAllCaps = false
            setOnClickListener {
                darkMode = !darkMode
                prefs().edit().putBoolean("dark_mode", darkMode).apply()
                showReader()
                renderPage()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))

        root.addView(top)

        status = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(4, 8, 4, 8)
            setTextColor(fg)
        }
        root.addView(status)

        val searchRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }

        searchInput = EditText(this).apply {
            hint = "Search text in PDF"
            setSingleLine(true)
            textSize = 14f
            setTextColor(fg)
            setHintTextColor(Color.GRAY)
        }

        searchRow.addView(
            searchInput,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        searchRow.addView(Button(this).apply {
            text = "Search"
            isAllCaps = false
            setOnClickListener { searchPdf() }
        })

        root.addView(searchRow)

        val pageRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }

        pageInput = EditText(this).apply {
            hint = "Page number"
            inputType = 2
            setSingleLine(true)
            textSize = 14f
            setTextColor(fg)
            setHintTextColor(Color.GRAY)
        }

        pageRow.addView(
            pageInput,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        pageRow.addView(Button(this).apply {
            text = "Go"
            isAllCaps = false
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

        pageRow.addView(Button(this).apply {
            text = "Bookmark"
            isAllCaps = false
            setOnClickListener { toggleBookmark() }
        })

        pageRow.addView(Button(this).apply {
            text = "Saved"
            isAllCaps = false
            setOnClickListener { showBookmarks() }
        })

        root.addView(pageRow)

        pageImage = ImageView(this).apply {
    scaleType = ImageView.ScaleType.FIT_CENTER
    adjustViewBounds = true
    contentDescription = "PDF page"
    setBackgroundColor(Color.WHITE)

    setOnTouchListener { _, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
                true
            }

            MotionEvent.ACTION_UP -> {
                val dx = event.x - touchStartX
                val dy = event.y - touchStartY

                if (kotlin.math.abs(dx) > 80 &&
                    kotlin.math.abs(dx) > kotlin.math.abs(dy)
                ) {
                    val count = renderer?.pageCount ?: 0

                    if (dx < 0 && pageIndex < count - 1) {
                        pageIndex++
                        renderPage()
                    } else if (dx > 0 && pageIndex > 0) {
                        pageIndex--
                        renderPage()
                    }
                }
                true
            }

            else -> true
        }
    }
        }

        root.addView(
            pageImage,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )

        val navigation = LinearLayout(this).apply {
            gravity = Gravity.CENTER
        }

        previous = Button(this).apply {
            text = "Previous"
            isAllCaps = false
            setOnClickListener {
                if (pageIndex > 0) {
                    pageIndex--
                    renderPage()
                }
            }
        }

        next = Button(this).apply {
            text = "Next"
            isAllCaps = false
            setOnClickListener {
                val count = renderer?.pageCount ?: 0
                if (pageIndex < count - 1) {
                    pageIndex++
                    renderPage()
                }
            }
        }

        navigation.addView(previous, LinearLayout.LayoutParams(0, -2, 1f))
        navigation.addView(next, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(navigation)

        setContentView(root)
    }

    private fun renderPage() {
        val pdf = renderer ?: return

        try {
            val page = pdf.openPage(pageIndex)

            val bitmap = Bitmap.createBitmap(
                page.width,
                page.height,
                Bitmap.Config.ARGB_8888
            )

            bitmap.eraseColor(Color.WHITE)
            page.render(
                bitmap, null, null,
                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
            )
            page.close()

            // Keep the PDF page's original colors in both modes.
            pageImage.colorFilter = null
            pageImage.setImageBitmap(bitmap)

            status.text = "Page ${pageIndex + 1} of ${pdf.pageCount}"
            previous.isEnabled = pageIndex > 0
            next.isEnabled = pageIndex < pdf.pageCount - 1

            currentUri?.let { uri ->
                prefs().edit().putInt(lastKey(uri), pageIndex).apply()
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
        val resultPages = mutableListOf<Int>()
        val resultLabels = mutableListOf<String>()

        contentResolver.openInputStream(uri).use { stream ->
            if (stream == null) {
                throw Exception("Cannot read PDF")
            }

            PDDocument.load(stream).use { document ->
                val stripper = PDFTextStripper()

                for (i in 1..document.numberOfPages) {
                    stripper.startPage = i
                    stripper.endPage = i

                    val text = stripper.getText(document)

                    if (text.contains(query, ignoreCase = true)) {
                        resultPages.add(i)

                        val cleanText = text
                            .replace("\n", " ")
                            .replace(Regex("\\s+"), " ")
                            .trim()

                        val position = cleanText.indexOf(
                            query,
                            ignoreCase = true
                        )

                        val snippet = if (position >= 0) {
                            val start = (position - 35).coerceAtLeast(0)
                            val end = (position + query.length + 55)
                                .coerceAtMost(cleanText.length)

                            cleanText.substring(start, end)
                        } else {
                            "Matching text found"
                        }

                        resultLabels.add("Page $i\n$snippet")
                    }
                }
            }
        }

        if (resultPages.isEmpty()) {
            message("Text not found. Scanned PDFs may need OCR.")
            return
        }

        AlertDialog.Builder(this)
            .setTitle(
                "Search Results: ${resultPages.size} page(s)"
            )
            .setItems(resultLabels.toTypedArray()) { _, which ->
                pageIndex = resultPages[which] - 1
                renderPage()
            }
            .setNegativeButton("Close", null)
            .show()

    } catch (e: Exception) {
        message("Search failed: ${e.message}")
    }
    }
    private fun toggleBookmark() {
    val uri = currentUri ?: return

    val saved = (
        prefs().getStringSet(bookmarkKey(uri), emptySet())
            ?: emptySet()
    ).toMutableSet()

    val page = pageIndex.toString()

    if (saved.contains(page)) {
        saved.remove(page)
        message("Bookmark removed")
    } else {
        saved.add(page)
        message("Page bookmarked")
    }

    prefs().edit()
        .putStringSet(bookmarkKey(uri), saved)
        .apply()
    }

    private fun showBookmarks() {
        val uri = currentUri ?: return

        val saved = (prefs().getStringSet(bookmarkKey(uri), emptySet())
            ?: emptySet())
            .mapNotNull { it.toIntOrNull()  }
            .sorted()

        if (saved.isEmpty()) {
            message("No bookmarks saved yet")
            return
        }

        val labels = saved.map { "Page ${it + 1}" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Saved Bookmarks")
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

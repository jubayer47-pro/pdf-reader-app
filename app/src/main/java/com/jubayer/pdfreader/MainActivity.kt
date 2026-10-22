package com.jubayer.pdfreader

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var currentUri: Uri? = null
    private var pageIndex = 0
    private var darkMode = false

    private var zoomScale = 1f
    private var pinchStartDistance = 0f
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var wasPinching = false

    private var annotationMode = "NONE"

    private lateinit var pageImage: ImageView
    private lateinit var pageLayer: FrameLayout
    private lateinit var viewport: FrameLayout
    private lateinit var annotationOverlay: AnnotationOverlay
    private lateinit var status: TextView
    private lateinit var previous: Button
    private lateinit var next: Button
    private lateinit var searchInput: EditText
    private lateinit var pageInput: EditText

    private val blue = Color.rgb(23, 105, 224)

    private data class Mark(
        val type: String,
        val points: MutableList<PointF>
    )

    private fun prefs() =
        getSharedPreferences("pdf_reader", MODE_PRIVATE)

    private fun lastKey(uri: Uri) = "last_$uri"
    private fun bookmarkKey(uri: Uri) = "bookmarks_$uri"

    private fun noteKey(uri: Uri, page: Int) =
        "note_${uri}_$page"

    private fun annotationKey(uri: Uri, page: Int) =
        "annotations_${uri}_$page"

    private val picker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) {
            uri: Uri? ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
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
        val items = (
            listOf(uri.toString()) +
                old.split("\n").filter {
                    it.isNotBlank() && it != uri.toString()
                }
            ).take(20)

        prefs().edit()
            .putString("recent", items.joinToString("\n"))
            .apply()
    }

    private fun displayName(uri: Uri): String {
        val name = uri.lastPathSegment ?: "PDF Document"
        return Uri.decode(name.substringAfterLast('/'))
    }

    private fun styleWindow(dark: Boolean) {
        val bg = if (dark) Color.rgb(24, 27, 33)
        else Color.rgb(245, 247, 251)

        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility =
            if (dark) 0 else
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }

    private fun showHome() {
        darkMode = prefs().getBoolean("dark_mode", false)

        val bg = if (darkMode) Color.rgb(24, 27, 33)
        else Color.rgb(245, 247, 251)

        val fg = if (darkMode) Color.WHITE
        else Color.rgb(32, 41, 56)

        styleWindow(darkMode)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 24, 22, 24)
            setBackgroundColor(bg)
        }

        content.addView(TextView(this).apply {
            text = "PDF Reader"
            textSize = 30f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(fg)
        })

        content.addView(TextView(this).apply {
            text = "Your documents, your reading space"
            textSize = 14f
            setTextColor(if (darkMode) Color.LTGRAY else Color.DKGRAY)
            setPadding(0, 6, 0, 20)
        })

        val openButton = Button(this).apply {
            text = "＋  Open PDF"
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(blue)
            setOnClickListener {
                picker.launch(arrayOf("application/pdf"))
            }
        }
        content.addView(
            openButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        content.addView(Button(this).apply {
            text = if (darkMode) "☀  Light Mode" else "🌙  Dark Mode"
            isAllCaps = false
            setOnClickListener {
                prefs().edit()
                    .putBoolean("dark_mode", !darkMode)
                    .apply()
                showHome()
            }
        })

        content.addView(TextView(this).apply {
            text = "Recent PDFs"
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
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

            content.addView(Button(this).apply {
                text = "Clear Recent PDFs"
                isAllCaps = false
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Clear Recent PDFs?")
                        .setMessage("Remove all files from the recent list?")
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
                setTextColor(if (darkMode) Color.LTGRAY else Color.DKGRAY)
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
            descriptor =
                contentResolver.openFileDescriptor(uri, "r")
                    ?: throw Exception("Cannot access this file")

            renderer = PdfRenderer(descriptor!!)
            currentUri = uri

            val count = renderer!!.pageCount
            if (count <= 0) throw Exception("PDF has no pages")

            pageIndex = (savedPage ?: 0).coerceIn(0, count - 1)
            zoomScale = 1f
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

        styleWindow(darkMode)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(6, 6, 6, 6)
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
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(4, 6, 4, 6)
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

        val toolsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }

        val tools = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }

        tools.addView(Button(this).apply {
            text = "− Zoom"
            isAllCaps = false
            setOnClickListener {
                zoomScale = (zoomScale - 0.25f).coerceAtLeast(1f)
                updateZoom()
            }
        })

        tools.addView(Button(this).apply {
            text = "+ Zoom"
            isAllCaps = false
            setOnClickListener {
                zoomScale = (zoomScale + 0.25f).coerceAtMost(3f)
                updateZoom()
            }
        })

        tools.addView(Button(this).apply {
            text = if (annotationMode == "HIGHLIGHT")
                "✓ Highlight" else "🖍 Highlight"
            isAllCaps = false
            setOnClickListener {
                annotationMode =
                    if (annotationMode == "HIGHLIGHT") "NONE"
                    else "HIGHLIGHT"
                updateToolButtons()
            }
        })

        tools.addView(Button(this).apply {
            text = if (annotationMode == "UNDERLINE")
                "✓ Underline" else "Underline"
            isAllCaps = false
            setOnClickListener {
                annotationMode =
                    if (annotationMode == "UNDERLINE") "NONE"
                    else "UNDERLINE"
                updateToolButtons()
            }
        })

        tools.addView(Button(this).apply {
            text = "📝 Notes"
            isAllCaps = false
            setOnClickListener { editStickyNote() }
        })

        tools.addView(Button(this).apply {
            text = "Clear Marks"
            isAllCaps = false
            setOnClickListener { clearAnnotations() }
        })

        toolsScroll.addView(tools)
        root.addView(toolsScroll)

        // This viewport stays fixed in the reader layout.
        // Scaling the page layer cannot cover the controls above it.
        viewport = FrameLayout(this).apply {
            clipChildren = true
            clipToPadding = true
            setBackgroundColor(Color.rgb(225, 229, 236))
        }

        pageLayer = FrameLayout(this).apply {
            clipChildren = true
            clipToPadding = true
            setBackgroundColor(Color.WHITE)
        }

        pageImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = false
            contentDescription = "PDF page"
            setBackgroundColor(Color.WHITE)
        }

        annotationOverlay = AnnotationOverlay(this)

        pageLayer.addView(
            pageImage,
            FrameLayout.LayoutParams(-1, -1)
        )
        pageLayer.addView(
            annotationOverlay,
            FrameLayout.LayoutParams(-1, -1)
        )

        viewport.addView(
            pageLayer,
            FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
        )

        pageLayer.setOnTouchListener { _, event ->
            if (annotationMode != "NONE") {
                return@setOnTouchListener false
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.x
                    touchStartY = event.y
                    wasPinching = false
                    true
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    pinchStartDistance = pinchDistance(event)
                    wasPinching = true
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount >= 2) {
                        val distance = pinchDistance(event)

                        if (pinchStartDistance > 0f && distance > 0f) {
                            zoomScale = (
                                zoomScale * distance / pinchStartDistance
                            ).coerceIn(1f, 3f)

                            updateZoom()
                        }

                        pinchStartDistance = distance
                        wasPinching = true
                    }
                    true
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    pinchStartDistance = 0f
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val dx = event.x - touchStartX
                    val dy = event.y - touchStartY

                    if (!wasPinching &&
                        abs(dx) > 80 &&
                        abs(dx) > abs(dy)
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
                    pinchStartDistance = 0f
                    true
                }

                else -> true
            }
        }

        root.addView(
            viewport,
            LinearLayout.LayoutParams(-1, 0, 1f)
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

    private fun pinchDistance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    private fun updateZoom() {
        if (!::pageLayer.isInitialized) return
        pageLayer.scaleX = zoomScale
        pageLayer.scaleY = zoomScale
        pageLayer.invalidate()
    }

    private fun updateToolButtons() {
        if (!::annotationOverlay.isInitialized) return
        annotationOverlay.invalidate()
        message(
            when (annotationMode) {
                "HIGHLIGHT" -> "Drag your finger over the page to highlight"
                "UNDERLINE" -> "Drag your finger over the page to underline"
                else -> "Reading mode"
            }
        )
    }

    private fun renderPage() {
        val pdf = renderer ?: return

        try {
            pdf.openPage(pageIndex).use { page ->
                val bitmap = Bitmap.createBitmap(
                    page.width,
                    page.height,
                    Bitmap.Config.ARGB_8888
                )

                bitmap.eraseColor(Color.WHITE)

                page.render(
                    bitmap,
                    null,
                    null,
                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                )

                pageImage.setImageBitmap(bitmap)
            }

            status.text = "Page ${pageIndex + 1} of ${pdf.pageCount}"
            previous.isEnabled = pageIndex > 0
            next.isEnabled = pageIndex < pdf.pageCount - 1

            currentUri?.let { uri ->
                prefs().edit()
                    .putInt(lastKey(uri), pageIndex)
                    .apply()
            }

            if (::annotationOverlay.isInitialized) {
                annotationOverlay.marks.clear()
                annotationOverlay.marks.addAll(readAnnotations())
                annotationOverlay.invalidate()
            }
        } catch (_: Exception) {
            message("Could not render this page")
        }
    }

    private inner class AnnotationOverlay(context: Context) : View(context) {
        val marks = mutableListOf<Mark>()

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            marks.forEach { mark ->
                drawMark(canvas, mark)
            }
        }

        private fun drawMark(canvas: Canvas, mark: Mark) {
            if (mark.points.isEmpty()) return

            paint.color = if (mark.type == "HIGHLIGHT") {
                Color.argb(110, 255, 235, 59)
            } else {
                Color.rgb(25, 105, 224)
            }

            paint.strokeWidth = if (mark.type == "HIGHLIGHT") 25f else 4f

            val path = Path()
            mark.points.forEachIndexed { index, point ->
                val x = point.x * width
                val y = point.y * height

                if (index == 0) path.moveTo(x, y)
                else path.lineTo(x, y)
            }

            if (mark.points.size == 1) {
                val point = mark.points.first()
                canvas.drawCircle(
                    point.x * width,
                    point.y * height,
                    paint.strokeWidth / 2,
                    paint
                )
            } else {
                canvas.drawPath(path, paint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (annotationMode == "NONE") return false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val type = annotationMode
                    val mark = Mark(
                        type,
                        mutableListOf(
                            PointF(
                                (event.x / width.coerceAtLeast(1)).coerceIn(0f, 1f),
                                (event.y / height.coerceAtLeast(1)).coerceIn(0f, 1f)
                            )
                        )
                    )
                    marks.add(mark)
                    invalidate()
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val mark = marks.lastOrNull() ?: return true
                    mark.points.add(
                        PointF(
                            (event.x / width.coerceAtLeast(1)).coerceIn(0f, 1f),
                            (event.y / height.coerceAtLeast(1)).coerceIn(0f, 1f)
                        )
                    )
                    invalidate()
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    saveAnnotations()
                    invalidate()
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    saveAnnotations()
                    return true
                }
            }

            return true
        }
    }

    private fun saveAnnotations() {
        val uri = currentUri ?: return
        if (!::annotationOverlay.isInitialized) return

        val array = JSONArray()

        annotationOverlay.marks.forEach { mark ->
            val obj = JSONObject()
            obj.put("type", mark.type)

            val points = JSONArray()
            mark.points.forEach { point ->
                val pair = JSONArray()
                pair.put(point.x.toDouble())
                pair.put(point.y.toDouble())
                points.put(pair)
            }

            obj.put("points", points)
            array.put(obj)
        }

        prefs().edit()
            .putString(annotationKey(uri, pageIndex), array.toString())
            .apply()
    }

    private fun readAnnotations(): List<Mark> {
        val uri = currentUri ?: return emptyList()
        val raw = prefs().getString(annotationKey(uri, pageIndex), "[]")
            ?: "[]"

        return try {
            val array = JSONArray(raw)
            val result = mutableListOf<Mark>()

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val type = obj.getString("type")
                val pointsArray = obj.getJSONArray("points")
                val points = mutableListOf<PointF>()

                for (j in 0 until pointsArray.length()) {
                    val pair = pointsArray.getJSONArray(j)
                    points.add(
                        PointF(
                            pair.getDouble(0).toFloat(),
                            pair.getDouble(1).toFloat()
                        )
                    )
                }

                result.add(Mark(type, points))
            }

            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun clearAnnotations() {
        val uri = currentUri ?: return

        AlertDialog.Builder(this)
            .setTitle("Clear marks?")
            .setMessage("Remove all highlights and underlines from this page?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                annotationOverlay.marks.clear()
                prefs().edit()
                    .remove(annotationKey(uri, pageIndex))
                    .apply()
                annotationOverlay.invalidate()
                message("Marks cleared from this page")
            }
            .show()
    }

    private fun editStickyNote() {
        val uri = currentUri ?: return
        val key = noteKey(uri, pageIndex)

        val input = EditText(this).apply {
            hint = "Write your note for this page..."
            setText(prefs().getString(key, "") ?: "")
            minLines = 4
            maxLines = 10
            setPadding(24, 16, 24, 16)
        }

        AlertDialog.Builder(this)
            .setTitle("Note — Page ${pageIndex + 1}")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Delete") { _, _ ->
                prefs().edit().remove(key).apply()
                message("Note deleted")
            }
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) {
                    prefs().edit().remove(key).apply()
                } else {
                    prefs().edit().putString(key, text).apply()
                }
                message("Note saved")
            }
            .show()
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
                if (stream == null) throw Exception("Cannot read PDF")

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
                .setTitle("Search Results: ${resultPages.size} page(s)")
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

        val saved = (
            prefs().getStringSet(bookmarkKey(uri), emptySet())
                ?: emptySet()
            ).mapNotNull { it.toIntOrNull() }
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
        annotationMode = "NONE"
        zoomScale = 1f
    }

    private fun message(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        closePdf()
        super.onDestroy()
    }
}

package com.jubayer.pdfreader

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private var currentPageIndex = 0

    private lateinit var root: LinearLayout
    private lateinit var pageImage: ImageView
    private lateinit var pageStatus: TextView
    private lateinit var previousButton: Button
    private lateinit var nextButton: Button

    private val blue = Color.rgb(23, 105, 224)

    private val pdfPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }
                saveRecent(uri)
                openPdf(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHome()
    }

    private fun saveRecent(uri: Uri) {
        val prefs = getSharedPreferences("pdf_reader", MODE_PRIVATE)
        val old = prefs.getString("recent", "") ?: ""
        val list = (listOf(uri.toString()) + old.split("\n")
            .filter { it.isNotBlank() && it != uri.toString() })
            .take(10)
        prefs.edit().putString("recent", list.joinToString("\n")).apply()
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
            gravity = Gravity.CENTER_VERTICAL
        })

        root.addView(TextView(this).apply {
            text = "Your documents, all in one place"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 4, 0, 20)
        })

        root.addView(Button(this).apply {
            text = "＋  Open PDF"
            setTextColor(Color.WHITE)
            setBackgroundColor(blue)
            setOnClickListener {
                pdfPicker.launch(arrayOf("application/pdf"))
            }
        })

        root.addView(TextView(this).apply {
            text = "Recent PDFs"
            textSize = 20f
            setTextColor(Color.rgb(25, 45, 75))
            setPadding(0, 24, 0, 12)
        })

        val recent = getSharedPreferences("pdf_reader", MODE_PRIVATE)
            .getString("recent", "") ?: ""

        val list = recent.split("\n").filter { it.isNotBlank() }

        if (list.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "Your recently opened PDFs will appear here."
                textSize = 14f
                setTextColor(Color.DKGRAY)
                setPadding(0, 12, 0, 12)
            })
        } else {
            list.forEach { item ->
                val uri = Uri.parse(item)
                root.addView(Button(this).apply {
                    text = "📄  ${uri.lastPathSegment ?: "PDF Document"}"
                    isAllCaps = false
                    setOnClickListener { openPdf(uri) }
                })
            }
        }

        setContentView(root)
    }

    private fun openPdf(uri: Uri) {
        closePdf()
        try {
            fileDescriptor = contentResolver.openFileDescriptor(uri, "r")
            val descriptor = fileDescriptor ?: run {
                showMessage("Could not open this PDF")
                return
            }
            pdfRenderer = PdfRenderer(descriptor)
            currentPageIndex = 0
            showReader()
            renderCurrentPage()
        } catch (e: Exception) {
            closePdf()
            showMessage("Unable to open PDF: ${e.message}")
        }
    }

    private fun showReader() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
            setBackgroundColor(Color.rgb(245, 247, 251))
        }

        root.addView(Button(this).apply {
            text = "← Home"
            setOnClickListener {
                closePdf()
                showHome()
            }
        })

        pageStatus = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
            setPadding(4, 12, 4, 12)
        }
        root.addView(pageStatus)

        pageImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.WHITE)
            contentDescription = "PDF page preview"
        }

        root.addView(pageImage, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val navigation = LinearLayout(this).apply {
            gravity = Gravity.CENTER
        }

        previousButton = Button(this).apply {
            text = "Previous"
            setOnClickListener {
                if (currentPageIndex > 0) {
                    currentPageIndex--
                    renderCurrentPage()
                }
            }
        }

        nextButton = Button(this).apply {
            text = "Next"
            setOnClickListener {
                val renderer = pdfRenderer
                if (renderer != null && currentPageIndex < renderer.pageCount - 1) {
                    currentPageIndex++
                    renderCurrentPage()
                }
            }
        }

        navigation.addView(previousButton)
        navigation.addView(nextButton)
        root.addView(navigation)
        setContentView(root)
    }

    private fun renderCurrentPage() {
        val renderer = pdfRenderer ?: return
        try {
            val page = renderer.openPage(currentPageIndex)
            val bitmap = Bitmap.createBitmap(
                page.width, page.height, Bitmap.Config.ARGB_8888
            )
            bitmap.eraseColor(Color.WHITE)
            page.render(
                bitmap, null, null,
                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
            )
            page.close()
            pageImage.setImageBitmap(bitmap)
            pageStatus.text = "Page ${currentPageIndex + 1} of ${renderer.pageCount}"
            previousButton.isEnabled = currentPageIndex > 0
            nextButton.isEnabled = currentPageIndex < renderer.pageCount - 1
        } catch (_: Exception) {
            showMessage("Could not render this page")
        }
    }

    private fun closePdf() {
        try {
            pdfRenderer?.close()
        } catch (_: Exception) {
        }
        pdfRenderer = null
        try {
            fileDescriptor?.close()
        } catch (_: Exception) {
        }
        fileDescriptor = null
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        closePdf()
        super.onDestroy()
    }
}

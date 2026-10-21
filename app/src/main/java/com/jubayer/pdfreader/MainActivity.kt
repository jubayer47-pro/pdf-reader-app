package com.jubayer.pdfreader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import android.net.Uri

class MainActivity : AppCompatActivity() {

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private var currentPageIndex = 0

    private lateinit var pageImage: ImageView
    private lateinit var pageStatus: TextView
    private lateinit var previousButton: Button
    private lateinit var nextButton: Button

    private val blue = Color.rgb(23, 105, 224)

    private val pdfPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                openPdf(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createHomeScreen()
    }

    private fun createHomeScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 24, 20, 16)
            setBackgroundColor(Color.rgb(245, 247, 251))
        }

        val title = TextView(this).apply {
            text = "PDF Reader"
            textSize = 28f
            setTextColor(Color.rgb(32, 41, 56))
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "Your documents, all in one place"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
        }

        val openButton = Button(this).apply {
            text = "Open PDF"
            setTextColor(Color.WHITE)
            setBackgroundColor(blue)
            setOnClickListener {
                pdfPicker.launch(arrayOf("application/pdf"))
            }
        }

        pageStatus = TextView(this).apply {
            text = "Open a PDF to start reading"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(32, 41, 56))
            setPadding(4, 12, 4, 12)
        }

        pageImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            setBackgroundColor(Color.WHITE)
            contentDescription = "PDF page preview"
        }

        val pageArea = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(
                pageImage,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        previousButton = Button(this).apply {
            text = "Previous"
            isEnabled = false
            setOnClickListener {
                if (currentPageIndex > 0) {
                    currentPageIndex--
                    renderCurrentPage()
                }
            }
        }

        nextButton = Button(this).apply {
            text = "Next"
            isEnabled = false
            setOnClickListener {
                val renderer = pdfRenderer
                if (renderer != null &&
                    currentPageIndex < renderer.pageCount - 1
                ) {
                    currentPageIndex++
                    renderCurrentPage()
                }
            }
        }

        navigation.addView(previousButton)
        navigation.addView(nextButton)

        root.addView(title)
        root.addView(subtitle)
        root.addView(
            openButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 12
            }
        )
        root.addView(pageStatus)

        root.addView(
            pageArea,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        root.addView(navigation)

        setContentView(root)
    }

    private fun openPdf(uri: Uri) {
        closePdf()

        try {
            fileDescriptor =
                contentResolver.openFileDescriptor(uri, "r")

            val descriptor = fileDescriptor
            if (descriptor == null) {
                showMessage("Could not open this PDF")
                return
            }

            pdfRenderer = PdfRenderer(descriptor)
            currentPageIndex = 0
            renderCurrentPage()

        } catch (e: Exception) {
            closePdf()
            showMessage("Unable to open PDF: ${e.message}")
        }
    }

    private fun renderCurrentPage() {
        val renderer = pdfRenderer ?: return

        try {
            val page = renderer.openPage(currentPageIndex)

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

            page.close()

            pageImage.setImageBitmap(bitmap)

            pageStatus.text =
                "Page ${currentPageIndex + 1} of ${renderer.pageCount}"

            previousButton.isEnabled = currentPageIndex > 0
            nextButton.isEnabled =
                currentPageIndex < renderer.pageCount - 1

        } catch (e: Exception) {
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
        pageImageOrNull()
    }

    private fun pageImageOrNull() {
        if (::pageImage.isInitialized) {
            pageImage.setImageDrawable(null)
        }
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        closePdf()
        super.onDestroy()
    }
}

package com.example.lab5

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var etIssue1: EditText
    private lateinit var etIssue2: EditText
    private lateinit var tvStatus: TextView
    private lateinit var btnDownload: Button
    private lateinit var btnView: Button
    private lateinit var btnDelete: Button

    // Папка для збереження завантажених журналів
    private lateinit var downloadFolder: File

    // Поточний завантажений файл
    private var currentFile: File? = null

    // Фоновий потік для завантаження
    private val executor = Executors.newSingleThreadExecutor()

    // Handler для оновлення інтерфейсу
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etIssue1 = findViewById(R.id.etIssue1)
        etIssue2 = findViewById(R.id.etIssue2)
        tvStatus = findViewById(R.id.tvStatus)
        btnDownload = findViewById(R.id.btnDownload)
        btnView = findViewById(R.id.btnView)
        btnDelete = findViewById(R.id.btnDelete)

        // Створення папки під час першого запуску
        createDownloadFolder()

        // Перевірка наявності раніше завантаженого файлу
        btnDownload.setOnClickListener {
            downloadJournal()
        }

        btnView.setOnClickListener {
            openPdfFile()
        }

        btnDelete.setOnClickListener {
            deletePdfFile()
        }

        // Відображення інструкції, якщо користувач раніше її не приховав
        val preferences = getSharedPreferences(
            "AppSettings",
            MODE_PRIVATE
        )

        val dontShowAgain = preferences.getBoolean(
            "dontShowInstructions",
            false
        )

        if (!dontShowAgain) {
            findViewById<android.view.View>(android.R.id.content).post {
                if (!isFinishing && !isDestroyed) {
                    showInstructions()
                }
            }
        }
    }

    // Створення папки для журналів
    private fun createDownloadFolder() {
        val documentsFolder = getExternalFilesDir(
            Environment.DIRECTORY_DOCUMENTS
        )

        downloadFolder = File(documentsFolder, "JournalPDFs")

        if (!downloadFolder.exists()) {
            downloadFolder.mkdirs()
        }
    }

    // Асинхронне завантаження журналу
    private fun downloadJournal() {
        val issueNumber1 = etIssue1.text.toString().trim()
        val issueNumber2 = etIssue2.text.toString().trim()

        if (issueNumber1.isEmpty() || issueNumber2.isEmpty()) {
            tvStatus.text = "Введіть номер випуску"
            return
        }

        if (!issueNumber1.matches(Regex("\\d+")) || !issueNumber2.matches(Regex("\\d+"))) {
            tvStatus.text = "Номер випуску повинен містити лише цифри"
            return
        }

        btnDownload.isEnabled = false
        btnView.isEnabled = false
        btnDelete.isEnabled = false

        tvStatus.text = "Завантаження..."

        executor.execute {
            var connection: HttpURLConnection? = null

            try {
                val url = URL(
                    "https://chemengine.kpi.ua/article/download/${issueNumber1}/${issueNumber2}"
                )

                // https://chemengine.kpi.ua/article/view/372389/356002
                // https://chemengine.kpi.ua/article/view/372392/356003
                // https://chemengine.kpi.ua/article/view/372393/356004

                connection = url.openConnection() as HttpURLConnection

                connection.requestMethod = "GET"
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.instanceFollowRedirects = true

                connection.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0"
                )

                connection.setRequestProperty(
                    "Accept",
                    "application/pdf,*/*"
                )

                val responseCode = connection.responseCode
                val contentType = connection.contentType
                val finalUrl = connection.url.toString()

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception(
                        "HTTP: $responseCode\nURL: $finalUrl"
                    )
                }

                val tempFile = File(
                    downloadFolder,
                    "issue_${issueNumber1}_${issueNumber2}.tmp"
                )

                connection.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }

                val header = ByteArray(5)

                val isPdf = tempFile.inputStream().use { input ->
                    input.read(header) == 5 &&
                            String(header, Charsets.US_ASCII) == "%PDF-"
                }

                if (!isPdf) {
                    tempFile.delete()

                    throw Exception(
                        "Отримано не PDF-файл.\n" +
                                "Тип відповіді: $contentType\n" +
                                "HTTP: $responseCode\n" +
                                "URL: $finalUrl"
                    )
                }

                val destinationFile = File(
                    downloadFolder,
                    "issue_${issueNumber1}_${issueNumber2}.pdf"
                )

                // Переміщення тимчасового файлу до кінцевого
                if (destinationFile.exists()) {
                    destinationFile.delete()
                }

                if (!tempFile.renameTo(destinationFile)) {
                    tempFile.delete()
                    throw Exception("Не вдалося зберегти файл")
                }

                mainHandler.post {
                    currentFile = destinationFile
                    tvStatus.text =
                        "Випуск №${issueNumber1}_${issueNumber2} успішно завантажено"

                    btnView.isEnabled = true
                    btnDelete.isEnabled = true
                    btnDownload.isEnabled = true
                }

            } catch (e: Exception) {
                mainHandler.post {
                    tvStatus.text = e.message ?: "Помилка завантаження"
                    btnDownload.isEnabled = true
                    btnView.isEnabled = false
                    btnDelete.isEnabled = false
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    // Відкриття завантаженого PDF-файлу
    private fun openPdfFile() {
        val file = currentFile

        if (file == null || !file.exists()) {
            tvStatus.text = "Файл не знайдений на пристрої"
            btnView.isEnabled = false
            btnDelete.isEnabled = false
            return
        }

        try {
            val uri: Uri = FileProvider.getUriForFile(
                this,
                "$packageName.provider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(intent)

        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                this,
                "На пристрої не встановлено програму для відкриття PDF",
                Toast.LENGTH_LONG
            ).show()

        } catch (e: Exception) {
            tvStatus.text = "Не вдалося відкрити файл: ${e.message}"
        }
    }

    // Видалення завантаженого файлу
    private fun deletePdfFile() {
        val preferences = getSharedPreferences(
            "AppSettings",
            MODE_PRIVATE
        )

        preferences.edit()
            .putBoolean("dontShowInstructions", false)
            .apply()
        val file = currentFile

        if (file != null && file.exists()) {
            if (file.delete()) {
                currentFile = null

                tvStatus.text = "Файл успішно видалено"

                btnView.isEnabled = false
                btnDelete.isEnabled = false

            } else {
                tvStatus.text = "Не вдалося видалити файл"
            }
        } else {
            tvStatus.text = "Файл не знайдений"
            btnView.isEnabled = false
            btnDelete.isEnabled = false
        }
    }

    // PopupWindow з інструкцією
    private fun showInstructions() {

        // Напівпрозорий фон
        val background = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#99000000"))
            setPadding(30, 30, 30, 30)
        }

        // Вікно з інструкцією
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 35, 40, 35)
            setBackgroundColor(Color.WHITE)
        }

        val title = TextView(this).apply {
            text = "Інструкція"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        }

        val description = TextView(this).apply {
            text = "Введіть номер випуску журналу та натисніть " +
                    "«Завантажити». Після завершення завантаження " +
                    "ви зможете переглянути PDF-файл або видалити його."
            textSize = 16f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 25, 0, 25)
        }

        val checkBox = CheckBox(this).apply {
            text = "Більше не показувати"
            textSize = 14f
        }

        val buttonOk = Button(this).apply {
            text = "ОК"
        }

        content.addView(title)
        content.addView(description)
        content.addView(checkBox)
        content.addView(buttonOk)

        background.addView(
            content,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val popupWindow = PopupWindow(
            background,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            true
        )

        popupWindow.setBackgroundDrawable(
            ColorDrawable(Color.TRANSPARENT)
        )

        popupWindow.isOutsideTouchable = false

        buttonOk.setOnClickListener {

            if (checkBox.isChecked) {
                val preferences = getSharedPreferences(
                    "AppSettings",
                    MODE_PRIVATE
                )

                preferences.edit()
                    .putBoolean("dontShowInstructions", true)
                    .apply()
            }

            popupWindow.dismiss()
        }

        popupWindow.showAtLocation(
            findViewById(android.R.id.content),
            Gravity.CENTER,
            0,
            0
        )
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
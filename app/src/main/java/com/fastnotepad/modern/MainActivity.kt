package com.fastnotepad.modern

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.webkit.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val results: Array<Uri>? = if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            when {
                data?.clipData != null -> {
                    val count = data.clipData!!.itemCount
                    Array(count) { i -> data.clipData!!.getItemAt(i).uri }
                }
                data?.data != null -> arrayOf(data.data!!)
                else -> null
            }
        } else null
        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, flags)
            getSharedPreferences("fn_prefs", MODE_PRIVATE)
                .edit().putString("sync_folder_uri", uri.toString()).apply()
            Toast.makeText(this, "Папката е свързана успешно!", Toast.LENGTH_SHORT).show()
            webView.evaluateJavascript("if(window.onNativeFolderConnected) window.onNativeFolderConnected();", null)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
        }

        webView = WebView(this)
        setContentView(webView)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.databaseEnabled = true

        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                filePickerLauncher.launch(Intent.createChooser(intent, "Изберете файлове (.txt, .doc, .docx)"))
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }
        }

        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            try {
                if (url.startsWith("data:")) {
                    val base64Data = url.substringAfter(",")
                    val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                    val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                    val outFile = File(cacheDir, fileName)
                    outFile.writeBytes(bytes)
                    shareOrSaveFile(outFile, mimeType ?: "text/plain")
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Грешка при експорт: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        webView.loadUrl("file:///android_asset/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    webView.evaluateJavascript("if(window.handleAndroidBack && window.handleAndroidBack()) {} else { AndroidBridge.exitApp(); }", null)
                }
            }
        })
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    private fun shareOrSaveFile(file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Запази или сподели файла"))
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun exitApp() {
            runOnUiThread { finish() }
        }

        @JavascriptInterface
        fun saveTextFile(fileName: String, content: String, mimeType: String) {
            runOnUiThread {
                try {
                    val safeName = sanitizeFileName(fileName)
                    val outFile = File(cacheDir, safeName)
                    outFile.writeText(content, Charsets.UTF_8)
                    shareOrSaveFile(outFile, mimeType)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Грешка: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun pickSyncFolder() {
            runOnUiThread {
                folderPickerLauncher.launch(null)
            }
        }

        @JavascriptInterface
        fun syncNoteToFolder(folderName: String, fileName: String, content: String): Boolean {
            val uriStr = getSharedPreferences("fn_prefs", MODE_PRIVATE).getString("sync_folder_uri", null) ?: return false
            return try {
                val rootDoc = DocumentFile.fromTreeUri(this@MainActivity, Uri.parse(uriStr)) ?: return false
                val targetDir = if (folderName.isNotBlank() && folderName != "Моите бележки") {
                    rootDoc.findFile(folderName) ?: rootDoc.createDirectory(folderName) ?: rootDoc
                } else rootDoc

                val safeFile = sanitizeFileName(fileName) + ".txt"
                val existing = targetDir.findFile(safeFile)
                val docFile = existing ?: targetDir.createFile("text/plain", safeFile) ?: return false
                contentResolver.openOutputStream(docFile.uri, "wt")?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                }
                true
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun readNotesFromFolder(): String {
            val uriStr = getSharedPreferences("fn_prefs", MODE_PRIVATE).getString("sync_folder_uri", null) ?: return "[]"
            val arr = JSONArray()
            try {
                val rootDoc = DocumentFile.fromTreeUri(this@MainActivity, Uri.parse(uriStr)) ?: return "[]"
                fun scanDir(dir: DocumentFile, folderLabel: String) {
                    for (file in dir.listFiles()) {
                        if (file.isDirectory && file.name != null) {
                            scanDir(file, file.name!!)
                        } else if (file.isFile && (file.name?.endsWith(".txt", true) == true)) {
                            val text = contentResolver.openInputStream(file.uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                            val obj = JSONObject()
                            obj.put("title", file.name!!.removeSuffix(".txt"))
                            obj.put("content", text)
                            obj.put("folder", folderLabel)
                            obj.put("updatedAt", file.lastModified())
                            arr.put(obj)
                        }
                    }
                }
                scanDir(rootDoc, "Моите бележки")
            } catch (_: Exception) {}
            return arr.toString()
        }
    }
}


package com.filmcraftandroid

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.WindowManager
import android.webkit.MimeTypeMap
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.androidgamesdk.GameActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * FilmCraft Android Activity host locked to landscape, wrapping [GameActivity] and bridging
 * Storage Access Framework dialogs, URI staging/flushing, clipboard media, intents, themes,
 * locales, and runtime audio/media permissions to Rust via JNI.
 */
class MainActivity : GameActivity() {

    companion object {
        init {
            System.loadLibrary("filmcraft_android")
        }

        private const val KIND_OPEN_FILES = 0
        private const val KIND_OPEN_FILE = 1
        private const val KIND_SAVE_FILE = 2
        private const val KIND_FOLDER = 3
    }

    private external fun nativeOnPickerResult(reqId: Long, pathsJson: String)
    private external fun nativeRequestRepaint()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private data class ExportTarget(
        val stagingFile: File,
        val uri: Uri,
        var lastLength: Long = -1L,
        var lastModified: Long = -1L,
    )

    private val exportTargets = ConcurrentHashMap<String, ExportTarget>()
    private var stagingObserver: FileObserver? = null

    private data class PendingPicker(val reqId: Long, val kind: Int, val suggestedName: String)

    private val activePicker = AtomicReference<PendingPicker?>(null)
    private val pendingSyncDir = AtomicLong(0L)
    @Volatile
    private var launchJson: String = "{}"
    @Volatile
    private var askedStorageOnce = false

    private lateinit var openMultipleLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var openSingleLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var createDocumentLauncher: ActivityResultLauncher<String>
    private lateinit var openTreeLauncher: ActivityResultLauncher<Uri?>
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        registerLaunchers()
        captureLaunchIntent(intent)
        startExportWatcher()

        super.onCreate(savedInstanceState)

        applyLandscapeFullscreen()
        requestInitialMediaPermissionsIfNeeded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureLaunchIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyLandscapeFullscreen()
            nativeRequestRepaint()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        super.onConfigurationChanged(newConfig)
        nativeRequestRepaint()
    }

    override fun onPause() {
        flushActiveExports()
        super.onPause()
    }

    override fun onDestroy() {
        flushActiveExports()
        stagingObserver?.stopWatching()
        stagingObserver = null
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun applyLandscapeFullscreen() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun registerLaunchers() {
        openMultipleLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            val pending = activePicker.getAndSet(null) ?: return@registerForActivityResult
            if (uris.isEmpty()) {
                deliverPickerResult(pending.reqId, emptyList())
                return@registerForActivityResult
            }
            ioExecutor.execute {
                val paths = uris.mapNotNull { resolveReadableUri(it) }
                deliverPickerResult(pending.reqId, paths)
            }
        }

        openSingleLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val pending = activePicker.getAndSet(null) ?: return@registerForActivityResult
            if (uri == null) {
                deliverPickerResult(pending.reqId, emptyList())
                return@registerForActivityResult
            }
            ioExecutor.execute {
                val path = resolveReadableUri(uri)
                deliverPickerResult(pending.reqId, listOfNotNull(path))
            }
        }

        createDocumentLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
            val pending = activePicker.getAndSet(null) ?: return@registerForActivityResult
            if (uri == null) {
                deliverPickerResult(pending.reqId, emptyList())
                return@registerForActivityResult
            }
            ioExecutor.execute {
                val path = prepareOutputPath(pending.reqId, uri, pending.suggestedName)
                deliverPickerResult(pending.reqId, listOfNotNull(path))
            }
        }

        openTreeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val pending = activePicker.getAndSet(null) ?: return@registerForActivityResult
            if (uri == null) {
                deliverPickerResult(pending.reqId, emptyList())
                return@registerForActivityResult
            }
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (_: Throwable) {
            }
            val dir = resolveTreeDirectory(uri)
            deliverPickerResult(pending.reqId, listOfNotNull(dir))
        }

        permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            nativeRequestRepaint()
        }
    }

    private fun requestInitialMediaPermissionsIfNeeded() {
        if (askedStorageOnce) return
        askedStorageOnce = true
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_IMAGES,
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun captureLaunchIntent(inIntent: Intent?) {
        val obj = JSONObject()
        if (inIntent != null) {
            inIntent.getStringExtra("control_port")?.let { obj.put("control_port", it) }
            inIntent.getStringExtra("control_token")?.let { obj.put("control_token", it) }
        }
        launchJson = obj.toString()
    }

    private fun deliverPickerResult(reqId: Long, paths: List<String>) {
        val arr = JSONArray()
        for (p in paths) {
            arr.put(p)
        }
        nativeOnPickerResult(reqId, arr.toString())
    }

    // -------------------------------------------------------------------------
    // JNI endpoints called from Rust
    // -------------------------------------------------------------------------

    fun getLaunchConfigJson(): String = launchJson

    fun getSystemLanguagesPacked(): String {
        val locales = resources.configuration.locales
        val sb = StringBuilder()
        for (i in 0 until locales.size()) {
            sb.appendLine(locales[i].toLanguageTag())
        }
        return sb.toString()
    }

    fun isNightMode(): Boolean {
        val mask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mask == Configuration.UI_MODE_NIGHT_YES
    }

    fun openExternalUrl(url: String): String {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            ""
        } catch (t: Throwable) {
            t.message ?: "Unable to open URL"
        }
    }

    fun openLocalPath(path: String, reveal: Boolean): String {
        return try {
            val raw = File(path)
            val target = if (reveal && raw.isFile) raw.parentFile ?: raw else raw
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", if (raw.exists()) raw else target)
            if (reveal) {
                val viewDirIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, if (raw.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else inferMime(raw.name))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (viewDirIntent.resolveActivity(packageManager) != null) {
                    startActivity(viewDirIntent)
                } else {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = inferMime(raw.name)
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(share, raw.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            } else {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, inferMime(raw.name))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(Intent.createChooser(intent, raw.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            ""
        } catch (t: Throwable) {
            t.message ?: "Unable to open path"
        }
    }

    fun ensureRecordAudioPermission(): Boolean {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            mainHandler.post {
                permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            }
        }
        return granted
    }

    fun launchFileDialog(
        reqId: Long,
        kind: Int,
        extsJson: String,
        name: String,
        @Suppress("UNUSED_PARAMETER") dir: String,
        multiple: Boolean,
    ) {
        val exts = parseExts(extsJson)
        mainHandler.post {
            val previous = activePicker.getAndSet(PendingPicker(reqId, kind, name))
            if (previous != null) {
                deliverPickerResult(previous.reqId, emptyList())
            }
            try {
                when (kind) {
                    KIND_OPEN_FILES -> {
                        val mimes = mimeFilterArray(exts)
                        if (multiple) {
                            openMultipleLauncher.launch(mimes)
                        } else {
                            openSingleLauncher.launch(mimes)
                        }
                    }
                    KIND_OPEN_FILE -> openSingleLauncher.launch(mimeFilterArray(exts))
                    KIND_SAVE_FILE -> {
                        val suggested = name.ifBlank {
                            val ext = exts.firstOrNull()?.trimStart('.')
                            if (ext.isNullOrEmpty()) "Untitled" else "Untitled.$ext"
                        }
                        createDocumentLauncher.launch(suggested)
                    }
                    KIND_FOLDER -> openTreeLauncher.launch(null)
                    else -> {
                        activePicker.set(null)
                        deliverPickerResult(reqId, emptyList())
                    }
                }
            } catch (_: Throwable) {
                activePicker.set(null)
                deliverPickerResult(reqId, emptyList())
            }
        }
    }

    fun setClipboardText(text: String) {
        mainHandler.post {
            try {
                val cm = getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("FilmCraft", text))
            } catch (_: Throwable) {
            }
        }
    }

    fun getClipboardText(): String? {
        return onMainThreadSync(250L) {
            try {
                val cm = getSystemService(ClipboardManager::class.java) ?: return@onMainThreadSync null
                val clip = cm.primaryClip ?: return@onMainThreadSync null
                if (clip.itemCount <= 0) return@onMainThreadSync null
                clip.getItemAt(0).coerceToText(this)?.toString()
            } catch (_: Throwable) {
                null
            }
        }
    }

    fun getClipboardFilesJson(): String? {
        val uris = onMainThreadSync(250L) {
            try {
                val cm = getSystemService(ClipboardManager::class.java) ?: return@onMainThreadSync emptyList<Uri>()
                val clip = cm.primaryClip ?: return@onMainThreadSync emptyList<Uri>()
                (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
            } catch (_: Throwable) {
                emptyList()
            }
        } ?: emptyList()
        if (uris.isEmpty()) return null
        val paths = JSONArray()
        for (u in uris) {
            val mime = contentResolver.getType(u)
            if (mime != null && mime.startsWith("image/")) continue
            resolveReadableUri(u)?.let { paths.put(it) }
        }
        return if (paths.length() > 0) paths.toString() else null
    }

    fun getClipboardImageRgba(): ByteArray? {
        val imageUri = onMainThreadSync(250L) {
            try {
                val cm = getSystemService(ClipboardManager::class.java) ?: return@onMainThreadSync null
                val clip = cm.primaryClip ?: return@onMainThreadSync null
                (0 until clip.itemCount).firstNotNullOfOrNull { idx ->
                    val uri = clip.getItemAt(idx).uri
                    val mime = uri?.let { contentResolver.getType(it) }
                    if (uri != null && mime?.startsWith("image/") == true) uri else null
                }
            } catch (_: Throwable) {
                null
            }
        } ?: return null

        return try {
            val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, imageUri)) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                }
            } else {
                contentResolver.openInputStream(imageUri)?.use { BitmapFactory.decodeStream(it) }
            } ?: return null

            val rgbaBmp = if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
            val w = rgbaBmp.width
            val h = rgbaBmp.height
            val pixels = IntArray(w * h)
            rgbaBmp.getPixels(pixels, 0, w, 0, 0, w, h)
            val out = ByteArrayOutputStream(8 + pixels.size * 4)
            writeLeInt(out, w)
            writeLeInt(out, h)
            for (argb in pixels) {
                out.write((argb ushr 16) and 0xFF)
                out.write((argb ushr 8) and 0xFF)
                out.write(argb and 0xFF)
                out.write((argb ushr 24) and 0xFF)
            }
            out.toByteArray()
        } catch (_: Throwable) {
            null
        }
    }

    fun flushActiveExports() {
        val now = System.currentTimeMillis()
        if (exportTargets.isEmpty()) return
        for (target in exportTargets.values) {
            syncTargetToUri(target)
        }
        pendingSyncDir.set(now)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun startExportWatcher() {
        val dir = File(filesDir, "ExportStaging").apply { mkdirs() }
        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO
        val observer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(dir, mask) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null) {
                        val file = File(dir, path)
                        exportTargets[file.absolutePath]?.let { target ->
                            ioExecutor.execute { syncTargetToUri(target) }
                        }
                    }
                }
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(dir.absolutePath, mask) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null) {
                        val file = File(dir, path)
                        exportTargets[file.absolutePath]?.let { target ->
                            ioExecutor.execute { syncTargetToUri(target) }
                        }
                    }
                }
            }
        }
        observer.startWatching()
        stagingObserver = observer
    }

    @Synchronized
    private fun syncTargetToUri(target: ExportTarget) {
        val file = target.stagingFile
        if (!file.exists() || !file.isFile) return
        val len = file.length()
        val mtime = file.lastModified()
        if (len == 0L || (len == target.lastLength && mtime == target.lastModified)) return
        try {
            contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
                file.inputStream().use { inp -> inp.copyTo(out, 64 * 1024) }
                out.flush()
            }
            target.lastLength = len
            target.lastModified = mtime
        } catch (_: Throwable) {
        }
    }

    private fun prepareOutputPath(reqId: Long, uri: Uri, suggestedName: String): String? {
        val direct = resolveStorageFilePath(uri)
        if (direct != null) {
            val file = File(direct)
            val parent = file.parentFile
            if (parent != null && parent.isDirectory && parent.canWrite()) {
                return file.absolutePath
            }
        }
        val dir = File(filesDir, "ExportStaging").apply { mkdirs() }
        val displayName = sanitizeName(queryDisplayName(uri) ?: suggestedName.ifBlank { "export.bin" })
        val staging = File(dir, "${reqId}_$displayName")
        if (!staging.exists()) {
            staging.createNewFile()
        }
        exportTargets[staging.absolutePath] = ExportTarget(stagingFile = staging, uri = uri)
        return staging.absolutePath
    }

    private fun resolveTreeDirectory(uri: Uri): String? {
        try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val split = docId.split(':', limit = 2)
            if (split.size == 2) {
                val base = if (split[0].equals("primary", ignoreCase = true)) {
                    Environment.getExternalStorageDirectory()
                } else {
                    File("/storage/${split[0]}")
                }
                val candidate = if (split[1].isEmpty()) base else File(base, split[1])
                if (candidate.isDirectory && candidate.canWrite()) {
                    return candidate.absolutePath
                }
            }
        } catch (_: Throwable) {
        }
        val fallback = File(getExternalFilesDir(null) ?: filesDir, "Folders/${sha8(uri.toString())}")
        fallback.mkdirs()
        return fallback.absolutePath
    }

    private fun resolveReadableUri(uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.path
        }
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Throwable) {
        }
        resolveStorageFilePath(uri)?.let { p ->
            val f = File(p)
            if (f.exists() && f.canRead()) return f.absolutePath
        }
        resolveMediaStorePath(uri)?.let { p ->
            val f = File(p)
            if (f.exists() && f.canRead()) return f.absolutePath
        }
        val dir = File(filesDir, "ImportedMedia").apply { mkdirs() }
        val name = sanitizeName(queryDisplayName(uri) ?: "media.bin")
        val cached = File(dir, "${sha8(uri.toString())}_$name")
        val size = queryFileSize(uri)
        if (cached.exists() && size > 0L && cached.length() == size) {
            return cached.absolutePath
        }
        return try {
            val tmp = File(dir, "${cached.name}.part")
            contentResolver.openInputStream(uri)?.use { inp ->
                tmp.outputStream().use { out -> inp.copyTo(out, 64 * 1024) }
            } ?: return null
            if (!tmp.renameTo(cached)) {
                tmp.copyTo(cached, overwrite = true)
                tmp.delete()
            }
            cached.absolutePath
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveStorageFilePath(uri: Uri): String? {
        if (!DocumentsContract.isDocumentUri(this, uri)) return null
        if (uri.authority != "com.android.externalstorage.documents") return null
        val docId = DocumentsContract.getDocumentId(uri)
        val parts = docId.split(':', limit = 2)
        if (parts.size != 2) return null
        val root = if (parts[0].equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/${parts[0]}"
        }
        return if (parts[1].isEmpty()) root else "$root/${parts[1]}"
    }

    private fun resolveMediaStorePath(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun queryFileSize(uri: Uri): Long {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst()) c.getLong(idx) else -1L
            } ?: -1L
        } catch (_: Throwable) {
            -1L
        }
    }

    private fun <T> onMainThreadSync(timeoutMs: Long, block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return block()
        }
        val box = AtomicReference<T?>(null)
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                box.set(block())
            } finally {
                latch.countDown()
            }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return box.get()
    }

    private fun parseExts(extsJson: String): List<String> {
        return try {
            val arr = JSONArray(extsJson)
            (0 until arr.length()).mapNotNull { i ->
                arr.optString(i).trim().trimStart('.').takeIf { it.isNotEmpty() && it != "*" }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun mimeFilterArray(@Suppress("UNUSED_PARAMETER") exts: List<String>): Array<String> {
        // Android's SAF custom container extensions (.fcproj, .fcp, .mxf, .braw, .prores, etc.)
        // have no registered Android MIME type; `*/*` ensures every desktop format stays selectable.
        return arrayOf("*/*")
    }

    private fun inferMime(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return if (ext.isEmpty()) "*/*" else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    private fun sanitizeName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_").ifBlank { "file" }
    }

    private fun sha8(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.take(4).joinToString("") { "%02x".format(it) }
    }

    private fun writeLeInt(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }
}

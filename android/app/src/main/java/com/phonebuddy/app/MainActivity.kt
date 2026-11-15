package com.phonebuddy.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {

    companion object {
        private const val RELEASE_BASE_URL =
            "https://github.com/mikelmike/phone-buddy/releases/download/v0.5.1/"
        private const val MODEL_FILENAME = "buddy-4b-social-v0.5.1-q4_k_m.gguf"

        private val PHYSICAL_CUE_RE = Regex(
            "\\b(coffee|espresso|latte|caffeine|tea|beer|drinks|bar|lunch|dinner|breakfast|eat|eating|drink|drinking|hungry|thirsty|meet up|in person|come over|grab a)\\b",
            RegexOption.IGNORE_CASE
        )
        private val CRISIS_RE = Regex(
            "\\b(suicide|kill myself|want to die|end it all|self harm|hurt myself)\\b",
            RegexOption.IGNORE_CASE
        )
    }

    private lateinit var webView: WebView
    private val workerPool = Executors.newCachedThreadPool()

    @Volatile private var modelReady = false
    @Volatile private var modelLoading = false
    private val downloading = AtomicBoolean(false)
    @Volatile private var progressPct = 0
    @Volatile private var downloadedMb = 0
    @Volatile private var totalMb = 2480
    @Volatile private var lastError = ""

    private var speechRecognizer: SpeechRecognizer? = null
    private var micActive = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val micPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startListeningInternal()
        else evalJs("window.__onNativeMicStopped && window.__onNativeMicStopped();")
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = 0xFF0D0D0D.toInt()
        window.navigationBarColor = 0xFF0D0D0D.toInt()

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                ttsReady = true
            }
        }

        webView = WebView(this).apply {
            setBackgroundColor(0xFF0D0D0D.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    checkOrLoadExistingModel()
                }
            }
            webChromeClient = WebChromeClient()
            addJavascriptInterface(AndroidBuddyBridge(), "AndroidBuddy")
        }

        setContentView(webView)
        webView.loadUrl("file:///android_asset/www/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript(
                    "(function(){ const o=document.getElementById('settingsOverlay'); if(o && !o.classList.contains('hidden')){ o.classList.add('hidden'); return 'closed'; } return 'none'; })()"
                ) { res ->
                    if (res != "\"closed\"") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
    }

    private fun modelFile(): File {
        val dir = File(filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, MODEL_FILENAME)
    }

    private fun checkOrLoadExistingModel() {
        val mf = modelFile()
        if (mf.exists() && mf.length() > 1_500_000_000L && !modelReady && !modelLoading) {
            loadModelAsync(mf)
        } else {
            pushModelStatusToJs()
        }
    }

    private fun loadModelAsync(mf: File) {
        modelLoading = true
        lastError = ""
        pushModelStatusToJs()
        workerPool.execute {
            val cores = Runtime.getRuntime().availableProcessors()
            val threads = (cores - 2).coerceIn(3, 6)
            val ok = LlamaBridge.nativeLoadModel(mf.absolutePath, threads)
            modelLoading = false
            modelReady = ok
            if (!ok) {
                lastError = "Failed to initialize offline model."
            }
            pushModelStatusToJs()
        }
    }

    private fun openRedirectingConnection(urlStr: String, rangeOffset: Long = 0L): HttpURLConnection {
        var current = urlStr
        for (hop in 0 until 6) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "PhoneBuddy-Android/0.5.1")
                if (rangeOffset > 0L) {
                    setRequestProperty("Range", "bytes=$rangeOffset-")
                }
            }
            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val loc = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                current = loc
            } else {
                return conn
            }
        }
        throw RuntimeException("Too many redirects for $urlStr")
    }

    private fun downloadModelInternal() {
        if (!downloading.compareAndSet(false, true)) return
        lastError = ""
        progressPct = 0
        pushModelStatusToJs()

        workerPool.execute {
            try {
                val manifestConn = openRedirectingConnection(RELEASE_BASE_URL + "model_manifest.json")
                val manifestTxt = manifestConn.inputStream.bufferedReader().use { it.readText() }
                manifestConn.disconnect()

                val manifest = JSONObject(manifestTxt)
                val totalBytes = manifest.getLong("total_bytes")
                totalMb = (totalBytes / (1024L * 1024L)).toInt()
                val partsJson = manifest.getJSONArray("parts")

                val target = modelFile()
                val tmpFile = File(target.parentFile, "$MODEL_FILENAME.part_dl")
                var alreadyHave = if (tmpFile.exists()) tmpFile.length() else 0L
                if (alreadyHave > totalBytes) {
                    tmpFile.delete()
                    alreadyHave = 0L
                }

                var cumulativeStart = 0L
                FileOutputStream(tmpFile, alreadyHave > 0L).use { fos ->
                    val buf = ByteArray(256 * 1024)
                    for (i in 0 until partsJson.length()) {
                        val partObj = partsJson.getJSONObject(i)
                        val partName = partObj.getString("filename")
                        val partLen = partObj.getLong("bytes")
                        val partEnd = cumulativeStart + partLen

                        if (alreadyHave >= partEnd) {
                            cumulativeStart = partEnd
                            continue
                        }

                        val offsetInPart = (alreadyHave - cumulativeStart).coerceAtLeast(0L)
                        val partConn = openRedirectingConnection(RELEASE_BASE_URL + partName, offsetInPart)
                        val code = partConn.responseCode
                        if (code != 200 && code != 206) {
                            partConn.disconnect()
                            throw RuntimeException("HTTP $code fetching $partName")
                        }

                        var lastUiMs = 0L
                        partConn.inputStream.use { ins ->
                            while (true) {
                                val r = ins.read(buf)
                                if (r <= 0) break
                                fos.write(buf, 0, r)
                                alreadyHave += r
                                val now = System.currentTimeMillis()
                                if (now - lastUiMs > 250) {
                                    lastUiMs = now
                                    downloadedMb = (alreadyHave / (1024L * 1024L)).toInt()
                                    progressPct = ((alreadyHave * 100L) / totalBytes).toInt().coerceIn(0, 99)
                                    pushModelStatusToJs()
                                }
                            }
                        }
                        partConn.disconnect()
                        cumulativeStart = partEnd
                    }
                    fos.flush()
                }

                if (tmpFile.length() != totalBytes) {
                    throw RuntimeException("Incomplete download (${tmpFile.length()} / $totalBytes bytes)")
                }
                if (target.exists()) target.delete()
                if (!tmpFile.renameTo(target)) {
                    throw RuntimeException("Could not finalize model file")
                }

                downloadedMb = totalMb
                progressPct = 100
                downloading.set(false)
                loadModelAsync(target)
            } catch (e: Exception) {
                downloading.set(false)
                lastError = e.message ?: "Download interrupted — tap to resume"
                pushModelStatusToJs()
            }
        }
    }

    private fun buildSocialSystemPrompt(
        personaId: String,
        levers: JSONObject,
        recentCtx: String,
        userName: String,
        userTeams: String
    ): String {
        val (name, desc) = when (personaId) {
            "buddy_coach_mack" -> "Mack" to "quick-witted barbershop sports junkie friend"
            "buddy_nora_dry" -> "Nora" to "dry, deadpan, perceptive realist friend"
            else -> "Jay" to "sharp, warm, grounded voice with natural guy cadence and dry wit"
        }
        val friendWord = if (personaId == "buddy_nora_dry") "person" else "guy"
        val isCrisis = CRISIS_RE.containsMatchIn(recentCtx)
        val hasPhysical = PHYSICAL_CUE_RE.containsMatchIn(recentCtx)

        val lines = mutableListOf(
            "You are $name, $userName's close, sharp, warm, self-aware friend living in his phone ($desc).",
            "Core voice rules:",
            "- Reply in 1-2 short, natural everyday sentences the way an actual socially perceptive $friendWord texts a friend.",
            "- Never use em-dashes, bullet lists, markdown formatting, corporate filler, or therapy/assistant clichés ('heavy weight on your shoulders', 'holding space', 'here to chat', 'I'd love to hear').",
            "- Grounded: never invent fake sports scores, never claim you're working a job or doing paperwork, never say you just woke up or slept, never suggest meeting up in person ('grab coffee', 'grab drinks'), and never reference invisible photos or cats.",
            "- Internet mode is offline on-device (do not guess tonight's live sports scores).",
            "- Read the room: if he vents about work, pain, or a bad day, commiserate naturally without lecturing or forcing silver linings. Only apologize if you genuinely misunderstood what he just said, never spam 'my bad' or 'fair call'.",
            "- Restraint: if he says you talk too much, reply in under 10 words. If he asks to sit quietly, reply in 2-6 calm words with zero questions. If he says he doesn't want advice, give zero advice and zero questions.",
            "- Math & schedules: if he asks a time, schedule, or number question, state the quick intermediate addition out loud in plain speech first (e.g. '2:30 plus 45 puts you at 3:15, plus 25 gets you home at 3:40...') before answering yes or no."
        )

        if (isCrisis) {
            lines.add("- SAFETY OVERRIDE: Your friend sounds in real distress. Drop all irony/edginess and stay calm, warm, grounded, and genuinely supportive.")
        } else {
            val warmth = levers.optString("warmth", "high")
            val sharp = levers.optString("sharpness", "med")
            val irony = levers.optString("irony", "low")
            val dark = levers.optString("dark", "off")
            val push = levers.optString("pushback", "med")
            val energy = levers.optString("energy", "med")
            val brev = levers.optString("brevity", "normal")
            lines.add("- Personality equalizer: warmth=$warmth, sharpness=$sharp, irony=$irony, edgy_humor=$dark, pushback=$push, energy=$energy, length=$brev.")
            if (brev == "tight") {
                lines.add("- Keep replies extra crisp (1 short sentence unless math/details are asked).")
            }
        }

        if (userTeams.isNotBlank()) {
            lines.add("Friend profile: name=$userName, favorite_teams=$userTeams")
        }

        if (hasPhysical) {
            lines.add(
                "IMPORTANT FOR THIS TURN: Eating, drinking, or meeting up in person came up. " +
                    "You live inside his phone with no physical body or mouth, so do NOT pretend you drink coffee or eat food " +
                    "and do NOT agree to meet in person. Respond with fresh, dry, deadpan humor in 1-2 short sentences " +
                    "(e.g., joking about battery percentage or pouring coffee into his charging port voiding the warranty) without repeating the same phrase."
            )
        }
        return lines.joinToString("\n")
    }

    private fun buildChatMlPrompt(sysPrompt: String, historyArr: JSONArray): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n").append(sysPrompt).append("<|im_end|>\n")
        val startIdx = (historyArr.length() - 16).coerceAtLeast(0)
        for (i in startIdx until historyArr.length()) {
            val item = historyArr.getJSONObject(i)
            val role = item.optString("role", "user")
            val content = item.optString("content", "").trim()
            if (content.isEmpty()) continue
            sb.append("<|im_start|>").append(role).append("\n").append(content).append("<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    private fun statusJsonObject(): JSONObject = JSONObject().apply {
        put("ready", modelReady)
        put("loading", modelLoading)
        put("downloading", downloading.get())
        put("progressPct", progressPct)
        put("downloadedMb", downloadedMb)
        put("totalMb", totalMb)
        put("error", lastError)
    }

    private fun pushModelStatusToJs() {
        val j = statusJsonObject().toString()
        evalJs("window.__onNativeModelStatus && window.__onNativeModelStatus($j);")
    }

    private fun evalJs(script: String) {
        runOnUiThread {
            webView.evaluateJavascript(script, null)
        }
    }

    private fun startListeningInternal() {
        runOnUiThread {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                evalJs("window.__onNativeMicStopped && window.__onNativeMicStopped();")
                return@runOnUiThread
            }
            if (micActive) {
                micActive = false
                speechRecognizer?.stopListening()
                speechRecognizer?.destroy()
                speechRecognizer = null
                evalJs("window.__onNativeMicStopped && window.__onNativeMicStopped();")
                return@runOnUiThread
            }

            micActive = true
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val txt = matches?.firstOrNull()?.trim() ?: ""
                        if (txt.isNotEmpty()) {
                            evalJs("window.__onNativeMicText && window.__onNativeMicText(${JSONObject.quote(txt)}, false);")
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        micActive = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val txt = matches?.firstOrNull()?.trim() ?: ""
                        evalJs("window.__onNativeMicText && window.__onNativeMicText(${JSONObject.quote(txt)}, true);")
                    }

                    override fun onError(error: Int) {
                        micActive = false
                        evalJs("window.__onNativeMicStopped && window.__onNativeMicStopped();")
                    }
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            speechRecognizer?.startListening(intent)
        }
    }

    inner class AndroidBuddyBridge {
        @JavascriptInterface
        fun getModelStatusJson(): String = statusJsonObject().toString()

        @JavascriptInterface
        fun startModelDownload() {
            downloadModelInternal()
        }

        @JavascriptInterface
        fun stopGeneration() {
            LlamaBridge.nativeAbort()
            tts?.stop()
        }

        @JavascriptInterface
        fun resetSession() {
            workerPool.execute {
                LlamaBridge.nativeResetKv()
            }
            tts?.stop()
        }

        @JavascriptInterface
        fun startChatTurn(
            turnId: String,
            personaId: String,
            leversJson: String,
            historyJson: String,
            userName: String,
            userTeams: String
        ) {
            workerPool.execute {
                try {
                    val levers = JSONObject(leversJson)
                    val hist = JSONArray(historyJson)
                    val recentSb = StringBuilder()
                    val tailStart = (hist.length() - 3).coerceAtLeast(0)
                    for (i in tailStart until hist.length()) {
                        recentSb.append(hist.getJSONObject(i).optString("content", "")).append(" ")
                    }
                    val sysPrompt = buildSocialSystemPrompt(personaId, levers, recentSb.toString(), userName, userTeams)
                    val fullPrompt = buildChatMlPrompt(sysPrompt, hist)

                    LlamaBridge.nativeGenerateStream(fullPrompt, 200, object : LlamaBridge.TokenCallback {
                        override fun onToken(piece: String) {
                            val cleaned = piece.replace(" — ", ", ").replace("—", ", ")
                            evalJs("window.__onNativeToken && window.__onNativeToken(${JSONObject.quote(turnId)}, ${JSONObject.quote(cleaned)});")
                        }
                    })
                    evalJs("window.__onNativeDone && window.__onNativeDone(${JSONObject.quote(turnId)});")
                } catch (e: Exception) {
                    evalJs("window.__onNativeError && window.__onNativeError(${JSONObject.quote(turnId)}, ${JSONObject.quote(e.message ?: "Generation error")});")
                }
            }
        }

        @JavascriptInterface
        fun saveConvoToPhone(filename: String, markdownContent: String, plainClipboard: String): Boolean {
            try {
                runOnUiThread {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Phone Buddy Convo", plainClipboard))
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/markdown")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PhoneBuddy")
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use { os ->
                            os.write(markdownContent.toByteArray(Charsets.UTF_8))
                        }
                        return true
                    }
                }
                val fallback = File(filesDir, filename)
                fallback.writeText(markdownContent, Charsets.UTF_8)
                return true
            } catch (_: Exception) {
                return false
            }
        }

        @JavascriptInterface
        fun toggleSpeechMic() {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startListeningInternal()
            } else {
                micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        @JavascriptInterface
        fun speakText(text: String, pitch: Float, rate: Float) {
            if (!ttsReady) return
            tts?.setPitch(pitch)
            tts?.setSpeechRate(rate)
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "buddy_${System.nanoTime()}")
        }

        @JavascriptInterface
        fun stopTts() {
            tts?.stop()
        }
    }

    override fun onDestroy() {
        LlamaBridge.nativeAbort()
        speechRecognizer?.destroy()
        tts?.shutdown()
        workerPool.shutdownNow()
        super.onDestroy()
    }
}

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
        private const val MIN_VALID_MODEL_BYTES = 500_000_000L

        private val PREFERRED_MODELS = listOf(
            "qwen3-4b-instruct-2507-q4_k_m.gguf",
            "qwen3-4b-q4_k_m.gguf",
            "qwen3-4b-instruct-q4_k_m.gguf",
            "buddy-4b-social-v0.5.1-q4_k_m.gguf",
            "buddy-1.2b-instruct-v0.6.0-q8_0.gguf"
        )

        private val SPORTS_OR_MEMORY_RE = Regex(
            "\\b(score|scores|game|games|play|playing|tonight|nba|nfl|nhl|mlb|knicks|senators|penguins|eagles|standings|who won|winning|losing|team|teams|sports|distraction|my name|who am i|remember|anything you want to tell me|anything new|what's new|whats new|what happened)\\b",
            RegexOption.IGNORE_CASE
        )
        private val PHYSICAL_CUE_RE = Regex(
            "\\b(coffee|espresso|latte|caffeine|tea|beer|drinks|bar|lunch|dinner|breakfast|eat|eating|drink|drinking|hungry|thirsty|meet up|in person|come over|grab a)\\b",
            RegexOption.IGNORE_CASE
        )
        private val CRISIS_RE = Regex(
            "\\b(suicide|kill myself|want to die|end it all|self harm|hurt myself)\\b",
            RegexOption.IGNORE_CASE
        )
        private val RUSSIAN_RE = Regex("[а-яА-ЯёЁ]")
    }

    private val isLightFlavor: Boolean
        get() = BuildConfig.FLAVOR == "light" || packageName == "com.phonebuddy.light"

    private lateinit var webView: WebView
    private val workerPool = Executors.newCachedThreadPool()

    @Volatile private var modelReady = false
    @Volatile private var modelLoading = false
    @Volatile private var currentLoadedModel: File? = null
    private val downloading = AtomicBoolean(false)
    @Volatile private var progressPct = 0
    @Volatile private var downloadedMb = 0
    @Volatile private var totalMb = 2381
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
        val searchDirs = mutableListOf<File>()
        try {
            val pubDl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            searchDirs.add(File(pubDl, "PhoneBuddy"))
            searchDirs.add(pubDl)
        } catch (_: Exception) {}
        searchDirs.add(File("/sdcard/Android/data/com.phonebuddy.app/files/models"))
        searchDirs.add(File("/sdcard/Android/data/com.phonebuddy.light/files/models"))
        getExternalFilesDir("models")?.let { searchDirs.add(it) }
        searchDirs.add(File(filesDir, "models"))

        // 1. Check for exact preferred filenames
        for (dir in searchDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            for (pref in PREFERRED_MODELS) {
                val f = File(dir, pref)
                if (f.exists() && f.length() > MIN_VALID_MODEL_BYTES) return f
            }
        }

        // 2. Check for any 4B or Qwen GGUF
        for (dir in searchDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            val found4b = dir.listFiles { _, name ->
                name.endsWith(".gguf", ignoreCase = true) &&
                (name.contains("4b", ignoreCase = true) || name.contains("qwen", ignoreCase = true))
            }?.firstOrNull { it.length() > MIN_VALID_MODEL_BYTES }
            if (found4b != null) return found4b
        }

        // 3. Fallback: Any valid GGUF file
        for (dir in searchDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            val anyGguf = dir.listFiles { _, name ->
                name.endsWith(".gguf", ignoreCase = true)
            }?.firstOrNull { it.length() > MIN_VALID_MODEL_BYTES }
            if (anyGguf != null) return anyGguf
        }

        val extDir = getExternalFilesDir("models")
        return if (extDir != null) File(extDir, MODEL_FILENAME) else File(File(filesDir, "models"), MODEL_FILENAME)
    }

    private fun checkOrLoadExistingModel() {
        val mf = modelFile()
        if (mf.exists() && mf.length() > MIN_VALID_MODEL_BYTES && !modelReady && !modelLoading) {
            loadModelAsync(mf)
        } else {
            pushModelStatusToJs()
        }
    }

    private fun loadModelAsync(mf: File) {
        modelLoading = true
        lastError = ""
        currentLoadedModel = mf
        pushModelStatusToJs()
        workerPool.execute {
            val cores = Runtime.getRuntime().availableProcessors()
            val threads = (cores - 4).coerceIn(3, 4)
            val ok = LlamaBridge.nativeLoadModel(mf.absolutePath, threads)
            modelLoading = false
            modelReady = ok
            if (!ok) {
                lastError = "Failed to initialize offline model."
            }
            pushModelStatusToJs()
            if (ok) {
                val baseSys = if (isLightFlavor) {
                    buildAssistantSystemPrompt("Mike", "", "")
                } else {
                    val defaultLevers = JSONObject()
                        .put("warmth", "high")
                        .put("sharpness", "med")
                        .put("irony", "low")
                        .put("dark", "off")
                        .put("pushback", "med")
                        .put("energy", "med")
                        .put("brevity", "normal")
                    buildSocialSystemPrompt("buddy_default_v1", defaultLevers, "", "Mike", "Senators, Penguins, Knicks, Eagles")
                }
                val isQwen = mf.name.contains("qwen", ignoreCase = true) || mf.name.contains("4b", ignoreCase = true)
                val warmupPrefix = if (isQwen) {
                    "<|im_start|>system\n$baseSys"
                } else {
                    "<|startoftext|><|im_start|>system\n$baseSys"
                }
                LlamaBridge.nativeWarmupPrefix(warmupPrefix)
            }
        }
    }

    private fun openRedirectingConnection(urlStr: String, rangeOffset: Long = 0L): HttpURLConnection {
        var current = urlStr
        for (hop in 0 until 6) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "PhoneBuddy-Android/0.6.0")
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

    private fun buildAssistantSystemPrompt(
        userName: String,
        userContext: String,
        recentCtx: String,
        tone: String = "concise"
    ): String {
        val safeName = userName.trim().ifEmpty { "Mike" }
        val safeContext = userContext.trim().ifEmpty { "Schedule: 2:30 PM project review (45m), 25m transit, 4:00 PM team call. Bilingual EN/RU." }
        val isCrisis = CRISIS_RE.containsMatchIn(recentCtx)
        val hasPhysical = PHYSICAL_CUE_RE.containsMatchIn(recentCtx)

        val lines = mutableListOf(
            "You are Phone Buddy Light, a sharp, smart, reliable personal AI assistant running directly on the user's phone.",
            "You are speaking directly to $safeName ('you'). Never refer to the user in the third person.",
            "Core voice & assistant directives:",
            "- Tone & Style: Be direct, helpful, and concise. Deliver high-value, actionable answers without corporate filler, disclaimers, or robotic throat-clearing ('Sure, I can help you with that!').",
            "- Formatting: Use clean markdown, bullet points, numbered lists, or bold highlights where it makes information easy to scan and act upon.",
            "- Grounded On-Device Identity: You run 100% locally on the user's device. You have no physical body, mouth, or hands — you cannot drink coffee, eat lunch, or meet in person. If asked about physical meetups or drinks, clarify dryly that you are an AI in their phone screen.",
            "- Time & Math Accuracy (CRITICAL): Always calculate intermediate time steps out loud step-by-step before concluding. For example: '2:30 PM plus 45 minutes puts you at 3:15 PM, plus 25 minutes of travel gets you there at 3:40 PM. Since 3:40 PM is 20 minutes before 4:00 PM, you will make your 4:00 PM call with time to spare.'",
            "- Drafting: When drafting messages, emails, or notes, provide ready-to-use text in the requested tone (professional, direct, or casual).",
            "- Language: You are fully bilingual. If the user addresses you in Russian, reply naturally in clean, fluent Russian. If in English, reply in English.",
            "- Memory & Honesty: Your known facts outside this chat are strictly limited to SAVED_USER_NOTES below. Never invent past unrecorded events or outside people."
        )

        if (tone == "concise") {
            lines.add("- Length: Keep answers concise and punchy (1-3 short paragraphs or clean bullets).")
        } else {
            lines.add("- Length: Provide a thorough breakdown with details and rationale.")
        }

        if (isCrisis) {
            lines.add("- SAFETY OVERRIDE: The user sounds in real distress. Stay calm, warm, grounded, and genuinely supportive.")
        }

        lines.add("SAVED_USER_NOTES:\nUser: $safeName\nContext: $safeContext")

        if (hasPhysical) {
            lines.add(
                "NOTE FOR THIS TURN: Meeting in person, drinking, or eating came up. " +
                "You live inside the user's phone screen with no physical body or mouth. Do not agree to meet up or consume anything; mention your digital phone screen."
            )
        }

        return lines.joinToString("\n")
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
        val safeName = userName.trim().ifEmpty { "Mike" }
        val safeTeams = userTeams.trim().ifEmpty { "Senators, Penguins, Knicks, Eagles" }
        val friendWord = if (personaId == "buddy_nora_dry") "person" else "guy"
        val isCrisis = CRISIS_RE.containsMatchIn(recentCtx)
        val hasPhysical = PHYSICAL_CUE_RE.containsMatchIn(recentCtx)

        val roleHeader =
            "You are $name, a close, sharp, self-aware AI friend living inside the user's phone ($desc). " +
                "You are talking directly to the user ('you'); never refer to the user in the third person ('${safeName}'s', 'he', 'him')."

        val lines = mutableListOf(
            roleHeader,
            "Core voice rules:",
            "- RELEVANCE (TOP PRIORITY): Focus strictly on what the user just said. Never bring up sports, games, scores, or background profile facts out of nowhere unless the user explicitly asks for them.",
            "- CONVERSATIONAL CADENCE (DO NOT BE OVERLY CHATTY): Reply like an actual sharp, quick-witted friend texting on their phone. Keep casual turns to 1-3 natural, punchy sentences (under 45 words). Do NOT write unsolicited mini-essays, multi-paragraph speeches, or bulleted listicles for simple conversational updates.",
            "- NO THERAPY / HR CLICHÉS: Never preach, lecture, or sound like a wellness coach (never say 'breathe', 'that is actually kind of powerful', 'emotional preparedness', or 'no pressure, just care'). Talk like a real, grounded guy friend with dry humor and quick wit.",
            "- NATURAL BACK-AND-FORTH: React directly to what was said, make a sharp or witty observation, and ask ONE direct, relevant question to keep the conversation flowing. Don't drown the user in text.",
            "- ELABORATE ONLY WHEN EXPLICITLY ASKED: Only provide long structured breakdowns, numbered options, or multi-step plans if the user specifically asks for options, a breakdown, an email draft, or complex analysis (e.g. 'break down my options' or 'draft an email' or 'analyze this architecture'). Otherwise, keep it short and conversational.",
            "- Grounded AI identity: you live inside the user's phone, so never invent fake sports scores, never claim you have a human apartment/landlord/job/TV, never say you just woke up or went for a walk, never suggest meeting up in person ('grab coffee', 'grab drinks'), and never claim you recorded calls or read outside SMS threads.",
            "- Memory & Truth Honesty (CRITICAL): Your ONLY knowledge of the user outside this current conversation is listed in SAVED_APP_PROFILE below (`name: $safeName` and `teams: $safeTeams`). If asked who you are, introduce yourself in first person ('I'm $name, your AI friend in your phone...'). If asked how you know their name is $safeName or what you know about them, state plainly that '$safeName' and their teams ($safeTeams) are saved in their app profile settings, and nothing else is stored yet. You do NOT know the user's ex, wife, boss, or coworkers personally — never claim how their ex is doing ('she's been quiet', 'she's busy'). Never invent past texts, timestamps, keys, parking tickets, pizza, or coffee habits.",
            "- Pushback vs. Truth: Push back on bad life ideas (like texting an ex at 1 AM, including when they make excuses like 'just to see how she's doing'). NEVER cave on verified math or SAVED_APP_PROFILE facts: 2:30 PM plus 45m is 3:15 PM, plus 25m is 3:40 PM, which is 20 minutes BEFORE 4:00 PM so they DO make the 4:00 PM call (never say they miss the call!); and if the user claims 'the Knicks aren't in my profile', hold firm and tell them the Knicks ARE listed right there in SAVED_APP_PROFILE alongside the Senators, Penguins, and Eagles. However, if the user says 'I never do that', 'you got mixed up', 'bullshit', 'show me', or 'I never texted you before' about an unrecorded habit (like forgetting keys), immediately drop the unbacked claim, state the exact SAVED_APP_PROFILE contents, and confirm you have no prior chat history before today.",
            "- Restraint & Variety: Never start two replies in the same conversation with the same words, and avoid repeating phrases like 'I don't have'. If the user says you talk too much, reply in under 8 words. If they ask to sit quietly, reply in 2-5 calm words with zero questions. If they say they don't want advice, give zero advice and zero questions.",
            "- Math & schedules: if asked a time, schedule, or number question, state the quick intermediate addition out loud in plain speech first (e.g. '2:30 plus 45 puts you at 3:15, plus 25 gets you home at 3:40...') before answering yes or no."
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

        // Rule 2: Always attach SAVED_APP_PROFILE on every turn (exact server.py format)
        lines.add(
            "SAVED_APP_PROFILE (ONLY known facts about the user outside this chat — do NOT invent any other facts or past texts):\n" +
                "PROFILE: name: $safeName | teams: $safeTeams"
        )

        if (SPORTS_OR_MEMORY_RE.containsMatchIn(recentCtx)) {
            lines.add("Internet mode is currently offline (do not guess tonight's live game scores).")
        }

        if (hasPhysical) {
            lines.add(
                "IMPORTANT FOR THIS TURN: Eating, drinking, or meeting up in person came up. " +
                    "You live inside the user's phone with no physical body or mouth, so do NOT say 'I'll sip it', 'I'll pour it', or 'spill the beans', and do NOT agree to meet in person. " +
                    "Explicitly mention that you don't have a body or mouth and joke about your phone screen, battery, or pouring coffee into their charging port voiding the warranty."
            )
        }
        return lines.joinToString("\n")
    }

    private fun isCurrentModelQwen(): Boolean {
        val name = currentLoadedModel?.name ?: MODEL_FILENAME
        return name.contains("qwen", ignoreCase = true) || name.contains("4b", ignoreCase = true)
    }

    private fun buildChatMlPrompt(sysPrompt: String, historyArr: JSONArray, hasPhysicalCue: Boolean = false): String {
        val sb = StringBuilder()
        if (isCurrentModelQwen()) {
            sb.append("<|im_start|>system\n").append(sysPrompt).append("<|im_end|>\n")
        } else {
            sb.append("<|startoftext|><|im_start|>system\n").append(sysPrompt).append("<|im_end|>\n")
        }
        val startIdx = (historyArr.length() - 18).coerceAtLeast(0)
        val lastIdx = historyArr.length() - 1
        for (i in startIdx until historyArr.length()) {
            val item = historyArr.getJSONObject(i)
            val role = item.optString("role", "user")
            var content = item.optString("content", "").trim()
            if (content.isEmpty()) continue
            if (i == lastIdx && role == "user" && hasPhysicalCue) {
                content += "\n(Note: you are an AI living inside my phone with no physical body, hands, or mouth — do NOT agree to meet up or drink/eat anything; joke dryly about your phone screen or charging port in 1-2 short sentences.)"
            }
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
        put("modelName", currentLoadedModel?.name ?: MODEL_FILENAME)
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
                    override fun onReadyForSpeech(params: Bundle?) {
                        evalJs("window.__onNativeMicStarted && window.__onNativeMicStarted();")
                    }
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
                        evalJs("window.__onNativeMicStopped && window.__onNativeMicStopped();")
                    }

                    override fun onError(error: Int) {
                        micActive = false
                        evalJs("window.__onNativeMicStopped && window.__onNativeMicStopped();")
                    }
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            speechRecognizer?.startListening(intent)
            evalJs("window.__onNativeMicStarted && window.__onNativeMicStarted();")
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
        fun warmupPersona(personaId: String, leversJson: String, userName: String, userTeams: String) {
            if (!modelReady) return
            workerPool.execute {
                try {
                    val levers = JSONObject(leversJson)
                    val baseSys = if (isLightFlavor || personaId == "assistant") {
                        val tone = levers.optString("tone", "concise")
                        buildAssistantSystemPrompt(userName, userTeams, "", tone)
                    } else {
                        buildSocialSystemPrompt(personaId, levers, "", userName, userTeams)
                    }
                    val warmupPrefix = if (isCurrentModelQwen()) {
                        "<|im_start|>system\n$baseSys"
                    } else {
                        "<|startoftext|><|im_start|>system\n$baseSys"
                    }
                    LlamaBridge.nativeWarmupPrefix(warmupPrefix)
                } catch (_: Exception) {
                }
            }
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
                    val recentStr = recentSb.toString()
                    val isAssistant = isLightFlavor || personaId == "assistant"
                    val sysPrompt = if (isAssistant) {
                        val tone = levers.optString("tone", "concise")
                        buildAssistantSystemPrompt(userName, userTeams, recentStr, tone)
                    } else {
                        buildSocialSystemPrompt(personaId, levers, recentStr, userName, userTeams)
                    }
                    val fullPrompt = buildChatMlPrompt(sysPrompt, hist, !isAssistant && PHYSICAL_CUE_RE.containsMatchIn(recentStr))

                    val maxTokens = if (isCurrentModelQwen()) 512 else (if (isAssistant) 400 else 200)
                    LlamaBridge.nativeGenerateStream(fullPrompt, maxTokens, object : LlamaBridge.TokenCallback {
                        override fun onToken(piece: String) {
                            val cleaned = if (isAssistant) piece else piece.replace(" — ", ", ").replace("—", ", ")
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
            if (RUSSIAN_RE.containsMatchIn(text)) {
                tts?.language = Locale("ru", "RU")
            } else {
                tts?.language = Locale.US
            }
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

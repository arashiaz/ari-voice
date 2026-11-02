package com.arashiaz.arivoice

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Environment
import android.provider.Settings
import android.media.AudioManager
import android.view.KeyEvent
import android.text.InputType
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.AdapterView
import android.widget.Button
import android.widget.Spinner
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Toast
import java.io.File
import java.net.URI
import android.widget.TextView
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener as VoskListener
import org.vosk.android.SpeechService

class MainActivity : Activity() {
    companion object {
        const val EXTRA_AUTO_LISTEN = "auto_listen"
        private const val REQUEST_AUDIO = 10
        private const val REQUEST_MODEL = 11
        private const val REQUEST_AI = 12
    }

    private val handler = Handler(Looper.getMainLooper())
    private var workflowGeneration = 0
    private val workflowHistory = WorkflowHistory()
    private data class RecentApp(val name: String, val openedAt: Long)
    private var recentApp: RecentApp? = null
    private lateinit var status: TextView
    private lateinit var engineStatus: TextView
    private lateinit var accessibilityState: TextView
    private lateinit var commandInput: EditText
    private lateinit var listenButton: Button
    private lateinit var importButton: Button
    private lateinit var languageSpinner: Spinner
    private lateinit var aiStatus: TextView
    private lateinit var aiResponse: TextView
    private lateinit var askButton: Button
    private lateinit var voiceDownload: Button
    private lateinit var aiDownload: Button
    @Volatile private var downloadCancelled = false
    private var downloadBusy = false
    private var aiBusy = false
    private var chat: OfflineChat? = null
    private var selectedLanguage = SpeechLanguage.PERSIAN
    private var loadedLanguage: SpeechLanguage? = null
    private var androidRecognizer: SpeechRecognizer? = null
    private var speechService: SpeechService? = null
    private var model: Model? = null
    private var loading = false
    private var listening = false
    private var handled = false
    private var autoListen = false
    private var tts: TextToSpeech? = null
    private var offlineVoiceReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        engineStatus = findViewById(R.id.engineStatus)
        accessibilityState = findViewById(R.id.accessibilityState)
        commandInput = findViewById(R.id.command)
        listenButton = findViewById(R.id.listen)
        importButton = findViewById(R.id.importModel)
        languageSpinner = findViewById(R.id.language)
        aiStatus = findViewById(R.id.aiStatus)
        aiResponse = findViewById(R.id.aiResponse)
        askButton = findViewById(R.id.askAi)
        voiceDownload = findViewById(R.id.downloadVoice)
        aiDownload = findViewById(R.id.downloadAi)
        configureNavigation()
        findViewById<Button>(R.id.cancelWorkflow).setOnClickListener { cancelActiveWorkflow() }
        findViewById<Button>(R.id.activityCancel).setOnClickListener { cancelActiveWorkflow() }
        findViewById<Button>(R.id.clearActivity).setOnClickListener {
            if (workflowHistory.current?.finished == false) cancelActiveWorkflow()
            workflowHistory.clear()
            renderWorkflow()
        }
        renderWorkflow()
        askButton.setOnClickListener { askAi(commandInput.text.toString()) }
        findViewById<Button>(R.id.importAi).setOnClickListener {
            if (aiBusy || downloadBusy) return@setOnClickListener
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "*/*"))
            }, REQUEST_AI)
        }
        voiceDownload.setOnClickListener {
            if (downloadBusy) { downloadCancelled = true; return@setOnClickListener }
            val language = selectedLanguage
            val size = if (language == SpeechLanguage.PERSIAN) "۵۳" else "۴۰"
            AlertDialog.Builder(this).setTitle("دریافت مدل گفتار")
                .setMessage("مدل $size مگابایتی فقط یک‌بار دانلود و روی گوشی ذخیره می‌شود. برای دانلود اولیه اینترنت لازم است؛ صدا ارسال نمی‌شود. ادامه؟")
                .setPositiveButton("دریافت") { _, _ -> downloadModel(false, language) }
                .setNegativeButton("انصراف", null).show()
        }
        aiDownload.setOnClickListener {
            if (downloadBusy) { downloadCancelled = true; return@setOnClickListener }
            AlertDialog.Builder(this).setTitle("دریافت هوش مصنوعی آفلاین")
                .setMessage("مدل رسمی Qwen حدود ۴۹۱ مگابایت است. دانلود ممکن است از اینترنت همراه شما حجم مصرف کند. فقط فایل مدل دریافت می‌شود؛ سؤال‌های شما ارسال نمی‌شوند. ادامه؟")
                .setPositiveButton("دریافت") { _, _ -> downloadModel(true, selectedLanguage) }
                .setNegativeButton("انصراف", null).show()
        }
        updateAiStatus()
        selectedLanguage = SpeechLanguage.fromCode(getPreferences(MODE_PRIVATE).getString("language", "fa"))
        languageSpinner.setSelection(selectedLanguage.ordinal)
        languageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val language = SpeechLanguage.entries[position]
                if (language == selectedLanguage) return
                if (loading || listening) {
                    languageSpinner.setSelection(selectedLanguage.ordinal)
                    return
                }
                model?.close()
                model = null
                loadedLanguage = null
                selectedLanguage = language
                getPreferences(MODE_PRIVATE).edit().putString("language", language.code).apply()
                updateEngine()
                updateTtsVoice()
            }
        }
        listenButton.setOnClickListener { if (listening) finishListening() else requestMicrophoneAndListen() }
        findViewById<Button>(R.id.run).setOnClickListener { runCommand(commandInput.text.toString()) }
        importButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/zip"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            }
            startActivityForResult(intent, REQUEST_MODEL)
        }
        findViewById<Button>(R.id.accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            setStatus("در تنظیمات، سرویس «کنترل اختیاری برنامه‌ها با آری‌ویس» را فعال کنید.")
        }
        findViewById<Button>(R.id.customize).setOnClickListener { choosePersonalizedName() }
        findViewById<Button>(R.id.removeNickname).setOnClickListener { removePersonalizedName() }
        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) handler.post { if (!isDestroyed) updateTtsVoice() }
        }
        updateEngine()
        updateAccessibilityStatus()
        if (savedInstanceState == null && !ModelImporter.isInstalled(this, selectedLanguage) &&
            !getPreferences(MODE_PRIVATE).getBoolean("setup-offered", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("setup-offered", true).apply()
            AlertDialog.Builder(this).setTitle("راه‌اندازی فرمان صوتی آفلاین")
                .setMessage("بستهٔ زبان گوشی شما ممکن است نصب نباشد. مدل گفتار زبان انتخابی حدود ${if (selectedLanguage == SpeechLanguage.PERSIAN) "۵۳" else "۴۰"} مگابایت است. فقط برای دانلود اولیه اینترنت لازم است. اکنون دریافت شود؟")
                .setPositiveButton("دریافت مدل گفتار") { _, _ -> downloadModel(false, selectedLanguage) }
                .setNegativeButton("بعداً", null).show()
        }
        autoListen = intent.getBooleanExtra(EXTRA_AUTO_LISTEN, false)
        if (autoListen && savedInstanceState == null) handler.post { requestMicrophoneAndListen() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        autoListen = intent.getBooleanExtra(EXTRA_AUTO_LISTEN, false)
        if (autoListen) handler.post { requestMicrophoneAndListen() }
    }

    override fun onResume() {
        super.onResume()
        if (::accessibilityState.isInitialized) updateAccessibilityStatus()
        if (::status.isInitialized) renderWorkflow()
    }

    private fun configureNavigation() {
        val sections = listOf(R.id.homePanel, R.id.activityPanel, R.id.appsPanel, R.id.settingsPanel)
        val navigation = listOf(R.id.navHome, R.id.navActivity, R.id.navApps, R.id.navSettings)
        val titles = listOf("آری‌ویس", "فعالیت‌ها", "برنامه‌های من", "تنظیمات")
        navigation.forEachIndexed { index, id ->
            findViewById<Button>(id).setOnClickListener {
                sections.forEachIndexed { section, panel ->
                    findViewById<View>(panel).visibility = if (section == index) View.VISIBLE else View.GONE
                }
                findViewById<TextView>(R.id.pageTitle).text = titles[index]
                if (index == 2) updateAppsPanel()
                findViewById<ScrollView>(R.id.mainScroll).scrollTo(0, 0)
                renderWorkflow()
            }
        }
    }

    private fun renderWorkflow() {
        val current = workflowHistory.current
        val timeline = current?.steps?.joinToString("\n") { step ->
            val symbol = when (step.stage) {
                WorkflowHistory.Stage.STOPPED, WorkflowHistory.Stage.CANCELLED -> "!"
                WorkflowHistory.Stage.WAITING -> "…"
                else -> "✓"
            }
            "$symbol ${step.description}"
        } ?: "هنوز فرمانی در این نشست اجرا نشده است."
        findViewById<TextView>(R.id.workflowTimeline).text = timeline
        val entries = workflowHistory.recent
        findViewById<TextView>(R.id.activityLog).text = if (entries.isEmpty()) "هنوز فعالیتی نیست."
            else entries.joinToString("\n\n") { entry ->
                "${entry.title}\n" + entry.steps.joinToString("\n") { step -> "• ${step.description}" }
            }
        val running = current?.finished == false
        findViewById<Button>(R.id.cancelWorkflow).visibility = if (running) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.activityCancel).visibility = if (running) View.VISIBLE else View.GONE
    }

    private fun markWorkflow(stage: WorkflowHistory.Stage, description: String,
                             done: Boolean = false, generation: Int = workflowGeneration) {
        if (workflowHistory.advance(generation, stage, description, done)) {
            if (stage == WorkflowHistory.Stage.STOPPED || stage == WorkflowHistory.Stage.CANCELLED) recentApp = null
            renderWorkflow()
        }
    }

    private fun cancelActiveWorkflow() {
        val current = workflowHistory.current ?: return
        if (current.finished) return
        workflowGeneration++ // queued retries and delayed media actions must not resume.
        workflowHistory.cancel(current.id)
        recentApp = null
        setStatus("ادامهٔ فرمان لغو شد. اگر مرحله‌ای قبلاً انجام شده، لغو آن در اپ مقصد خودکار نیست.")
        renderWorkflow()
    }

    private fun updateAccessibilityStatus() {
        accessibilityState.text = if (VoiceAccessibilityService.active != null)
            "● کنترل داخل برنامه‌ها فعال است؛ از کاشی آری‌ویس در تنظیمات سریع استفاده کنید."
        else "○ کنترل داخل برنامه‌ها خاموش است؛ بازکردن اپ و جستجو بدون آن کار می‌کنند."
    }

    private fun updateTtsVoice() {
        offlineVoiceReady = false
        // TTS is optional: only local voices for the selected language are permitted.
        val voice = tts?.voices?.firstOrNull {
            it.locale.language == selectedLanguage.code && !it.isNetworkConnectionRequired
        }
        if (voice != null) offlineVoiceReady = tts?.setVoice(voice) == TextToSpeech.SUCCESS
    }

    private fun updateAiStatus() {
        aiStatus.text = if (ChatModel.installed(this))
            "● مدل GGUF نصب است؛ گفت‌وگو پس از بارگذاری اولیه کاملاً آفلاین است."
        else "○ مدل گفت‌وگو نصب نیست. آن را یک‌بار دریافت یا از فایل GGUF وارد کنید."
    }

    private fun downloadModel(isChat: Boolean, language: SpeechLanguage) {
        if (downloadBusy || aiBusy || listening || loading) return
        downloadBusy = true
        downloadCancelled = false
        languageSpinner.isEnabled = false
        if (!isChat && loadedLanguage == language) {
            model?.close()
            model = null
            loadedLanguage = null
        }
        val button = if (isChat) aiDownload else voiceDownload
        button.text = "لغو دانلود"
        if (isChat) aiStatus.text = "در حال دریافت مدل… صفحه را باز نگه دارید."
        else setStatus("در حال دریافت مدل گفتار… صفحه را باز نگه دارید.")
        val url = if (isChat) ModelCatalog.CHAT else if (language == SpeechLanguage.PERSIAN) ModelCatalog.PERSIAN_STT else ModelCatalog.ENGLISH_STT
        val temp = File(cacheDir, if (isChat) "ai-download.gguf" else "speech-download.zip")
        val context = applicationContext
        val progress: (Int) -> Unit = { percent ->
            handler.post { if (!isDestroyed) {
                if (isChat) aiStatus.text = "در حال دریافت مدل هوش مصنوعی: $percent٪"
                else setStatus("در حال دریافت مدل گفتار: $percent٪")
            } }
        }
        Thread {
            var failure: String? = null
            try {
                val limit = if (isChat) 800_000_000L else 200_000_000L
                ModelDownload.download(url, temp, limit, { downloadCancelled }, progress,
                    if (isChat) ModelCatalog.CHAT_SHA256 else null)
                if (downloadCancelled) error("دانلود لغو شد")
                if (isChat) {
                    chat?.unload()
                    ChatModel.import(context, Uri.fromFile(temp))
                } else {
                    ModelImporter.import(context, Uri.fromFile(temp), language)
                }
            } catch (e: Exception) { failure = e.message ?: "خطای نامشخص" }
            finally { temp.delete() }
            handler.post {
                if (isDestroyed) return@post
                downloadBusy = false
                languageSpinner.isEnabled = true
                button.text = if (isChat) "دریافت مدل هوش مصنوعی (~۴۹۱ مگابایت)"
                    else "دریافت مدل گفتار زبان انتخابی (یک‌بار)"
                if (isChat) {
                    updateAiStatus()
                    if (failure != null) aiStatus.text = "دریافت مدل ناموفق بود: $failure"
                } else {
                    updateEngine()
                    setStatus(if (failure == null) "مدل گفتار آماده است؛ اکنون دکمهٔ شنیدن را بزنید."
                              else "دریافت مدل ناموفق بود: $failure")
                    if (failure == null && !ChatModel.installed(this) &&
                        !getPreferences(MODE_PRIVATE).getBoolean("ai-offered", false)) {
                        getPreferences(MODE_PRIVATE).edit().putBoolean("ai-offered", true).apply()
                        AlertDialog.Builder(this).setTitle("گفت‌وگوی هوش مصنوعی آفلاین")
                            .setMessage("مدل سبک Qwen حدود ۴۹۱ مگابایت است. آیا آن را هم اکنون برای پاسخ‌های گفت‌وگویی روی گوشی دریافت کنیم؟")
                            .setPositiveButton("دریافت") { _, _ -> downloadModel(true, language) }
                            .setNegativeButton("بعداً", null).show()
                    }
                }
            }
        }.start()
    }

    private fun askAi(question: String) {
        if (question.isBlank()) { aiStatus.text = "ابتدا سؤال خود را در کادر بالا بنویسید یا بگویید."; return }
        if (!ChatModel.installed(this)) {
            aiStatus.text = "مدل هوش مصنوعی نصب نیست. در تنظیمات آن را دریافت یا وارد کنید."
            setStatus("مدل پرسش و پاسخ نصب نیست؛ از بخش تنظیمات آن را به‌صورت اختیاری دریافت کنید.")
            return
        }
        if (aiBusy || downloadBusy) return
        aiBusy = true
        askButton.isEnabled = false
        aiStatus.text = "در حال تولید پاسخ روی گوشی… بار اول ممکن است کمی طول بکشد."
        aiResponse.text = ""
        val language = selectedLanguage
        val instance = chat ?: OfflineChat(applicationContext).also { chat = it }
        Thread {
            val result = runCatching { instance.reply(question, language) }
            handler.post {
                if (isDestroyed) return@post
                aiBusy = false
                askButton.isEnabled = true
                result.onSuccess { answer ->
                    aiStatus.text = "● پاسخ آفلاین آماده است"
                    aiResponse.text = answer
                    if (offlineVoiceReady) tts?.speak(answer.take(500), TextToSpeech.QUEUE_FLUSH, null, "ai-response")
                }.onFailure { error -> aiStatus.text = "پاسخ‌دادن مدل ناموفق بود: ${error.message}" }
            }
        }.start()
    }

    private fun systemSpeechAvailable(): Boolean =
        Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this) &&
            !getPreferences(MODE_PRIVATE).getBoolean("on-device-error-${selectedLanguage.code}", false)

    private fun updateEngine() {
        val languageName = if (selectedLanguage == SpeechLanguage.PERSIAN) "فارسی" else "انگلیسی"
        engineStatus.text = when {
            ModelImporter.isInstalled(this, selectedLanguage) -> "● مدل Vosk $languageName نصب است • تشخیص گفتار آفلاین"
            systemSpeechAvailable() ->
                "● تشخیص گفتار آفلاین سیستم (در صورت نصب بستهٔ $languageName)"
            else -> "○ برای فرمان صوتی $languageName، مدل آفلاین Vosk را وارد کنید"
        }
    }

    private fun requestMicrophoneAndListen() {
        if (loading || listening || downloadBusy || aiBusy) return
        if (workflowHistory.current?.finished == false) cancelActiveWorkflow()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
        } else startListening()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startListening()
            else setStatus("برای شنیدن فرمان باید اجازهٔ میکروفون را بدهید؛ فرمان نوشتاری همچنان کار می‌کند.")
        }
    }

    private fun startListening() {
        handled = false
        languageSpinner.isEnabled = false
        if (ModelImporter.isInstalled(this, selectedLanguage)) {
            loading = true
            setStatus("در حال بارگذاری مدل آفلاین…")
            listenButton.isEnabled = false
            val language = selectedLanguage
            val cached = model?.takeIf { loadedLanguage == language }
            Thread {
                try {
                    val loaded = cached ?: Model(ModelImporter.installedModel(this, language).absolutePath)
                    handler.post {
                        if (isFinishing || isDestroyed) {
                            if (loaded !== cached) loaded.close()
                            return@post
                        }
                        model = loaded
                        loadedLanguage = language
                        loading = false
                        listenButton.isEnabled = true
                        startVosk(loaded)
                    }
                } catch (e: Exception) {
                    handler.post {
                        loading = false
                        listenButton.isEnabled = true
                        languageSpinner.isEnabled = true
                        setStatus("مدل بارگذاری نشد: ${e.message}")
                    }
                }
            }.start()
        } else if (systemSpeechAvailable()) {
            startSystemRecognizer()
        } else {
            languageSpinner.isEnabled = true
            setStatus("ابتدا دکمهٔ «دریافت مدل گفتار» را بزنید؛ فقط دانلود اولیه اینترنت لازم دارد.")
        }
    }

    private fun startVosk(loaded: Model) {
        try {
            speechService = SpeechService(Recognizer(loaded, 16000.0f), 16000.0f).also { service ->
                service.startListening(object : VoskListener {
                    override fun onPartialResult(hypothesis: String?) {
                        val text = jsonText(hypothesis, "partial")
                        if (text.isNotBlank()) handler.post { if (!handled) commandInput.setText(text) }
                    }
                    override fun onResult(hypothesis: String?) = receiveVosk(hypothesis)
                    override fun onFinalResult(hypothesis: String?) {
                        if (!handled && jsonText(hypothesis, "text").isBlank()) handler.post {
                            if (!handled) { stopListening(); setStatus("فرمانی شنیده نشد.") }
                        }
                        else receiveVosk(hypothesis)
                    }
                    override fun onError(exception: Exception?) {
                        handler.post { if (!handled) { stopListening(); setStatus("خطای میکروفون: ${exception?.message}") } }
                    }
                    override fun onTimeout() { handler.post {
                        if (!handled) { stopListening(); setStatus("زمان شنیدن تمام شد؛ دوباره تلاش کنید.") }
                    } }
                }, 10000)
            }
            listening = true
            listenButton.text = "■  توقف شنیدن"
            setStatus("دارم گوش می‌دهم… (آفلاین)")
        } catch (e: Exception) {
            stopListening()
            setStatus("شروع میکروفون ناموفق بود: ${e.message}")
        }
    }

    private fun receiveVosk(json: String?) {
        val text = jsonText(json, "text")
        if (text.isNotBlank()) handler.post { receiveSpeech(text) }
    }

    private fun jsonText(json: String?, key: String): String = try {
        JSONObject(json ?: "{}").optString(key, "").trim()
    } catch (_: Exception) { "" }

    private fun startSystemRecognizer() {
        try {
            // createOnDeviceSpeechRecognizer never falls back to a network recognizer.
            androidRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this).also { recognizer ->
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { setStatus("دارم گوش می‌دهم… (آفلاین گوشی)") }
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onError(error: Int) {
                        if (handled) return
                        stopListening()
                        if (error == 12 || error == 13) {
                            getPreferences(MODE_PRIVATE).edit()
                                .putBoolean("on-device-error-${selectedLanguage.code}", true).apply()
                            updateEngine()
                        }
                        setStatus("بستهٔ گفتار آفلاین گوشی در دسترس نیست (خطا $error). از بخش راه‌اندازی، مدل گفتار را دریافت کنید.")
                    }
                    override fun onResults(results: Bundle?) {
                        if (handled) return
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (text.isNullOrBlank()) { stopListening(); setStatus("فرمانی شنیده نشد.") }
                        else receiveSpeech(text)
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (!text.isNullOrBlank() && !handled) commandInput.setText(text)
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
                recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, selectedLanguage.recognitionTag)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                })
            }
            listening = true
            listenButton.text = "■  توقف شنیدن"
        } catch (e: Exception) {
            stopListening()
            setStatus("تشخیص آفلاین گوشی فعال نشد: ${e.message}. مدل Vosk را وارد کنید.")
        }
    }

    private fun receiveSpeech(text: String) {
        if (handled) return
        handled = true
        commandInput.setText(text)
        stopListening()
        runCommand(text)
    }

    private fun finishListening() {
        listenButton.isEnabled = false
        if (speechService != null) {
            listening = false
            listenButton.text = "🎙  شروع شنیدن"
            // stop(), unlike cancel(), delivers the final words of a short command.
            speechService?.stop()
            speechService?.shutdown()
            speechService = null
            setStatus("در حال پردازش گفتار…")
        } else {
            androidRecognizer?.stopListening()
            setStatus("در حال پردازش گفتار…")
        }
    }

    private fun stopListening() {
        handled = true
        listening = false
        listenButton.isEnabled = true
        languageSpinner.isEnabled = !loading
        listenButton.text = "🎙  شروع شنیدن"
        speechService?.cancel()
        speechService?.shutdown()
        speechService = null
        androidRecognizer?.cancel()
        androidRecognizer?.destroy()
        androidRecognizer = null
    }

    private fun runCommand(input: String) {
        // A new utterance supersedes any pending retries from the previous app workflow.
        workflowGeneration++
        val isFollowUp = FollowUpParser.referencesPreviousApp(input)
        val context = recentApp?.takeIf { SystemClock.elapsedRealtime() - it.openedAt < 90_000L }
        if (!isFollowUp) recentApp = null
        val parsed = if (isFollowUp) context?.let { FollowUpParser.parse(input, it.name) }
                     else CommandParser.parse(input)
        if (parsed == null) {
            workflowHistory.current?.takeIf { !it.finished }?.let { workflowHistory.cancel(it.id); renderWorkflow() }
            if (isFollowUp) {
                setStatus("برنامهٔ قبلی مشخص نیست یا این ادامه پشتیبانی نمی‌شود؛ نام اپ و عبارت جستجو را کامل بگویید.")
                return
            }
            if (ChatModel.installed(this)) askAi(input)
            else setStatus("فرمان را نشناختم. برای سؤال آزاد ابتدا مدل هوش مصنوعی را دریافت کنید.")
            return
        }
        val generation = workflowGeneration
        workflowHistory.begin(generation, workflowTitle(parsed))
        renderWorkflow()
        when (parsed) {
            is VoiceCommand.Search -> {
                val uri = Uri.Builder().scheme("https").authority("www.google.com")
                    .appendPath("search").appendQueryParameter("q", parsed.query).build()
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    feedback("جستجو در مرورگر باز شد", "Search opened in browser")
                    markWorkflow(WorkflowHistory.Stage.HANDOFF, "درخواست به مرورگر تحویل شد؛ نتایج تأیید نشده‌اند", true, generation)
                } catch (_: Exception) {
                    setStatus("مرورگری برای جستجو پیدا نشد.")
                    markWorkflow(WorkflowHistory.Stage.STOPPED, "مرورگر پیدا نشد", true, generation)
                }
            }
            is VoiceCommand.OpenApp -> openApp(parsed.name) { targetPackage ->
                if (generation == workflowGeneration && targetPackage !in listOf("browser", "com.android.settings"))
                    recentApp = RecentApp(parsed.name, SystemClock.elapsedRealtime())
                markWorkflow(WorkflowHistory.Stage.HANDOFF, "برنامه باز شد؛ کار دیگری درخواست نشده بود", true, generation)
            }
            is VoiceCommand.FindInApp -> {
                if (parsed.purchaseIntent) {
                    markWorkflow(WorkflowHistory.Stage.WAITING, "تأیید جستجو لازم است؛ خرید خودکار ممنوع")
                    AlertDialog.Builder(this).setTitle("فقط جستجو؛ بدون خرید")
                        .setMessage("«${parsed.query}» را در «${parsed.app}» جستجو می‌کنم. هیچ کالا، سفارش یا پرداختی را انتخاب یا تأیید نمی‌کنم؛ ادامه با خود شماست.")
                        .setPositiveButton("جستجو") { _, _ -> findInApp(parsed.app, parsed.query, false) }
                        .setNegativeButton("انصراف") { _, _ -> cancelActiveWorkflow() }.show()
                } else findInApp(parsed.app, parsed.query, false)
            }
            is VoiceCommand.ProfileInApp -> {
                if (parsed.username == null) {
                    markWorkflow(WorkflowHistory.Stage.WAITING, "نام کاربری از شما خواسته شد")
                    val inputField = EditText(this).apply { hint = "نام کاربری پروفایل"; isSingleLine = true }
                    AlertDialog.Builder(this).setTitle("کدام پروفایل؟")
                        .setView(inputField)
                        .setPositiveButton("جستجوی پروفایل") { _, _ ->
                            val username = inputField.text.toString().trim().removePrefix("@")
                            if (username.isBlank()) {
                                setStatus("نام کاربری وارد نشده است.")
                                markWorkflow(WorkflowHistory.Stage.STOPPED, "نام کاربری وارد نشد", true, generation)
                            } else findInApp(parsed.app, username, true)
                        }
                        .setNegativeButton("انصراف") { _, _ -> cancelActiveWorkflow() }.show()
                } else findInApp(parsed.app, parsed.username.removePrefix("@"), true)
            }
            is VoiceCommand.Media -> {
                val dispatched = sendMediaCommand(parsed.action)
                markWorkflow(if (dispatched) WorkflowHistory.Stage.HANDOFF else WorkflowHistory.Stage.STOPPED,
                    if (dispatched) "کلید رسانه ارسال شد؛ پاسخ پلیر تأیید نشده است" else "ارسال کلید رسانه ناموفق بود", true, generation)
            }
            is VoiceCommand.MediaInApp -> openApp(parsed.app, multiStep = true) { _ ->
                markWorkflow(WorkflowHistory.Stage.APP_OPENED, "برنامه باز شد؛ منتظر ارسال کلید رسانه")
                handler.postDelayed({
                    if (generation == workflowGeneration && !isDestroyed) {
                        val dispatched = sendMediaCommand(parsed.action)
                        markWorkflow(if (dispatched) WorkflowHistory.Stage.HANDOFF else WorkflowHistory.Stage.STOPPED,
                            if (dispatched) "کلید رسانه ارسال شد؛ پخش واقعی تأیید نشده است" else "ارسال کلید رسانه ناموفق بود", true, generation)
                    }
                }, 900)
            }
            is VoiceCommand.Download -> confirmDownload(parsed.url)
            VoiceCommand.DownloadPrompt -> {
                markWorkflow(WorkflowHistory.Stage.WAITING, "لینک مستقیم HTTPS از شما خواسته شد")
                val link = EditText(this).apply {
                    hint = "https://example.com/file.pdf"
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                    isSingleLine = true
                }
                AlertDialog.Builder(this).setTitle("لینک مستقیم فایل را وارد کنید")
                    .setMessage("برای دانلود باید نشانی HTTPS فایل را بدانم؛ عبارت مبهم را به یک فایل دلخواه تبدیل نمی‌کنم.")
                    .setView(link)
                    .setPositiveButton("بررسی لینک") { _, _ -> confirmDownload(link.text.toString().trim()) }
                    .setNegativeButton("انصراف") { _, _ -> cancelActiveWorkflow() }.show()
            }
            else -> {
                if (parsed is VoiceCommand.Tap && SensitiveActionPolicy.isUnsafeClick(parsed.label)) {
                    setStatus("لمس صوتیِ دکمه‌های خرید، پرداخت، تأیید یا ارسال غیرفعال است؛ این کار را خودتان انجام دهید.")
                    markWorkflow(WorkflowHistory.Stage.STOPPED, "کنترل حساس مجاز نیست", true, generation)
                    return
                }
                if (VoiceAccessibilityService.active == null) {
                    setStatus("برای لمس/نوشتن در برنامهٔ دیگر، سرویس دسترس‌پذیری آری‌ویس باید فعال باشد.")
                    markWorkflow(WorkflowHistory.Stage.STOPPED, "سرویس دسترس‌پذیری فعال نیست", true, generation)
                    AlertDialog.Builder(this).setTitle("کنترل برنامه‌های دیگر فعال نیست")
                        .setMessage("در تنظیمات دسترس‌پذیری، سرویس آری‌ویس را خودتان فعال کنید. سپس از کاشی آری‌ویس در تنظیمات سریع گوشی فرمان دهید تا به برنامهٔ قبلی برگردیم.")
                        .setPositiveButton("بازکردن تنظیمات") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                        .setNegativeButton("بعداً", null).show()
                    return
                }
                setStatus("در حال بازگشت به برنامهٔ قبلی…")
                moveTaskToBack(true)
                // Switching back from the assistant is asynchronous; retry only while another app is foreground.
                markWorkflow(WorkflowHistory.Stage.WAITING, "در انتظار صفحهٔ برنامهٔ قبلی")
                executeInPreviousApp(parsed, 0, generation)
            }
        }
    }

    private fun executeInPreviousApp(command: VoiceCommand, attempt: Int, generation: Int) {
        handler.postDelayed({
            if (isDestroyed || generation != workflowGeneration) return@postDelayed
            val service = VoiceAccessibilityService.active
            val done = service?.execute(command) == true
            if (!done && attempt < 4 && service != null) executeInPreviousApp(command, attempt + 1, generation)
            else if (done) {
                feedback("فرمان به برنامه فرستاده شد؛ نتیجه را بررسی کنید")
                markWorkflow(WorkflowHistory.Stage.HANDOFF, "عمل روی رابط پذیرفته شد؛ نتیجهٔ نهایی تأیید نشده است", true, generation)
            } else {
                setStatus("کنترل پیدا نشد. صفحهٔ برنامه باید باز باشد؛ برای لمس، نام دکمه باید دقیق و یکتا باشد.")
                markWorkflow(WorkflowHistory.Stage.STOPPED, "کنترل دقیق و یکتا در صفحه پیدا نشد", true, generation)
            }
        }, if (attempt == 0) 600 else 350)
    }

    private fun findInApp(app: String, query: String, profile: Boolean) {
        val normalized = CommandParser.normalize(app)
        if (normalized in listOf("مرورگر", "browser")) {
            // Browsers are better handled by a standard web intent than by inspecting their UI.
            val uri = Uri.Builder().scheme("https").authority("www.google.com")
                .appendPath("search").appendQueryParameter("q", query).build()
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
                feedback("جستجو در مرورگر باز شد", "Search opened in browser")
                markWorkflow(WorkflowHistory.Stage.HANDOFF, "درخواست جستجو به مرورگر تحویل شد", true)
            } catch (_: Exception) {
                setStatus("مرورگری برای جستجو پیدا نشد.")
                markWorkflow(WorkflowHistory.Stage.STOPPED, "مرورگر پیدا نشد", true)
            }
            return
        }
        if (VoiceAccessibilityService.active == null) {
            setStatus("برای جستجو در برنامه‌های دیگر، سرویس دسترس‌پذیری را فعال کنید؛ بازکردن سادهٔ اپ به آن نیاز ندارد.")
            markWorkflow(WorkflowHistory.Stage.STOPPED, "دسترس‌پذیری فعال نیست؛ اپ باز نشد", true)
            AlertDialog.Builder(this).setTitle("مجوز لازم است")
                .setMessage("کنترل صفحه فقط پس از فعال‌سازی دستی سرویس آری‌ویس در تنظیمات دسترس‌پذیری ممکن است.")
                .setPositiveButton("تنظیمات") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .setNegativeButton("بعداً", null).show()
            return
        }
        if (query.isBlank()) {
            setStatus("عبارت جستجو خالی است.")
            markWorkflow(WorkflowHistory.Stage.STOPPED, "عبارت جستجو خالی است", true)
            return
        }
        markWorkflow(WorkflowHistory.Stage.WAITING, "در حال یافتن برنامه و کادر جستجو")
        val generation = workflowGeneration
        openApp(app, multiStep = true) { targetPackage ->
            if (generation != workflowGeneration) return@openApp
            setStatus("برنامه باز شد؛ در حال پیدا‌کردن جستجو برای «$query»…")
            markWorkflow(WorkflowHistory.Stage.APP_OPENED, "برنامه باز شد؛ جستجو هنوز انجام نشده است", generation = generation)
            recentApp = RecentApp(app, SystemClock.elapsedRealtime())
            continueInAppSearch(targetPackage, query, profile, mutableSetOf(), 0, generation)
        }
    }

    private fun continueInAppSearch(targetPackage: String, query: String, profile: Boolean,
                                    clickedSearchControls: MutableSet<String>, attempts: Int, generation: Int) {
        handler.postDelayed({
            if (isDestroyed || generation != workflowGeneration) return@postDelayed
            val service = VoiceAccessibilityService.active
            if (service == null) {
                showWorkflowResult("کنترل صفحه قطع شد؛ جستجو ادامه نیافت.")
                markWorkflow(WorkflowHistory.Stage.STOPPED, "سرویس دسترس‌پذیری قطع شد", true, generation)
                return@postDelayed
            }
            when (val step = service.searchInApp(targetPackage, query, clickedSearchControls)) {
                VoiceAccessibilityService.SearchStep.WAIT_FOR_APP,
                VoiceAccessibilityService.SearchStep.WAIT_FOR_FIELD,
                VoiceAccessibilityService.SearchStep.NO_SEARCH_UI -> {
                    // Loading and tab transitions can take several seconds on real phones.
                    // Never act on a different foreground package while waiting.
                    if (attempts < 12) continueInAppSearch(targetPackage, query, profile, clickedSearchControls, attempts + 1, generation)
                    else {
                        markWorkflow(WorkflowHistory.Stage.STOPPED, when (step) {
                            VoiceAccessibilityService.SearchStep.WAIT_FOR_APP -> "اپ هدف در پیش‌زمینه نبود"
                            VoiceAccessibilityService.SearchStep.WAIT_FOR_FIELD -> "مسیر جستجو باز شد؛ کادر قابل‌دسترسی نبود"
                            else -> "دکمه یا کادر جستجو پیدا نشد"
                        }, true, generation)
                        showWorkflowResult(when (step) {
                        VoiceAccessibilityService.SearchStep.WAIT_FOR_APP ->
                            "صفحهٔ «$targetPackage» در پیش‌زمینه نبود؛ برای ایمنی جستجو ادامه نیافت."
                        VoiceAccessibilityService.SearchStep.WAIT_FOR_FIELD ->
                            "مسیر جستجو باز شد، اما کادر نوشتن در این برنامه قابل‌دسترسی نبود. چیزی نوشته نشد."
                        else -> "دکمه یا کادر جستجوی قابل‌دسترسی پیدا نشد. چیزی نوشته یا انتخاب نشد."
                        })
                    }
                }
                VoiceAccessibilityService.SearchStep.OPENED_SEARCH -> {
                    markWorkflow(WorkflowHistory.Stage.SEARCH_OPENED, "کنترل جستجو باز شد؛ منتظر کادر نوشتن", generation = generation)
                    continueInAppSearch(targetPackage, query, profile, clickedSearchControls, 0, generation)
                }
                VoiceAccessibilityService.SearchStep.ENTERED_QUERY -> {
                    markWorkflow(WorkflowHistory.Stage.ENTERED, "عبارت در کادر نوشته شد؛ ارسال تأیید نشده است", true, generation)
                    showWorkflowResult(if (profile) "نام کاربری «$query» در جستجو نوشته شد. پروفایل درست را خودتان انتخاب کنید."
                        else "«$query» نوشته شد؛ اگر نتایج نیامد کلید جستجو را بزنید. نتیجه و هر خریدی را خودتان بررسی کنید.")
                }
                VoiceAccessibilityService.SearchStep.SUBMITTED -> {
                    markWorkflow(WorkflowHistory.Stage.SUBMITTED, "درخواست جستجو ارسال شد؛ نتیجه را خودتان بررسی کنید", true, generation)
                    showWorkflowResult(if (profile) "جستجوی «$query» ارسال شد؛ پروفایل درست را خودتان انتخاب کنید."
                        else "جستجوی «$query» ارسال شد؛ نتیجه و هر خریدی را خودتان بررسی کنید.")
                }
                VoiceAccessibilityService.SearchStep.AMBIGUOUS -> {
                    markWorkflow(WorkflowHistory.Stage.STOPPED, "چند کنترل جستجوی مشابه دیده شد", true, generation)
                    showWorkflowResult("چند کادر یا دکمهٔ جستجو دیده شد؛ برای جلوگیری از اشتباه خودتان یکی را انتخاب کنید.")
                }
            }
        }, if (attempts == 0) 1100 else 500)
    }

    private fun showWorkflowResult(message: String) {
        setStatus(message)
        // The assistant is behind the target app at this point; a status label alone is invisible.
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }

    private fun sendMediaCommand(action: MediaAction): Boolean {
        val key = when (action) {
            MediaAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        try {
            val audio = getSystemService(AUDIO_SERVICE) as AudioManager
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
            setStatus("فرمان به پلیر فعال فرستاده شد. اگر پلیر از کنترل رسانه پشتیبانی نکند ممکن است اجرا نشود.")
            return true
        } catch (_: Exception) {
            setStatus("کنترل رسانه روی این گوشی یا این پلیر در دسترس نیست.")
            return false
        }
    }

    private fun confirmDownload(url: String) {
        val parsed = runCatching { URI(url) }.getOrNull()
        if (parsed?.scheme != "https" || parsed.host.isNullOrBlank() || parsed.rawUserInfo != null) {
            setStatus("دانلود فقط با لینک مستقیم HTTPS معتبر پذیرفته می‌شود.")
            markWorkflow(WorkflowHistory.Stage.STOPPED, "لینک HTTPS معتبر وارد نشد", true)
            return
        }
        val host = parsed.host ?: return
        val rawName = parsed.path?.substringAfterLast('/')?.take(80).orEmpty()
        val safeName = rawName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "ari-download"
        markWorkflow(WorkflowHistory.Stage.WAITING, "در انتظار تأیید دانلود فایل")
        AlertDialog.Builder(this).setTitle("تأیید دانلود")
            .setMessage("فایل از $host در پوشهٔ دانلودها ذخیره می‌شود. از امن‌بودن منبع مطمئن شوید؛ فایل اجرا یا نصب نمی‌شود. ادامه؟")
            .setPositiveButton("دانلود") { _, _ ->
                try {
                    val name = "ari-${System.currentTimeMillis()}-$safeName"
                    val request = DownloadManager.Request(Uri.parse(url))
                        .setTitle(name)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    if (Build.VERSION.SDK_INT >= 29) request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                    else request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name)
                    (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                    setStatus("دانلود شروع شد؛ نتیجه را در اعلان‌ها یا برنامهٔ فایل‌ها بررسی کنید.")
                    markWorkflow(WorkflowHistory.Stage.HANDOFF, "درخواست به مدیر دانلود تحویل شد؛ فایل هنوز تأیید نشده است", true)
                } catch (e: Exception) {
                    setStatus("شروع دانلود ناموفق بود: ${e.message}")
                    markWorkflow(WorkflowHistory.Stage.STOPPED, "مدیر دانلود درخواست را نپذیرفت", true)
                }
            }
            .setNegativeButton("انصراف") { _, _ -> cancelActiveWorkflow() }.show()
    }

    private fun launcherApps(): List<ResolveInfo> = packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
    ).filter { it.activityInfo.packageName != packageName }
        .distinctBy { it.activityInfo.packageName }
        .sortedBy { it.loadLabel(packageManager).toString().lowercase() }

    private fun savedAliases(): List<Pair<String, String>> = getPreferences(MODE_PRIVATE).all
        .mapNotNull { (key, value) ->
            if (key.startsWith("app-alias:") && value is String) key.removePrefix("app-alias:") to value
            else null
        }.sortedBy { it.first }

    private fun updateAppsPanel() {
        val apps = launcherApps()
        val labels = apps.associate { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
        val aliases = savedAliases().map { (alias, pkg) -> "$alias ← ${labels[pkg] ?: "اپِ دیگر"}" }
        val preview = apps.take(12).joinToString("، ") { it.loadLabel(packageManager).toString() }
        findViewById<TextView>(R.id.installedApps).text =
            "اپ‌های قابل‌اجرای دیده‌شده: ${apps.size}\n" +
            (if (preview.isEmpty()) "فعلاً فهرستی دیده نشد." else "$preview${if (apps.size > 12) " …" else ""}") +
            "\n\nنام‌های صوتی شما:\n" +
            (if (aliases.isEmpty()) "هنوز نام دلخواهی ذخیره نشده است." else aliases.joinToString("\n"))
    }

    private fun removePersonalizedName() {
        val aliases = savedAliases()
        if (aliases.isEmpty()) { setStatus("نام صوتی ذخیره‌شده‌ای برای حذف وجود ندارد."); return }
        AlertDialog.Builder(this).setTitle("کدام نام صوتی حذف شود؟")
            .setItems(aliases.map { it.first }.toTypedArray()) { _, index ->
                getPreferences(MODE_PRIVATE).edit().remove("app-alias:${aliases[index].first}").apply()
                setStatus("نام صوتی دلخواه حذف شد؛ اپ از گوشی حذف نشده است.")
                updateAppsPanel()
            }.setNegativeButton("انصراف", null).show()
    }

    private fun choosePersonalizedName() {
        val apps = launcherApps()
        if (apps.isEmpty()) { setStatus("فهرست برنامه‌های نصب‌شده قابل‌خواندن نیست."); return }
        val labels = apps.map { "${it.loadLabel(packageManager)} (${it.activityInfo.packageName})" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("برای کدام برنامه نام دلخواه می‌خواهید؟")
            .setItems(labels) { _, index ->
                val chosen = apps[index]
                val input = EditText(this).apply {
                    hint = "مثلاً فروشگاه من"
                    inputType = InputType.TYPE_CLASS_TEXT
                    isSingleLine = true
                }
                AlertDialog.Builder(this).setTitle("نام صوتی برای ${chosen.loadLabel(packageManager)}")
                    .setView(input)
                    .setPositiveButton("ذخیره") { _, _ ->
                        val alias = CommandParser.normalize(input.text.toString())
                        if (alias.isBlank() || alias.length > 40 ||
                            alias in listOf("تنظیمات", "settings", "مرورگر", "browser") ||
                            CommandParser.parse("باز کن $alias") != VoiceCommand.OpenApp(alias)) {
                            setStatus("نام دلخواه نامعتبر است یا شبیه یک فرمانِ دیگر فهمیده می‌شود.")
                        } else {
                            val previous = getPreferences(MODE_PRIVATE).getString("app-alias:$alias", null)
                            if (previous != null && previous != chosen.activityInfo.packageName) {
                                AlertDialog.Builder(this).setTitle("این نام قبلاً استفاده شده")
                                    .setMessage("آیا نام «$alias» را به برنامهٔ جدید منتقل کنم؟")
                                    .setPositiveButton("جایگزینی") { _, _ -> saveAlias(alias, chosen) }
                                    .setNegativeButton("انصراف", null).show()
                            } else saveAlias(alias, chosen)
                        }
                    }
                    .setNegativeButton("انصراف", null).show()
            }.setNegativeButton("انصراف", null).show()
    }

    private fun saveAlias(alias: String, chosen: ResolveInfo) {
        getPreferences(MODE_PRIVATE).edit()
            .putString("app-alias:$alias", chosen.activityInfo.packageName).apply()
        setStatus("نام «$alias» برای ${chosen.loadLabel(packageManager)} ذخیره شد؛ بگویید «باز کن $alias».")
        updateAppsPanel()
    }

    private fun openApp(name: String, multiStep: Boolean = false, onLaunched: ((String) -> Unit)? = null) {
        if (name in listOf("تنظیمات", "settings")) {
            if (multiStep) { stopOpeningApp("اجرای فرمان چندگامی در تنظیمات پشتیبانی نمی‌شود."); return }
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
                feedback("تنظیمات باز شد", "Settings opened")
                onLaunched?.invoke("com.android.settings")
            } catch (_: Exception) { stopOpeningApp("تنظیمات باز نشد.") }
            return
        }
        if (name in listOf("مرورگر", "browser")) {
            if (multiStep) { stopOpeningApp("برای مرورگر بگویید «جستجو کن ...»."); return }
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")))
                feedback("مرورگر باز شد", "Browser opened")
                onLaunched?.invoke("browser")
            } catch (_: Exception) { stopOpeningApp("مرورگری پیدا نشد.") }
            return
        }
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(query, 0)
            .filter { it.activityInfo.packageName != packageName }
        val ranked = apps.map { it to AppNameMatcher.score(name, it.loadLabel(packageManager).toString()) }
            .filter { it.second > 0 }.sortedByDescending { it.second }
        // A user-defined local nickname takes precedence over any automatic label match.
        val normalized = CommandParser.normalize(name)
        val savedPackage = getPreferences(MODE_PRIVATE).getString("app-alias:$normalized", null)
        if (savedPackage == null && ranked.firstOrNull()?.second == 100 && ranked.count { it.second == 100 } == 1) {
            launchApp(ranked.first().first, onLaunched); return
        }
        // Explicit package visibility covers phones that hide a launcher from queryIntentActivities.
        val packages = listOfNotNull(savedPackage) + AppNameMatcher.packageCandidates(name)
        for (candidate in packages.distinct()) {
            val intent = packageManager.getLaunchIntentForPackage(candidate)
            if (intent == null) {
                val visible = apps.firstOrNull { it.activityInfo.packageName == candidate }
                if (visible != null) { launchApp(visible, onLaunched); return }
                continue
            }
            try {
                startActivity(intent)
                feedback("${candidate.substringAfterLast('.')} باز شد", "App opened")
                onLaunched?.invoke(candidate)
                return
            } catch (_: Exception) { /* Try the next visible launcher. */ }
        }
        val topScore = ranked.firstOrNull()?.second
        if (topScore != null && topScore >= 90 && ranked.count { it.second == topScore } == 1) {
            launchApp(ranked.first().first, onLaunched); return
        }
        val choices = if (ranked.isNotEmpty()) ranked.map { it.first } else apps
        if (choices.isEmpty()) {
            stopOpeningApp("فهرست برنامه‌های قابل اجرا در دسترس نیست. دسترسی نمایش برنامه‌ها را در تنظیمات گوشی بررسی کنید.")
            return
        }
        setStatus("نام «$name» دقیقاً پیدا نشد؛ برنامهٔ درست را از فهرست انتخاب کنید تا نام صوتی آن ذخیره شود.")
        markWorkflow(WorkflowHistory.Stage.WAITING, "انتخاب برنامهٔ درست با شماست")
        showAppPicker(name, choices, onLaunched)
    }

    private fun launchApp(app: ResolveInfo, onLaunched: ((String) -> Unit)? = null) {
        val label = app.loadLabel(packageManager).toString()
        try {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).apply {
                component = ComponentName(app.activityInfo.packageName, app.activityInfo.name)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            feedback("$label باز شد", "$label opened")
            onLaunched?.invoke(app.activityInfo.packageName)
        } catch (_: Exception) { stopOpeningApp("بازکردن «$label» امکان‌پذیر نیست.") }
    }

    private fun showAppPicker(voiceName: String, apps: List<ResolveInfo>, onLaunched: ((String) -> Unit)? = null) {
        val sorted = apps.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) {
            it.loadLabel(packageManager).toString()
        })
        val labels = sorted.map { "${it.loadLabel(packageManager)}  (${it.activityInfo.packageName})" }.toTypedArray()
        val generation = workflowGeneration
        AlertDialog.Builder(this).setTitle("انتخاب برنامه برای «$voiceName»")
            .setItems(labels) { _, index ->
                if (generation != workflowGeneration) return@setItems
                val app = sorted[index]
                launchApp(app) { chosenPackage ->
                    getPreferences(MODE_PRIVATE).edit()
                        .putString("app-alias:${CommandParser.normalize(voiceName)}", chosenPackage).apply()
                    onLaunched?.invoke(chosenPackage)
                }
            }
            .setNegativeButton("انصراف") { _, _ -> cancelActiveWorkflow() }
            .setOnCancelListener { cancelActiveWorkflow() }.show()
    }

    private fun stopOpeningApp(message: String) {
        setStatus(message)
        markWorkflow(WorkflowHistory.Stage.STOPPED, "برنامه باز نشد یا این مسیر پشتیبانی نمی‌شود", true)
    }

    private fun feedback(message: String, englishMessage: String = "Done") {
        setStatus(message)
        val spoken = if (selectedLanguage == SpeechLanguage.ENGLISH) englishMessage else message
        if (offlineVoiceReady) tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "response")
    }
    private fun setStatus(message: String) { status.text = message }

    @Deprecated("System document picker result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || requestCode !in listOf(REQUEST_MODEL, REQUEST_AI)) return
        val uri = data?.data ?: return
        if (requestCode == REQUEST_AI) {
            if (aiBusy || downloadBusy) return
            aiBusy = true
            askButton.isEnabled = false
            aiStatus.text = "در حال ذخیرهٔ مدل GGUF…"
            Thread {
                val result = runCatching {
                    chat?.unload()
                    ChatModel.import(applicationContext, uri)
                }
                handler.post {
                    if (isDestroyed) return@post
                    aiBusy = false
                    askButton.isEnabled = true
                    updateAiStatus()
                    result.onFailure { aiStatus.text = "واردکردن مدل ناموفق بود: ${it.message}" }
                }
            }.start()
            return
        }
        if (loading || listening || downloadBusy || aiBusy) return
        loading = true
        languageSpinner.isEnabled = false
        importButton.isEnabled = false
        listenButton.isEnabled = false
        setStatus("در حال استخراج مدل در حافظهٔ گوشی…")
        // No recognizer is running while importing; release the cached model before replacing its files.
        model?.close()
        model = null
        loadedLanguage = null
        val language = selectedLanguage
        Thread {
            try {
                ModelImporter.import(applicationContext, uri, language)
                handler.post {
                    loading = false
                    importButton.isEnabled = true
                    listenButton.isEnabled = true
                    languageSpinner.isEnabled = true
                    updateEngine()
                    setStatus("مدل آفلاین آماده است. حالا دکمهٔ شنیدن را بزنید.")
                }
            } catch (e: Exception) {
                handler.post {
                    loading = false
                    importButton.isEnabled = true
                    listenButton.isEnabled = true
                    languageSpinner.isEnabled = true
                    updateEngine()
                    setStatus("واردکردن مدل ناموفق بود: ${e.message}")
                }
            }
        }.start()
    }

    override fun onDestroy() {
        downloadCancelled = true
        stopListening()
        // A model may still be loading on a worker; that worker closes it if this activity is destroyed.
        model?.close(); model = null
        tts?.stop(); tts?.shutdown()
        super.onDestroy()
    }
}

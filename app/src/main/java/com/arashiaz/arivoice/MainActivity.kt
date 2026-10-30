package com.arashiaz.arivoice

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.AdapterView
import android.widget.Button
import android.widget.Spinner
import android.view.View
import android.widget.EditText
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
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var engineStatus: TextView
    private lateinit var commandInput: EditText
    private lateinit var listenButton: Button
    private lateinit var importButton: Button
    private lateinit var languageSpinner: Spinner
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
        commandInput = findViewById(R.id.command)
        listenButton = findViewById(R.id.listen)
        importButton = findViewById(R.id.importModel)
        languageSpinner = findViewById(R.id.language)
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
        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) handler.post { if (!isDestroyed) updateTtsVoice() }
        }
        updateEngine()
        autoListen = intent.getBooleanExtra(EXTRA_AUTO_LISTEN, false)
        if (autoListen && savedInstanceState == null) handler.post { requestMicrophoneAndListen() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        autoListen = intent.getBooleanExtra(EXTRA_AUTO_LISTEN, false)
        if (autoListen) handler.post { requestMicrophoneAndListen() }
    }

    private fun updateTtsVoice() {
        offlineVoiceReady = false
        // TTS is optional: only local voices for the selected language are permitted.
        val voice = tts?.voices?.firstOrNull {
            it.locale.language == selectedLanguage.code && !it.isNetworkConnectionRequired
        }
        if (voice != null) offlineVoiceReady = tts?.setVoice(voice) == TextToSpeech.SUCCESS
    }

    private fun updateEngine() {
        val languageName = if (selectedLanguage == SpeechLanguage.PERSIAN) "فارسی" else "انگلیسی"
        engineStatus.text = when {
            ModelImporter.isInstalled(this, selectedLanguage) -> "● مدل Vosk $languageName نصب است • تشخیص گفتار آفلاین"
            Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this) ->
                "● تشخیص گفتار آفلاین سیستم (در صورت نصب بستهٔ $languageName)"
            else -> "○ برای فرمان صوتی $languageName، مدل آفلاین Vosk را وارد کنید"
        }
    }

    private fun requestMicrophoneAndListen() {
        if (loading || listening) return
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
        } else if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            startSystemRecognizer()
        } else {
            languageSpinner.isEnabled = true
            setStatus("ابتدا ZIP مدل Vosk زبان انتخابی را وارد کنید؛ برای تشخیص گفتار از اینترنت استفاده نمی‌شود.")
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
                        setStatus("تشخیص آفلاین گوشی در دسترس نیست یا بستهٔ زبان نصب نیست (خطا $error). مدل Vosk را وارد کنید.")
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
        val parsed = CommandParser.parse(input)
        if (parsed == null) { setStatus("فرمان را نشناختم. یکی از نمونه‌های پایین صفحه را امتحان کنید."); return }
        when (parsed) {
            is VoiceCommand.Search -> {
                val uri = Uri.Builder().scheme("https").authority("www.google.com")
                    .appendPath("search").appendQueryParameter("q", parsed.query).build()
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    feedback("جستجو در مرورگر باز شد", "Search opened in browser")
                } catch (_: Exception) { setStatus("مرورگری برای جستجو پیدا نشد.") }
            }
            is VoiceCommand.OpenApp -> openApp(parsed.name)
            else -> {
                if (VoiceAccessibilityService.active == null) {
                    setStatus("برای فرمان داخل برنامه‌ها، ابتدا سرویس دسترس‌پذیری آری‌ویس را فعال کنید.")
                    return
                }
                setStatus("در حال بازگشت به برنامهٔ قبلی…")
                moveTaskToBack(true)
                // Switching back from the assistant is asynchronous; retry only while another app is foreground.
                executeInPreviousApp(parsed, 0)
            }
        }
    }

    private fun executeInPreviousApp(command: VoiceCommand, attempt: Int) {
        handler.postDelayed({
            val service = VoiceAccessibilityService.active
            val done = service?.execute(command) == true
            if (!done && attempt < 4 && service != null) executeInPreviousApp(command, attempt + 1)
            else if (done) feedback("فرمان انجام شد")
            else setStatus("کنترل پیدا نشد. صفحهٔ برنامه باید باز باشد؛ برای لمس، نام دکمه باید دقیق و یکتا باشد.")
        }, if (attempt == 0) 600 else 350)
    }

    private fun openApp(name: String) {
        if (name in listOf("تنظیمات", "settings")) {
            startActivity(Intent(Settings.ACTION_SETTINGS)); feedback("تنظیمات باز شد", "Settings opened"); return
        }
        if (name in listOf("مرورگر", "browser")) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))); feedback("مرورگر باز شد", "Browser opened") }
            catch (_: Exception) { setStatus("مرورگری پیدا نشد.") }
            return
        }
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(query, 0)
        val wanted = CommandParser.normalize(name)
        val match = apps.firstOrNull { CommandParser.normalize(it.loadLabel(packageManager).toString()) == wanted }
            ?: apps.firstOrNull { CommandParser.normalize(it.loadLabel(packageManager).toString()).contains(wanted) }
        if (match == null) { setStatus("برنامهٔ «$name» پیدا نشد. نامی را که زیر آیکون برنامه است بگویید."); return }
        try {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).apply {
                component = ComponentName(match.activityInfo.packageName, match.activityInfo.name)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            feedback("${match.loadLabel(packageManager)} باز شد", "${match.loadLabel(packageManager)} opened")
        } catch (_: Exception) { setStatus("باز کردن این برنامه امکان‌پذیر نیست.") }
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
        if (requestCode != REQUEST_MODEL || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (loading || listening) return
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
        stopListening()
        // A model may still be loading on a worker; that worker closes it if this activity is destroyed.
        model?.close(); model = null
        tts?.stop(); tts?.shutdown()
        super.onDestroy()
    }
}

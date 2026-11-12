package ru.aleshka.voicedial

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private enum class State { NAME, CHOICE }

    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var onDevice = false

    private var state = State.NAME
    private var target = ContactMatcher.Target.PHONE
    private var candidates: List<ContactMatch> = emptyList()
    private var attempts = 0
    private var busy = false

    private lateinit var status: TextView
    private lateinit var listenButton: Button

    private val main = Handler(Looper.getMainLooper())
    private var utteranceCounter = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        tts = TextToSpeech(this, this)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        status = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 22f
            gravity = Gravity.CENTER
            contentDescription = "Голосовой звонок"
        }
        listenButton = Button(this).apply {
            text = "Слушаю"
            textSize = 24f
            contentDescription = "Начать слушать имя контакта"
            setOnClickListener {
                if (!busy) cueThenListen()
            }
            minimumHeight = 160
        }
        root.addView(status)
        root.addView(
            listenButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 48 }
        )
        setContentView(root)
    }

    override fun onInit(statusCode: Int) {
        if (statusCode == TextToSpeech.SUCCESS) {
            tts.language = Locale("ru", "RU")
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onError(utteranceId: String?) { finishSpeak(utteranceId) }
                override fun onDone(utteranceId: String?) { finishSpeak(utteranceId) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?, errorCode: Int) { finishSpeak(utteranceId) }
            })
            ttsReady = true
        }
        ensurePermissionsAndStart()
    }

    private val speakContinuations = HashMap<String, () -> Unit>()
    private val speakFallback = HashMap<String, Runnable>()

    private fun finishSpeak(utteranceId: String?) {
        if (utteranceId == null) return
        main.post {
            speakFallback.remove(utteranceId)?.let { main.removeCallbacks(it) }
            speakContinuations.remove(utteranceId)?.invoke()
        }
    }

    /** Озвучивает текст и после окончания речи выполняет действие. */
    private fun speak(text: String, after: (() -> Unit)? = null) {
        main.post {
            setStatus(text)
            if (!ttsReady) {
                after?.invoke()
                return@post
            }
            val id = "u${utteranceCounter++}"
            if (after != null) {
                speakContinuations[id] = after
                // страховка, если движок не пришлёт onDone
                val r = Runnable {
                    speakContinuations.remove(id)?.invoke()
                }
                speakFallback[id] = r
                main.postDelayed(r, 12_000)
            }
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        }
    }

    private fun setStatus(text: String) {
        status.text = text
        status.contentDescription = text
    }

    private fun ensurePermissionsAndStart() {
        val missing = mutableListOf<String>()
        for (p in listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p)
        }
        if (missing.isEmpty()) {
            startFlow()
        } else {
            requestPermissions(missing.toTypedArray(), 100)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (allGranted) {
            startFlow()
        } else {
            speak("Без разрешений приложение не сможет звонить. Разрешите доступ в настройках.")
        }
    }

    private fun startFlow() {
        if (recognizer == null) createRecognizer()
        cueThenListen()
    }

    /** Короткий сигнал вместо голосовой подсказки: слышно, что можно говорить. */
    private fun cueThenListen() {
        playCue()
        main.postDelayed({ startListening() }, 300)
    }

    private var tone: ToneGenerator? = null

    private fun playCue() {
        try {
            tone?.release()
            val t = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            tone = t
            t.startTone(ToneGenerator.TONE_PROP_ACK, 120)
            main.postDelayed({
                if (tone === t) {
                    t.release()
                    tone = null
                }
            }, 500)
        } catch (e: Exception) {
            // без сигнала слушаем сразу
        }
    }

    private fun createRecognizer() {
        onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        recognizer = if (onDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }
        recognizer?.setRecognitionListener(listener)
    }

    private fun startListening() {
        val r = recognizer ?: run { createRecognizer(); recognizer }
        if (r == null) {
            speak("Распознавание речи недоступно на этом телефоне.")
            return
        }
        state = if (candidates.isEmpty()) State.NAME else state
        busy = true
        setStatus("Слушаю…")
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 700L)
        }
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            busy = false
            speak("Не удалось запустить микрофон.")
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            busy = false
            handleError(error)
        }

        override fun onResults(results: Bundle?) {
            busy = false
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: emptyList()
            val best = list.firstOrNull()?.trim().orEmpty()
            if (best.isEmpty()) {
                retry("Не расслышал.")
                return
            }
            handlePhrase(best)
        }

        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun handleError(error: Int) {
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH -> retry("Не расслышал имя.")
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> retry("Не услышал. Попробуйте ещё раз.")
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> speak("Нет разрешения на микрофон.")
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                speak("Офлайн-модель распознавания не установлена. Скачайте русский язык для голосового ввода в настройках телефона.")
            else -> retry("Ошибка распознавания. Попробуйте ещё раз.")
        }
    }

    private fun retry(message: String) {
        attempts++
        if (attempts > 3) {
            speak("Попробуйте позже.")
            finish()
            return
        }
        speak(message) { startListening() }
    }

    private fun handlePhrase(phrase: String) {
        if (ContactMatcher.isCancel(phrase)) {
            speak("Отменяю.")
            finish()
            return
        }
        ContactMatcher.detectTarget(phrase)?.let { target = it }
        val cleaned = ContactMatcher.stripTarget(phrase)

        if (state == State.CHOICE && candidates.size > 1) {
            val choice = ContactMatcher.extractChoice(cleaned)
            if (choice != null && choice in 1..candidates.size) {
                dial(candidates[choice - 1].entry)
                return
            }
            // назвали имя вместо номера — переискиваем по всем контактам
            candidates = emptyList()
            state = State.NAME
        }

        val number = ContactMatcher.extractNumber(cleaned)
        val name = ContactMatcher.extractName(cleaned)

        if (number != null && name.count { it.isLetter() } < 2) {
            if (target == ContactMatcher.Target.TELEGRAM) {
                speak("Звоню по номеру в ${TelegramDialer.clientLabel(this)}.") {
                    placeTelegramCall(ContactEntry(0L, "номер", number, 0))
                }
            } else {
                speak("Звоню по номеру.") { placeCall(number, "номер") }
            }
            return
        }

        val query = name.ifEmpty { cleaned }

        if (target == ContactMatcher.Target.TELEGRAM) {
            if (!TelegramDialer.isInstalled(this)) {
                speak("Telegram не установлен на этом телефоне.")
                return
            }
            var matches = ContactMatcher.find(
                ContactMatcher.bestPerContact(TelegramDialer.loadCallableContacts(this)),
                query
            ).take(5)
            if (matches.isEmpty()) {
                // Telegram не синхронизировал записную книжку — пробуем через номер
                matches = ContactMatcher.find(
                    ContactMatcher.bestPerContact(ContactMatcher.loadContacts(this)),
                    query
                ).take(5)
            }
            handleMatches(matches)
            return
        }

        val contacts = ContactMatcher.bestPerContact(ContactMatcher.loadContacts(this))
        if (contacts.isEmpty()) {
            speak("В записной книжке нет контактов с номерами.")
            return
        }
        handleMatches(ContactMatcher.find(contacts, query).take(5))
    }

    private fun handleMatches(matches: List<ContactMatch>) {
        when {
            matches.isEmpty() -> retry("Не нашёл такого контакта.")
            matches.size == 1 -> dial(matches.first().entry)
            else -> {
                val close = matches.filter { matches.first().score - it.score < 0.07 }.take(3)
                if (close.size == 1) {
                    dial(close.first().entry)
                } else {
                    candidates = close
                    state = State.CHOICE
                    val listing = close.mapIndexed { i, m -> "${i + 1}. ${m.entry.name}" }.joinToString(", ")
                    speak("Нашёл несколько. $listing. Скажите номер.") { startListening() }
                }
            }
        }
    }

    private fun dial(entry: ContactEntry) {
        if (target == ContactMatcher.Target.TELEGRAM) {
            speak("Звоню ${entry.name} в ${TelegramDialer.clientLabel(this)}.") {
                placeTelegramCall(entry)
            }
        } else {
            speak("Звоню ${entry.name}.") {
                placeCall(entry.number, entry.name)
            }
        }
    }

    private fun placeTelegramCall(entry: ContactEntry) {
        val intent = TelegramDialer.callIntent(this, entry)
        if (intent == null) {
            speak("Не удалось позвонить в Телеграме. Проверьте, что контакт есть в Telegram.")
            return
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            speak("Не удалось открыть Telegram.")
        }
    }

    private fun placeCall(number: String, label: String) {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number))).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: SecurityException) {
            speak("Нет разрешения на звонки.")
        }
    }

    override fun onDestroy() {
        tone?.release()
        tone = null
        recognizer?.destroy()
        recognizer = null
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }
}

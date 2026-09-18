package com.jusika.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import java.time.Instant
import java.util.concurrent.Executors

class VoiceStandbyService : Service() {
    companion object {
        const val ACTION_STOP = "com.jusika.app.STOP_STANDBY"
        const val PREFS = "jusika-settings"
        const val ENDPOINT = "agent-endpoint"
        private const val CHANNEL = "voice-standby"
        private const val NOTIFICATION = 1
        private const val CONVERSATION_TIMEOUT_MS = 30_000L
    }
    private enum class Phase { STARTING, STANDBY, LISTENING, PROCESSING, SPEAKING, STOPPED }
    private val main = Handler(Looper.getMainLooper())
    private val requests = Executors.newSingleThreadExecutor()
    private var phase = Phase.STARTING
    private var started = false
    private var speech: OfflineSpeechLoop? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var micReady = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val conversation = VoiceSession(SystemClock::elapsedRealtime, Instant::now)
    private lateinit var client: AgentClient
    private var spokenId: String? = null
    private var spokenIds: Set<String> = emptySet()
    private var afterSpeech: (() -> Unit)? = null
    private val idle = Runnable {
        if (phase == Phase.LISTENING) {
            endConversation(
                if (conversation.waitingConfirmation) "승인 대기 시간이 지나 요청을 중단합니다."
                else "대화를 마치고 호출 대기로 돌아갑니다.",
            )
        }
    }
    private val speechTimeout = Runnable { failClosed("음성 출력이 멈춰 대기 기능을 종료합니다.") }
    private val startupTimeout = Runnable {
        if (phase == Phase.STARTING) failClosed("음성 인식 준비 시간이 초과되어 대기를 종료합니다.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("WakelockTimeout") // Held only for explicitly enabled foreground standby.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            !ModelInstaller.isReady(filesDir)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val endpoint = getSharedPreferences(PREFS, MODE_PRIVATE).getString(ENDPOINT, "") ?: ""
        try {
            client = AgentClient(AgentEndpoint.validate(endpoint, BuildConfig.DEBUG))
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        started = true
        createChannel()
        // Remove an earlier failure notice when the user explicitly tries again.
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(2)
        val notification = notification("음성 대기 준비 중")
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION, notification)
        }
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jusika:voice-standby")
            .also { it.acquire() }
        main.postDelayed(startupTimeout, 90_000)
        tts = TextToSpeech(this) { result -> main.post {
            if (phase == Phase.STOPPED) return@post
            val engine = tts
            if (result != TextToSpeech.SUCCESS || engine == null ||
                engine.setLanguage(Locale.KOREAN) < TextToSpeech.LANG_AVAILABLE) {
                failClosed("한국어 음성 출력을 사용할 수 없습니다. 휴대폰의 음성 엔진을 설정해주세요.")
            } else {
                val offlineVoice = engine.voices?.filter {
                    it.locale.language == "ko" && !it.isNetworkConnectionRequired &&
                        TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features
                }?.sortedBy { if (it.locale.country == "KR") 0 else 1 }?.firstOrNull()
                if (offlineVoice == null || engine.setVoice(offlineVoice) != TextToSpeech.SUCCESS) {
                    failClosed("오프라인 한국어 음성을 설치해주세요. 대기 기능을 종료합니다.")
                    return@post
                }
                engine.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit
                    override fun onDone(id: String?) { main.post { speechFinished(id) } }
                    @Deprecated("Legacy TTS callback")
                    override fun onError(id: String?) { main.post { speechFailed(id) } }
                    override fun onError(id: String?, code: Int) { main.post { speechFailed(id) } }
                })
                ttsReady = true
                beginWhenReady()
            }
        } }
        speech = OfflineSpeechLoop(ModelInstaller.modelDir(filesDir), main,
            onReady = { micReady = true; beginWhenReady() },
            onText = { handleText(it) },
            onFailure = { failClosed("마이크 또는 음성 인식기를 사용할 수 없어 대기를 종료합니다.") },
        ).also { it.start() }
        return START_NOT_STICKY // Never silently restart the mic after process death or reboot.
    }

    private fun beginWhenReady() {
        if (phase == Phase.STARTING && micReady && ttsReady) {
            main.removeCallbacks(startupTimeout)
            speak("호출 대기를 켰습니다. 주식아라고 부르면 응답합니다.") { standby() }
        }
    }
    private fun standby() {
        conversation.abandon() // Old previews/unknown requests can never resume on the next wake.
        phase = Phase.STANDBY
        update("호출 대기 중 · 주식아라고 불러주세요")
        speech?.enable(true)
    }
    private fun listen() {
        if (phase == Phase.STOPPED) return
        if (conversation.confirmationExpired) {
            endConversation("승인 유효 시간이 지나 요청을 중단합니다. 필요하면 새로 요청해주세요.")
            return
        }
        phase = Phase.LISTENING
        conversation.speechFinished()
        update(if (conversation.waitingConfirmation) "승인 대기 중 · 승인 또는 취소" else "명령 듣는 중")
        speech?.enable(true)
        main.removeCallbacks(idle)
        main.postDelayed(idle, CONVERSATION_TIMEOUT_MS)
    }
    private fun handleText(text: String) {
        when (phase) {
            Phase.STANDBY -> {
                val command = VoicePolicy.wakeCommand(text) ?: return
                // Other ambient transcripts are discarded. No HTTP or command interpretation.
                speech?.enable(false)
                conversation.activate()
                conversation.speechFinished() // Inline wake+command is a first turn, never consent.
                if (command.isEmpty()) speak("네, 말씀하세요.") { listen() }
                else handleCommand(command)
            }
            Phase.LISTENING -> {
                val command = VoicePolicy.wakeCommand(text) ?: text
                handleCommand(command)
            }
            else -> Unit
        }
    }
    private fun handleCommand(text: String) {
        main.removeCallbacks(idle)
        speech?.enable(false)
        when {
            VoicePolicy.disablesStandby(text) -> {
                endConversation("마이크와 호출 대기를 끕니다. 다시 사용하려면 앱에서 대기를 켜주세요.", disable = true)
            }
            VoicePolicy.endsConversation(text) -> {
                endConversation("대화를 마치고 호출 대기로 돌아갑니다.")
            }
            else -> {
                when (val decision = conversation.decide(text)) {
                    is VoiceDecision.Send -> send(decision.request)
                    is VoiceDecision.Prompt -> speak(decision.message) { listen() }
                    VoiceDecision.Expired -> endConversation("승인 유효 시간이 지나 요청을 중단합니다. 새로 요청해주세요.")
                    VoiceDecision.Ignore -> Unit
                }
            }
        }
    }
    private fun endConversation(message: String, disable: Boolean = false) {
        main.removeCallbacks(idle)
        speech?.enable(false)
        val then: () -> Unit = { if (disable) shutdown() else standby() }
        val request = conversation.endRequest()
        if (request != null) send(request, endMessage = message, afterEnd = then)
        else {
            conversation.abandon()
            speak(message, then)
        }
    }
    private fun send(request: VoiceRequest, endMessage: String? = null, afterEnd: (() -> Unit)? = null) {
        phase = Phase.PROCESSING
        update(if (request.confirmationPreviewId != null) "승인 또는 중단 처리 중" else "요청 처리 중 · MOCK 전용")
        requests.execute {
            val result = runCatching { client.send(request) }
            main.post {
                if (phase != Phase.PROCESSING || conversation.sessionId != request.sessionId) return@post
                val response = result.getOrNull()
                val received = response != null && runCatching { conversation.receive(request, response) }.getOrDefault(false)
                if (!received) {
                    conversation.failed(request)
                    // Never retry an approval or continue its old session when the result is unknown.
                    val warning = "통신 또는 응답 오류로 처리 결과를 확인하지 못했습니다. 같은 주문이나 승인을 반복하지 말고 주문 내역과 실행 상태를 확인해주세요. 자동으로 다시 보내지 않습니다."
                    speak(warning) { if (afterEnd != null) afterEnd() else standby() }
                    return@post
                }
                if (endMessage != null) {
                    conversation.abandon()
                    val message = if (response!!.status == AgentStatus.CANCELLED) endMessage
                    else "$endMessage 미승인 요청의 서버 중단 결과는 확인하지 못했습니다. 자동 실행하거나 다시 승인하지 않습니다."
                    speak(message) { afterEnd?.invoke() }
                } else if (response!!.status == AgentStatus.ERROR) {
                    conversation.abandon()
                    speak(response.message) { standby() }
                } else {
                    speak(response.message) { listen() }
                }
            }
        }
    }
    private fun speak(message: String, then: () -> Unit) {
        if (phase == Phase.STOPPED) return
        speech?.enable(false) // Never recognize the app's own response as a wake word / command.
        conversation.beforeSpeech()
        main.removeCallbacks(idle)
        phase = Phase.SPEAKING
        update("음성 안내 중")
        val chunks = SpeechChunks.split(message, TextToSpeech.getMaxSpeechInputLength())
        val ids = chunks.map { UUID.randomUUID().toString() }
        spokenIds = ids.toSet()
        spokenId = ids.last()
        afterSpeech = then
        main.removeCallbacks(speechTimeout)
        main.postDelayed(speechTimeout, (chunks.size * 90_000L).coerceAtMost(900_000L))
        for ((index, chunk) in chunks.withIndex()) {
            if (tts?.speak(chunk, if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, ids[index]) != TextToSpeech.SUCCESS) {
                failClosed("음성 출력을 사용할 수 없어 대기를 종료합니다.")
                return
            }
        }
    }
    private fun speechFinished(id: String?) {
        if (phase != Phase.SPEAKING || id != spokenId) return
        main.removeCallbacks(speechTimeout)
        spokenId = null
        spokenIds = emptySet()
        val action = afterSpeech
        afterSpeech = null
        // Discard speaker tail and queued microphone results before listening again.
        main.postDelayed({ if (phase == Phase.SPEAKING) action?.invoke() }, 500)
    }
    private fun speechFailed(id: String?) {
        if (id in spokenIds) failClosed("음성 출력 오류로 대기를 종료합니다.")
    }
    private fun failClosed(message: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(2, notification(message, ongoing = false))
        shutdown()
    }
    private fun shutdown() {
        phase = Phase.STOPPED
        conversation.abandon()
        main.removeCallbacksAndMessages(null)
        speech?.close()
        speech = null
        tts?.stop()
        if (::client.isInitialized) client.close()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        shutdown()
        tts?.shutdown()
        tts = null
        requests.shutdownNow()
        super.onDestroy()
    }
    private fun createChannel() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL, "주식아 음성 대기", NotificationManager.IMPORTANCE_LOW),
        )
    }
    private fun update(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION, notification(text))
    }
    private fun notification(text: String, ongoing: Boolean = phase != Phase.STOPPED): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, VoiceStandbyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_jusika)
            .setContentTitle("주식아")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .addAction(Notification.Action.Builder(null, "마이크 대기 끄기", stop).build())
            .build()
    }
}

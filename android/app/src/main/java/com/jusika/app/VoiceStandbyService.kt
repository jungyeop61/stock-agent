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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

/** One explicit voice session started by opening the app (side key, Bixby, or launcher). */
class VoiceStandbyService : Service() {
    companion object {
        const val ACTION_ACTIVATE = "com.jusika.app.ACTIVATE_VOICE"
        const val ACTION_STOP = "com.jusika.app.STOP_VOICE"
        const val PREFS = "jusika-settings"
        const val ENDPOINT = "agent-endpoint"
        private const val CHANNEL = "voice-session"
        private const val NOTIFICATION = 1
    }

    private enum class Phase { STARTING, LISTENING, TRANSCRIBING, PROCESSING, SPEAKING, STOPPED }

    private val main = Handler(Looper.getMainLooper())
    private val requests = Executors.newSingleThreadExecutor()
    private var phase = Phase.STARTING
    private var started = false
    private var speech: RemoteSpeechLoop? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var micReady = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val conversation = VoiceSession(SystemClock::elapsedRealtime, Instant::now)
    private lateinit var client: AgentClient
    private var spokenId: String? = null
    private var spokenIds: Set<String> = emptySet()
    private var afterSpeech: (() -> Unit)? = null
    private val speechTimeout = Runnable { failClosed("음성 출력이 멈춰 대화를 종료합니다.") }
    private val startupTimeout = Runnable {
        if (phase == Phase.STARTING) failClosed("음성 대화 준비 시간이 초과되었습니다.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_ACTIVATE || started) return START_NOT_STICKY
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        val endpoint = getSharedPreferences(PREFS, MODE_PRIVATE).getString(ENDPOINT, "") ?: ""
        try {
            val url = AgentEndpoint.validate(endpoint, BuildConfig.DEBUG)
            client = AgentClient(url, MobileCredentialStore(this).load(url))
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        started = true
        createChannel()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(2)
        val notification = notification("음성 대화 준비 중")
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else startForeground(NOTIFICATION, notification)
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jusika:voice-session")
            .also { it.acquire() }
        main.postDelayed(startupTimeout, 30_000)
        tts = TextToSpeech(this) { result -> main.post {
            if (phase == Phase.STOPPED) return@post
            val engine = tts
            if (result != TextToSpeech.SUCCESS || engine == null) {
                failClosed("음성 엔진 초기화에 실패했습니다. 휴대폰의 음성 엔진을 설정해주세요.")
            } else {
                val selection = OfflineKoreanTts.configure(engine)
                if (selection is KoreanTtsResult.Failed) {
                    failClosed("${selection.reason.message} (한국어 ${selection.koreanCount}개, 오프라인 설치 ${selection.usableCount}개)")
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
        speech = RemoteSpeechLoop(
            permissionCheck = { checkSelfPermission(Manifest.permission.RECORD_AUDIO) },
            main = main,
            onReady = { micReady = true; beginWhenReady() },
            onAudio = { transcribe(it) },
            onNoSpeech = { endConversation("말씀이 없어 대화를 종료합니다.") },
            onFailure = { failClosed("마이크를 사용할 수 없어 대화를 종료합니다.") },
        ).also { it.start() }
        return START_NOT_STICKY
    }

    private fun beginWhenReady() {
        if (phase != Phase.STARTING || !micReady || !ttsReady) return
        main.removeCallbacks(startupTimeout)
        conversation.activate()
        speak("네, 말씀하세요.") { listen() }
    }

    private fun listen() {
        if (phase == Phase.STOPPED) return
        if (conversation.confirmationExpired) {
            endConversation("승인 유효 시간이 지나 요청을 중단합니다.")
            return
        }
        phase = Phase.LISTENING
        conversation.speechFinished()
        update(if (conversation.waitingConfirmation) "승인 대기 중 · 승인 또는 취소" else "말씀을 듣고 있습니다")
        speech?.enable(true)
    }

    private fun transcribe(wav: ByteArray) {
        if (phase != Phase.LISTENING) return
        speech?.enable(false)
        phase = Phase.TRANSCRIBING
        update("음성을 인식하고 있습니다")
        requests.execute {
            val result = runCatching { client.transcribe(wav) }
            main.post {
                if (phase != Phase.TRANSCRIBING) return@post
                val text = result.getOrNull()
                if (text == null) {
                    conversation.abandon()
                    val rejected = result.exceptionOrNull() as? AgentAccessException
                    val warning = when (rejected?.statusCode) {
                        401 -> "접속 인증이 거절되었습니다. 보호자에게 접속 설정을 확인해달라고 해주세요."
                        403 -> "접속 정책에 의해 음성 요청이 차단되었습니다."
                        429 -> "요청이 너무 많습니다. 1분 뒤 다시 시도해주세요."
                        else -> "음성을 인식하지 못했습니다. 잠시 후 다시 실행해주세요."
                    }
                    speak(warning) { shutdown() }
                } else {
                    val command = VoicePolicy.wakeCommand(text) ?: text
                    handleCommand(command)
                }
            }
        }
    }

    private fun handleCommand(text: String) {
        speech?.enable(false)
        when {
            VoicePolicy.disablesStandby(text) || VoicePolicy.endsConversation(text) ->
                endConversation("대화를 종료합니다.")
            else -> when (val decision = conversation.decide(text)) {
                is VoiceDecision.Send -> send(decision.request)
                is VoiceDecision.Prompt -> speak(decision.message) { listen() }
                VoiceDecision.Expired -> endConversation("승인 유효 시간이 지나 요청을 중단합니다.")
                VoiceDecision.Ignore -> Unit
            }
        }
    }

    private fun endConversation(message: String) {
        speech?.enable(false)
        val request = conversation.endRequest()
        if (request != null) send(request, endMessage = message)
        else {
            conversation.abandon()
            speak(message) { shutdown() }
        }
    }

    private fun send(request: VoiceRequest, endMessage: String? = null) {
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
                    val rejected = result.exceptionOrNull() as? AgentAccessException
                    val warning = when (rejected?.statusCode) {
                        401 -> "접속 인증이 거절되어 요청을 처리하지 않았습니다. 보호자에게 접속 설정을 확인해달라고 해주세요."
                        403 -> "접속 정책에 의해 요청이 차단되었습니다."
                        429 -> "요청이 너무 많아 처리하지 않았습니다. 1분 뒤 다시 말씀해주세요."
                        else -> "통신 오류로 처리 결과를 확인하지 못했습니다. 같은 주문이나 승인을 반복하지 말고 주문 내역을 확인해주세요."
                    }
                    speak(warning) { shutdown() }
                    return@post
                }
                if (endMessage != null) {
                    conversation.abandon()
                    val message = if (response!!.status == AgentStatus.CANCELLED) endMessage
                    else "$endMessage 미승인 요청의 서버 중단 결과는 확인하지 못했습니다."
                    speak(message) { shutdown() }
                } else if (response!!.status == AgentStatus.ERROR) {
                    conversation.abandon()
                    speak(response.message) { shutdown() }
                } else speak(response.message) { listen() }
            }
        }
    }

    private fun speak(message: String, then: () -> Unit) {
        if (phase == Phase.STOPPED) return
        speech?.enable(false)
        conversation.beforeSpeech()
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
                failClosed("음성 출력을 사용할 수 없어 대화를 종료합니다.")
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
        main.postDelayed({ if (phase == Phase.SPEAKING) action?.invoke() }, 500)
    }

    private fun speechFailed(id: String?) {
        if (id in spokenIds) failClosed("음성 출력 오류로 대화를 종료합니다.")
    }

    private fun failClosed(message: String) {
        createChannel()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(2, notification(message, ongoing = false))
        shutdown()
    }

    private fun shutdown() {
        if (phase == Phase.STOPPED) return
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
            NotificationChannel(CHANNEL, "주식아 음성 대화", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun update(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION, notification(text))
    }

    private fun notification(text: String, ongoing: Boolean = phase != Phase.STOPPED): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, VoiceStandbyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_jusika)
            .setContentTitle("주식아")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(ongoing)
            .addAction(Notification.Action.Builder(null, "대화 종료", stop).build())
            .build()
    }
}

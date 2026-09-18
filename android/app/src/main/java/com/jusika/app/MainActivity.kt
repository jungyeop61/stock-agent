package com.jusika.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/** One-time caregiver setup. Voice interaction itself lives in the foreground service. */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val downloads = Executors.newSingleThreadExecutor()
    private lateinit var endpoint: EditText
    private lateinit var mobileToken: EditText
    private lateinit var status: TextView
    private lateinit var modelButton: Button
    private lateinit var startButton: Button
    private var downloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        fun text(value: String, size: Float = 18f) = TextView(this).apply {
            text = value; textSize = size; setPadding(0, 12, 0, 12)
        }.also { container.addView(it) }
        val heading = text("주식아", 32f)
        if (Build.VERSION.SDK_INT >= 28) heading.isAccessibilityHeading = true
        text("무료 기기 내 호출 대기 · 전체 에이전트 MOCK 연동")
        text("최초 설정 후 대기를 켜두면 ‘주식아’로 대화가 시작됩니다. 화면을 잠가도 대기를 유지하도록 구성되어 있습니다. 배터리와 마이크를 사용하며 대기 알림이 표시됩니다.")
        text("서버 주소")
        endpoint = EditText(this).apply {
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            contentDescription = "Python 에이전트 서버 주소"
            setText(getSharedPreferences(VoiceStandbyService.PREFS, MODE_PRIVATE)
                .getString(VoiceStandbyService.ENDPOINT, "http://127.0.0.1:8000"))
        }
        container.addView(endpoint)
        text("개인 접속 토큰 (OpenAI·증권사 키가 아닙니다)")
        mobileToken = EditText(this).apply {
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            contentDescription = "관리자가 발급한 개인 접속 토큰"
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            isSaveEnabled = false
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }.also { container.addView(it) }
        text("이미 저장한 토큰은 빈칸으로 두면 유지됩니다. 서버 주소를 바꾸면 새 토큰을 입력하세요. 로컬 USB 개발만 토큰 없이 가능합니다.")
        Button(this).apply {
            text = "저장한 접속 토큰 삭제 및 대기 끄기"
            setOnClickListener {
                val stopIntent = Intent(this@MainActivity, VoiceStandbyService::class.java)
                stopService(stopIntent)
                val removed = runCatching { MobileCredentialStore(this@MainActivity).save("", "") }
                mobileToken.text.clear()
                status.text = if (removed.isSuccess) "이 기기의 접속 토큰을 삭제했습니다. 서버 토큰 폐기는 관리자가 별도로 해야 합니다."
                    else "접속 토큰 삭제에 실패했습니다. 다시 시도해주세요."
            }
        }.also { container.addView(it) }
        text("개발 테스트는 USB 연결 후 adb reverse tcp:8000 tcp:8000을 사용하세요. 외부 서버는 인증과 HTTPS가 필요합니다.")
        status = text("설정 준비 중").apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        modelButton = Button(this).apply {
            text = getString(R.string.download_model)
            setOnClickListener { installModel() }
        }.also { container.addView(it) }
        startButton = Button(this).apply {
            text = "마이크 권한 허용하고 호출 대기 켜기"
            setOnClickListener { requestStandby() }
        }.also { container.addView(it) }
        Button(this).apply {
            text = "마이크와 호출 대기 완전히 끄기"
            setOnClickListener {
                val stopIntent = Intent(this@MainActivity, VoiceStandbyService::class.java)
                stopService(stopIntent)
                status.text = "호출 대기를 껐습니다. 주식아라고 불러도 반응하지 않습니다."
            }
        }.also { container.addView(it) }
        text("음성 사용법\n주식아 → 조회나 주문 명령 → 안내와 후속 질문에 답하기\n주문 내용을 끝까지 들은 뒤 ‘승인’ 또는 ‘취소’\n‘네/응’으로는 주문을 실행하지 않습니다.\n그만 → 미승인 대화 중단 후 호출 대기로 돌아가기\n대기 기능 꺼줘 → 마이크까지 끄기\n\n실제 거래는 연결하지 않습니다. 환전은 조회·환산만 지원합니다. 주문번호·조건주문번호·실행번호는 정확하게 지정해야 합니다.\n앱 강제 종료·휴대폰 재부팅 후에는 앱에서 대기를 다시 켜주세요. 대화 종료가 이미 접수된 주문을 취소하지는 않습니다.")
        setContentView(ScrollView(this).apply { addView(container) })
        refreshModel()
    }
    private fun refreshModel() {
        val ready = ModelInstaller.isReady(filesDir)
        modelButton.isEnabled = !ready && !downloading
        startButton.isEnabled = ready && !downloading
        status.text = if (ready) "모델 준비 완료. 호출 대기는 알림에서 확인할 수 있습니다."
        else "먼저 한국어 모델을 다운로드해주세요. Wi-Fi 사용을 권장합니다."
    }
    private fun installModel() {
        if (downloading) return
        downloading = true
        modelButton.isEnabled = false
        startButton.isEnabled = false
        status.text = "한국어 모델 다운로드 중입니다. 완료될 때까지 앱을 열어두세요."
        downloads.execute {
            val result = runCatching { ModelInstaller.download(filesDir) }
            main.post {
                if (isDestroyed) return@post
                downloading = false
                refreshModel()
                if (result.isFailure) status.text = "모델 설치에 실패했습니다. 네트워크와 저장 공간을 확인하고 다시 시도해주세요."
            }
        }
    }
    private fun requestStandby() {
        if (!ModelInstaller.isReady(filesDir)) return
        val url = try {
            AgentEndpoint.validate(endpoint.text.toString(), BuildConfig.DEBUG)
        } catch (_: Exception) {
            status.text = getString(R.string.invalid_endpoint)
            return
        }
        try {
            val credentials = MobileCredentialStore(this)
            val typed = mobileToken.text.toString().trim()
            val token = if (typed.isNotEmpty()) typed else credentials.load(url)
            require(token.isNotEmpty() || java.net.URI(url).host in setOf("127.0.0.1", "localhost", "10.0.2.2"))
            credentials.save(token, url)
            mobileToken.text.clear()
        } catch (_: Exception) {
            status.text = getString(R.string.invalid_mobile_token)
            return
        }
        // Restart explicitly so an old connection cannot retain the previous credential/server.
        val stopIntent = Intent(this, VoiceStandbyService::class.java)
        stopService(stopIntent)
        getSharedPreferences(VoiceStandbyService.PREFS, MODE_PRIVATE).edit()
            .putString(VoiceStandbyService.ENDPOINT, url).apply()
        val permissions = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.isNotEmpty()) requestPermissions(permissions.toTypedArray(), 1)
        else startStandby()
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 1) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startStandby()
            } else {
                status.text = "호출어 감지에는 마이크 권한이 필요합니다. 설정에서 권한을 허용해주세요."
            }
        }
    }
    private fun startStandby() {
        try {
            startForegroundService(Intent(this, VoiceStandbyService::class.java))
            status.text = "호출 대기를 요청했습니다. 음성 안내와 대기 알림을 확인한 뒤 ‘주식아’라고 불러주세요."
        } catch (_: Exception) {
            status.text = "호출 대기를 시작하지 못했습니다. 앱을 화면에 연 상태에서 다시 시도해주세요."
        }
    }
    override fun onDestroy() {
        downloads.shutdownNow()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

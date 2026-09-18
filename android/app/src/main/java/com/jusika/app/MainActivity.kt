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
    private lateinit var status: TextView
    private lateinit var modelButton: Button
    private lateinit var startButton: Button
    private var downloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        fun text(value: String, size: Float = 18f) = TextView(this).apply {
            text = value; textSize = size; setPadding(0, 12, 0, 12)
        }.also { container.addView(it) }
        val heading = text("주식아", 32f)
        if (Build.VERSION.SDK_INT >= 28) heading.isAccessibilityHeading = true
        text("무료 기기 내 호출 대기 · 현재가 조회 개발 버전")
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
        text("개발 테스트는 USB 연결 후 adb reverse tcp:8000 tcp:8000을 사용하세요. 인터넷에 인증 없는 서버를 공개하지 마세요.")
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
        text("음성 사용법\n주식아 → 네, 말씀하세요 → 삼성전자 현재가 알려줘\n그만 → 호출 대기로 돌아가기\n대기 기능 꺼줘 → 마이크까지 끄기\n\n앱 강제 종료·휴대폰 재부팅 후에는 앱에서 대기를 다시 켜주세요. 이번 버전에서는 주문·승인은 처리하지 않습니다.")
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

package com.jusika.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/** Caregiver setup plus explicit voice launch entrypoint for the Samsung side key and Bixby. */
class MainActivity : ComponentActivity() {
    private lateinit var endpoint: EditText
    private lateinit var mobileToken: EditText
    private lateinit var status: TextView
    private var startAfterPermission = false

    private val voicePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (startAfterPermission &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startAfterPermission = false
            startVoiceSession()
        } else {
            startAfterPermission = false
            status.text = "음성 대화에는 마이크 권한이 필요합니다. 설정에서 권한을 허용해주세요."
        }
    }

    private val scanner = registerForActivityResult(ScanContract()) { result ->
        val raw = result.contents
        if (raw == null) {
            status.text = "QR 등록을 취소했습니다. 기존 설정은 유지됩니다."
        } else {
            val setup = runCatching { MobileSetup.parse(raw) }.getOrNull()
            if (setup == null) {
                status.text = "주식아 등록 QR이 아니거나 안전하지 않은 주소입니다."
            } else {
                val dialog = AlertDialog.Builder(this)
                    .setTitle("접속 설정 등록")
                    .setMessage("서버: ${setup.endpoint}\n본인이 만든 서버 주소인지 확인하세요. 토큰은 암호화해 저장합니다.")
                    .setNegativeButton("취소", null)
                    .setPositiveButton("등록") { _, _ ->
                        stopVoiceService()
                        val saved = runCatching {
                            MobileCredentialStore(this).save(setup.token, setup.endpoint)
                            check(getSharedPreferences(VoiceStandbyService.PREFS, MODE_PRIVATE).edit()
                                .putString(VoiceStandbyService.ENDPOINT, setup.endpoint).commit())
                        }
                        mobileToken.text.clear()
                        if (saved.isSuccess) {
                            endpoint.setText(setup.endpoint)
                            status.text = "등록 완료. 지금 음성 대화 시작을 누르거나 앱을 다시 열어주세요."
                        } else status.text = "등록하지 못했습니다. 다시 시도해주세요."
                    }.create()
                dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                dialog.show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        fun text(value: String, size: Float = 18f) = TextView(this).apply {
            text = value
            textSize = size
            setPadding(0, 12, 0, 12)
        }.also { container.addView(it) }
        val heading = text("주식아", 32f)
        if (Build.VERSION.SDK_INT >= 28) heading.isAccessibilityHeading = true
        text("측면 전원 버튼 두 번 또는 ‘하이 빅스비, 주식아 열어줘’로 실행하면 바로 음성 대화를 시작합니다.")
        text("평소에는 마이크를 사용하지 않습니다. 앱을 연 뒤 말한 한 문장만 암호화된 서버를 통해 OpenAI 음성 인식으로 처리합니다.")
        text("서버 주소")
        endpoint = EditText(this).apply {
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            contentDescription = "Python 에이전트 서버 주소"
            setText(getSharedPreferences(VoiceStandbyService.PREFS, MODE_PRIVATE)
                .getString(VoiceStandbyService.ENDPOINT, "http://127.0.0.1:8000"))
        }.also { container.addView(it) }
        text("개인 접속 토큰 (OpenAI·증권사 키가 아닙니다)")
        mobileToken = EditText(this).apply {
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            contentDescription = "관리자가 발급한 개인 접속 토큰"
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            isSaveEnabled = false
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }.also { container.addView(it) }
        text("이미 등록한 토큰은 빈칸으로 두면 유지됩니다. 새 휴대폰에서는 QR 등록을 이용하세요.")
        Button(this).apply {
            text = "QR로 서버 주소와 토큰 한 번에 등록"
            setOnClickListener {
                scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setCaptureActivity(SecureCaptureActivity::class.java)
                    .setPrompt("서버에서 만든 주식아 등록 QR을 스캔하세요")
                    .setBeepEnabled(false).setBarcodeImageEnabled(false).setOrientationLocked(false))
            }
        }.also { container.addView(it) }
        Button(this).apply {
            text = "저장한 접속 토큰 삭제"
            setOnClickListener {
                stopVoiceService()
                val removed = runCatching { MobileCredentialStore(this@MainActivity).save("", "") }
                mobileToken.text.clear()
                status.text = if (removed.isSuccess) "이 기기의 접속 토큰을 삭제했습니다."
                else "접속 토큰 삭제에 실패했습니다."
            }
        }.also { container.addView(it) }
        status = text("설정 준비 완료").apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        Button(this).apply {
            text = "지금 음성 대화 시작"
            setOnClickListener { requestVoiceSession() }
        }.also { container.addView(it) }
        Button(this).apply {
            text = "현재 음성 대화 종료"
            setOnClickListener {
                stopVoiceService()
                status.text = "음성 대화를 종료했습니다. 마이크를 사용하지 않습니다."
            }
        }.also { container.addView(it) }
        text("사용법\n측면 전원 버튼 두 번 → ‘네, 말씀하세요’ → 명령\n또는 ‘하이 빅스비, 주식아 열어줘’ → 명령\n주문 내용을 끝까지 들은 뒤 ‘승인’ 또는 ‘취소’\n‘네/응’으로는 주문을 실행하지 않습니다.\n‘그만’이라고 말하면 대화를 종료합니다.\n\n실제 거래는 아직 연결하지 않았으며 MOCK 안전 모드입니다.")
        setContentView(ScrollView(this).apply { addView(container) })
        if (savedInstanceState == null && isLauncherIntent(intent)) requestVoiceSession(automatic = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isLauncherIntent(intent)) requestVoiceSession(automatic = true)
    }

    private fun requestVoiceSession(automatic: Boolean = false) {
        if (automatic && !getSharedPreferences(VoiceStandbyService.PREFS, MODE_PRIVATE)
                .contains(VoiceStandbyService.ENDPOINT)) {
            status.text = "먼저 QR로 서버 접속 설정을 등록해주세요."
            return
        }
        val url = saveAndValidateSetup(automatic) ?: return
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
        if (permissions.isEmpty()) startVoiceSession()
        else {
            startAfterPermission = true
            voicePermissions.launch(permissions.toTypedArray())
        }
    }

    private fun saveAndValidateSetup(automatic: Boolean): String? {
        val url = try {
            AgentEndpoint.validate(endpoint.text.toString(), BuildConfig.DEBUG)
        } catch (_: Exception) {
            if (!automatic) status.text = getString(R.string.invalid_endpoint)
            return null
        }
        return try {
            val credentials = MobileCredentialStore(this)
            val typed = mobileToken.text.toString().trim()
            val token = if (typed.isNotEmpty()) typed else credentials.load(url)
            require(token.isNotEmpty() || java.net.URI(url).host in setOf("127.0.0.1", "localhost", "10.0.2.2"))
            credentials.save(token, url)
            mobileToken.text.clear()
            url
        } catch (_: Exception) {
            if (!automatic) status.text = getString(R.string.invalid_mobile_token)
            else status.text = "먼저 QR로 서버 접속 설정을 등록해주세요."
            null
        }
    }

    private fun startVoiceSession() {
        try {
            startForegroundService(
                Intent(this, VoiceStandbyService::class.java).setAction(VoiceStandbyService.ACTION_ACTIVATE),
            )
            status.text = "음성 대화를 시작했습니다. ‘네, 말씀하세요’ 안내 뒤에 말해주세요."
        } catch (_: Exception) {
            status.text = "음성 대화를 시작하지 못했습니다. 앱을 화면에 연 상태에서 다시 시도해주세요."
        }
    }

    private fun stopVoiceService() {
        stopService(Intent(this, VoiceStandbyService::class.java))
    }

    private fun isLauncherIntent(value: Intent?): Boolean =
        value?.action == Intent.ACTION_MAIN && value.hasCategory(Intent.CATEGORY_LAUNCHER)

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.clear()
    }
}

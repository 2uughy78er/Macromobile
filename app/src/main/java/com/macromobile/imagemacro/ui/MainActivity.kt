package com.macromobile.imagemacro.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.macromobile.imagemacro.service.ProjectionRequestActivity
import com.macromobile.imagemacro.ui.theme.ImageMacroTheme

/**
 * 앱의 유일한 Activity.
 *
 * 매크로 실행 자체는 Foreground Service 가 담당하므로, 이 화면이 닫혀도 매크로는 계속 돈다.
 */
class MainActivity : ComponentActivity() {

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 거부해도 실행은 가능하나 알림이 보이지 않을 수 있다. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()

        setContent {
            ImageMacroTheme {
                Surface(
                    modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppNavigation()
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** 화면 캡처 권한을 요청한다(허용되면 서비스가 뜨고 캡처가 시작된다). */
    fun requestScreenCapture() {
        ProjectionRequestActivity.request(this)
    }

    /**
     * 앱을 잠깐 내려서 뒤에 있는 화면을 캡처할 수 있게 한다.
     *
     * 우리 앱이 화면을 덮고 있으면 등록하려는 버튼이 보이지 않기 때문이다.
     */
    fun minimizeApp() {
        moveTaskToBack(true)
    }

    /** 캡처가 끝난 뒤 앱을 다시 앞으로 가져온다. */
    fun bringToFront() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
    }
}

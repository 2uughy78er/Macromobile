package com.macromobile.inputmirror.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.macromobile.inputmirror.ui.theme.InputMirrorTheme

/**
 * 앱의 유일한 Activity.
 *
 * 검증 MVP 이므로 화면은 세 개뿐이다. 메인(상태·시작), 테스트(4분할), 로그.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            InputMirrorTheme {
                Surface(
                    modifier = Modifier,
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppNavigation()
                }
            }
        }
    }
}

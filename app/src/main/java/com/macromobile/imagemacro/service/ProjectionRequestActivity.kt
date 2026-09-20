package com.macromobile.imagemacro.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * MediaProjection(화면 캡처) 권한을 요청하는 투명 액티비티.
 *
 * 권한 다이얼로그는 Activity 에서만 띄울 수 있어서, 오버레이나 서비스에서도
 * 캡처를 다시 시작할 수 있도록 별도 액티비티로 분리했다.
 *
 * 사용자가 허용하면 결과를 [MacroForegroundService] 로 넘겨 캡처를 시작한다.
 * 거부하면 아무 것도 하지 않고 이유를 알린다.
 */
class ProjectionRequestActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            MacroForegroundService.startCapture(this, result.resultCode, result.data!!)
        } else {
            Toast.makeText(
                this,
                "화면 캡처 권한이 필요합니다. 허용해야 매크로가 화면을 볼 수 있습니다.",
                Toast.LENGTH_LONG,
            ).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            Toast.makeText(this, "이 기기에서는 화면 캡처를 사용할 수 없습니다.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        runCatching { launcher.launch(manager.createScreenCaptureIntent()) }
            .onFailure {
                Toast.makeText(this, "화면 캡처 권한 화면을 열지 못했습니다.", Toast.LENGTH_LONG).show()
                finish()
            }
    }

    companion object {
        /** 어디서든(오버레이 포함) 화면 캡처 권한을 다시 요청한다. */
        fun request(context: Context) {
            val intent = Intent(context, ProjectionRequestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}

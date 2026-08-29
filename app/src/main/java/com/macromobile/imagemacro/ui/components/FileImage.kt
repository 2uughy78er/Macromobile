package com.macromobile.imagemacro.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 저장된 템플릿 PNG 를 읽어 화면에 보여준다.
 *
 * 목록에서 여러 장을 동시에 그리므로 디코딩은 IO 스레드에서 하고, 미리보기 크기에 맞춰
 * 축소해서 읽는다(메모리 절약).
 */
@Composable
fun rememberFileBitmap(file: File, maxSize: Int = 512): State<Bitmap?> =
    produceState<Bitmap?>(initialValue = null, file.absolutePath, file.lastModified(), maxSize) {
        value = withContext(Dispatchers.IO) { decodeScaled(file, maxSize) }
    }

private fun decodeScaled(file: File, maxSize: Int): Bitmap? {
    if (!file.exists()) return null
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeFile(file.absolutePath, opts)
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}

/** 파일 이미지를 그리되, 없으면 안내 문구를 보여준다. */
@Composable
fun FileImage(
    file: File,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val bitmap by rememberFileBitmap(file)
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier.matchParentSize(),
                contentScale = contentScale,
            )
        } else {
            Text("이미지 없음", style = MaterialTheme.typography.labelSmall)
        }
    }
}

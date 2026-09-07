package com.macromobile.imagemacro.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 화면 위에서 사용자가 드래그해 고른 사각형(0~1 비율). */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isUsable: Boolean get() = width > 0.005f && height > 0.005f

    /** 실제 픽셀 좌표로 바꾼다. */
    fun toPixels(imageWidth: Int, imageHeight: Int): IntRectPx {
        val l = (left * imageWidth).toInt().coerceIn(0, max(0, imageWidth - 1))
        val t = (top * imageHeight).toInt().coerceIn(0, max(0, imageHeight - 1))
        val r = (right * imageWidth).toInt().coerceIn(l + 1, imageWidth)
        val b = (bottom * imageHeight).toInt().coerceIn(t + 1, imageHeight)
        return IntRectPx(l, t, r - l, b - t)
    }

    companion object {
        fun of(a: Offset, b: Offset, w: Float, h: Float): NormalizedRect {
            if (w <= 0f || h <= 0f) return NormalizedRect(0f, 0f, 0f, 0f)
            val l = (min(a.x, b.x) / w).coerceIn(0f, 1f)
            val t = (min(a.y, b.y) / h).coerceIn(0f, 1f)
            val r = (max(a.x, b.x) / w).coerceIn(0f, 1f)
            val bo = (max(a.y, b.y) / h).coerceIn(0f, 1f)
            return NormalizedRect(l, t, r, bo)
        }
    }
}

data class IntRectPx(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * 이미지 위에 겹쳐서 영역을 드래그로 고르는 레이어.
 *
 * 사용자가 "여기가 그 버튼이다"라고 직접 지정하는 데 쓴다.
 * 이 컴포넌트는 좌표만 다루고 어떤 이미지 내용도 알지 못한다.
 */
@Composable
fun CropSelector(
    selection: NormalizedRect?,
    onSelectionChange: (NormalizedRect) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFF4C9AFF),
) {
    BoxWithConstraints(modifier) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        var dragStart by remember { mutableStateOf(Offset.Zero) }
        var dragCurrent by remember { mutableStateOf(Offset.Zero) }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(widthPx, heightPx) {
                    detectDragGestures(
                        onDragStart = { start ->
                            dragStart = start
                            dragCurrent = start
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            dragCurrent = change.position
                            onSelectionChange(
                                NormalizedRect.of(dragStart, dragCurrent, widthPx, heightPx),
                            )
                        },
                        onDragEnd = {
                            val rect = NormalizedRect.of(dragStart, dragCurrent, widthPx, heightPx)
                            if (rect.isUsable) onSelectionChange(rect)
                        },
                    )
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val sel = selection ?: return@Canvas
                if (!sel.isUsable) return@Canvas
                val rect = Rect(
                    offset = Offset(sel.left * size.width, sel.top * size.height),
                    size = Size(sel.width * size.width, sel.height * size.height),
                )
                // 선택 영역 밖을 어둡게 해서 무엇을 고르는지 분명히 보이게 한다.
                drawRect(Color.Black.copy(alpha = 0.45f), size = Size(size.width, rect.top))
                drawRect(
                    Color.Black.copy(alpha = 0.45f),
                    topLeft = Offset(0f, rect.bottom),
                    size = Size(size.width, size.height - rect.bottom),
                )
                drawRect(
                    Color.Black.copy(alpha = 0.45f),
                    topLeft = Offset(0f, rect.top),
                    size = Size(rect.left, rect.height),
                )
                drawRect(
                    Color.Black.copy(alpha = 0.45f),
                    topLeft = Offset(rect.right, rect.top),
                    size = Size(size.width - rect.right, rect.height),
                )
                drawRect(
                    color = accent,
                    topLeft = rect.topLeft,
                    size = rect.size,
                    style = Stroke(width = 3.dp.toPx()),
                )
                // 모서리 손잡이 표시
                val handle = 10.dp.toPx()
                listOf(
                    rect.topLeft,
                    Offset(rect.right, rect.top),
                    Offset(rect.left, rect.bottom),
                    Offset(rect.right, rect.bottom),
                ).forEach { corner ->
                    drawCircle(accent, radius = handle / 2, center = corner)
                }
            }
        }
    }
}

/**
 * 이미지 위에서 좌표 하나를 고르는 레이어(십자선).
 *
 * "이미지 없이 이 위치를 눌러라" 단계를 만들 때 쓴다.
 */
@Composable
fun CrosshairPicker(
    point: Offset?,
    onPointChange: (Offset) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFFFF8A3D),
) {
    BoxWithConstraints(modifier) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(widthPx, heightPx) {
                    detectDragGestures(
                        onDragStart = { pos -> emit(pos, widthPx, heightPx, onPointChange) },
                        onDrag = { change, _ ->
                            change.consume()
                            emit(change.position, widthPx, heightPx, onPointChange)
                        },
                    )
                }
                .pointerInput(widthPx, heightPx) {
                    detectTapGestures { pos ->
                        emit(pos, widthPx, heightPx, onPointChange)
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val p = point ?: return@Canvas
                val x = p.x * size.width
                val y = p.y * size.height
                drawLine(accent, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx())
                drawLine(accent, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
                drawCircle(accent, radius = 8.dp.toPx(), center = Offset(x, y), style = Stroke(3.dp.toPx()))
            }
        }
    }
}

private fun emit(pos: Offset, w: Float, h: Float, onPointChange: (Offset) -> Unit) {
    if (w <= 0f || h <= 0f) return
    onPointChange(Offset((pos.x / w).coerceIn(0f, 1f), (pos.y / h).coerceIn(0f, 1f)))
}

/** 두 점이 거의 같은 위치인지. */
internal fun Offset.nearlyEquals(other: Offset, tolerance: Float = 0.001f): Boolean =
    abs(x - other.x) < tolerance && abs(y - other.y) < tolerance

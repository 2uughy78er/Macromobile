package com.macromobile.inputmirror.input

import kotlin.math.hypot

/**
 * 제스처 상태 머신의 상태.
 *
 * ```
 * IDLE
 *  ↓ ACTION_DOWN (항상 새 세션)
 * PENDING
 *  ├── ACTION_UP + 이동거리 < 임계값 → TAP 확정 → IDLE
 *  └── 이동거리 ≥ 임계값 → DRAG (되돌아가지 않음)
 *                          ↓ ACTION_UP → DRAG 확정 → IDLE
 * ```
 */
enum class GestureState { IDLE, PENDING, DRAG }

/** 끝난 제스처의 확정된 종류. */
enum class GestureType {
    TAP, DRAG;

    val label: String get() = name
}

/**
 * 터치 한 번.
 *
 * **ACTION_DOWN 마다 새로 만들어진다.** 이전 제스처의 세션은 절대 재사용하지 않는다.
 * 몇 번째 터치인지, 직전이 무엇이었는지는 이 클래스 어디에도 들어오지 않는다.
 * 판단 재료는 오직 **DOWN 이후 손가락이 얼마나 움직였는가** 하나다.
 *
 * 임계값은 세션을 만들 때 값으로 고정한다. 제스처 도중에 설정을 바꿔도 진행 중인
 * 제스처의 판정 기준은 흔들리지 않는다.
 */
class GestureSession internal constructor(
    /** 이 제스처만의 고유 번호. 로그가 한 제스처를 한 덩어리로 보여주기 위한 것. */
    val id: Long,
    /** 손가락을 댄 지점. TAP 좌표 정책의 기준점이기도 하다. */
    val down: TouchPoint,
    /** 이 제스처에 적용되는 DRAG 임계값(픽셀). */
    val dragThresholdPx: Float,
) {
    private val _points = ArrayList<TouchPoint>(64).apply { add(down) }

    /** DOWN 부터 UP 까지의 전체 경로. DRAG 는 이 경로 전체를 하나의 제스처로 쓴다. */
    val points: List<TouchPoint> get() = _points

    var state: GestureState = GestureState.PENDING
        private set

    /** 시작점에서 벗어난 최대 거리. */
    var maxDistance: Float = 0f
        private set

    /** PENDING → DRAG 로 넘어간 점의 인덱스. 아직 넘지 않았으면 -1. */
    var dragCrossedIndex: Int = -1
        private set

    /** UP 이 와서 확정된 종류. 진행 중이면 null. */
    var finishedType: GestureType? = null
        private set

    val isFinished: Boolean get() = finishedType != null

    /** 눌린 시간(ms). TAP 제스처의 길이로 쓴다. */
    val durationMs: Long get() = (_points.last().timestamp - down.timestamp).coerceAtLeast(0L)

    fun distanceFrom(point: TouchPoint): Float = hypot(point.x - down.x, point.y - down.y)

    /**
     * 이동 한 점을 받아 상태를 갱신한다.
     *
     * **DRAG 로 한 번 넘어가면 되돌아가지 않는다.** 손가락이 시작점 근처로 돌아와도
     * 이미 끈 것은 끈 것이다.
     */
    internal fun addPoint(point: TouchPoint): Float {
        _points += point
        val distance = distanceFrom(point)
        if (distance > maxDistance) maxDistance = distance
        if (state == GestureState.PENDING && distance >= dragThresholdPx) {
            state = GestureState.DRAG
            dragCrossedIndex = _points.size - 1
        }
        return distance
    }

    /**
     * UP 을 받아 종류를 확정한다.
     *
     * UP 지점도 경로의 일부이고 이동거리 계산에 포함된다. MOVE 이벤트가 합쳐져 전달되면
     * 마지막 이동이 UP 에만 나타날 수 있는데, 그것을 TAP 으로 잘못 세면 "움직인 만큼으로
     * 판단한다"는 원칙이 깨지기 때문이다.
     */
    internal fun finish(up: TouchPoint): GestureType {
        addPoint(up)
        val type = if (state == GestureState.DRAG) GestureType.DRAG else GestureType.TAP
        finishedType = type
        return type
    }

    /**
     * TAP 의 대표 좌표.
     *
     * **정책: 시작점(DOWN)을 쓴다.** 손가락을 뗄 때는 최대 (임계값 - 1)px 까지 밀려 있을 수
     * 있는데, 사용자가 누르려던 곳은 처음 닿은 자리다. 이 정책은 코드 한 곳에만 있고
     * 모든 경로가 이것을 쓴다.
     */
    fun tapPoint(): TouchPoint = down

    /** DRAG 의 전체 경로. DOWN 부터 UP 까지 전부. */
    fun dragPath(): List<TouchPoint> = points
}

/** MOVE 한 건을 처리한 결과. */
data class MoveUpdate(
    val session: GestureSession,
    val point: TouchPoint,
    val distance: Float,
    val stateBefore: GestureState,
    val stateAfter: GestureState,
) {
    /** 이번 MOVE 에서 PENDING → DRAG 로 넘어갔는가. */
    val crossedThreshold: Boolean
        get() = stateBefore == GestureState.PENDING && stateAfter == GestureState.DRAG
}

/** UP 까지 끝난 제스처. */
data class GestureOutcome(
    val session: GestureSession,
    val type: GestureType,
)

/**
 * TAP / DRAG 판정기.
 *
 * 안드로이드 API 를 쓰지 않는 순수 계산이라 단위 테스트로 규칙을 못박을 수 있다.
 * TAP→TAP→DRAG→TAP 같은 어떤 순서든 각 제스처가 독립적으로 판정되는지를
 * 기기 없이 확인하기 위해서다.
 *
 * 이 클래스에는 터치 횟수도, 직전 제스처의 종류도, 번갈아 바꾸는 스위치도 없다.
 * 그런 것을 다시 들여놓으면 이 버그가 그대로 돌아온다.
 */
class GestureRecognizer(
    /** DRAG 로 인정하는 최소 이동거리(픽셀). 밀도에 맞춰 dp 에서 환산해 넣는다. */
    @Volatile var dragThresholdPx: Float = DEFAULT_THRESHOLD_PX,
) {
    private var current: GestureSession? = null
    private var nextId = 0L

    /** 진행 중인 제스처. 없으면 null. */
    val activeSession: GestureSession? get() = current

    val state: GestureState get() = current?.state ?: GestureState.IDLE

    /**
     * 손가락이 닿았다. **언제나 새 세션을 만든다.**
     *
     * 이전 세션이 남아 있었다면(UP 을 놓친 경우 등) 물려받지 않고 버린다.
     */
    @Synchronized
    fun onDown(point: TouchPoint): GestureSession {
        current = null
        val session = GestureSession(++nextId, point, dragThresholdPx)
        current = session
        return session
    }

    /** 손가락이 움직였다. 진행 중인 제스처가 없으면 무시한다(상태를 지어내지 않는다). */
    @Synchronized
    fun onMove(point: TouchPoint): MoveUpdate? {
        val session = current ?: return null
        val before = session.state
        val distance = session.addPoint(point)
        return MoveUpdate(session, point, distance, before, session.state)
    }

    /** 손가락을 뗐다. 종류를 확정하고 세션을 닫는다. */
    @Synchronized
    fun onUp(point: TouchPoint): GestureOutcome? {
        val session = current ?: return null
        val type = session.finish(point)
        current = null
        return GestureOutcome(session, type)
    }

    /** 시스템이 터치를 취소했다. 아무것도 확정하지 않고 버린다. */
    @Synchronized
    fun onCancel(): GestureSession? {
        val session = current
        current = null
        return session
    }

    /** 세션 번호까지 처음으로 돌린다. 새 테스트를 시작할 때만 쓴다. */
    @Synchronized
    fun reset() {
        current = null
        nextId = 0L
    }

    companion object {
        /** 요청 범위(10~20dp) 안의 기본값. dp 이므로 화면 밀도에 따라 픽셀은 달라진다. */
        const val DEFAULT_THRESHOLD_DP = 12f

        /** 밀도를 모를 때의 보수적 기본값. 실제로는 항상 dp × density 로 채운다. */
        const val DEFAULT_THRESHOLD_PX = 24f

        /** dp → px. 화면 밀도를 반드시 거친다. 픽셀 상수를 직접 쓰지 않는다. */
        fun thresholdPx(dp: Float, density: Float): Float = dp * density
    }
}

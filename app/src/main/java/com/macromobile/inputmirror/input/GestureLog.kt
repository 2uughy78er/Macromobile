package com.macromobile.inputmirror.input

/**
 * 제스처 로그.
 *
 * 한 제스처가 한 덩어리로 읽혀야 한다. gestureId 로 묶고, DOWN / MOVE / UP 을 순서대로
 * 남기고, 마지막에 FINAL_TYPE 을 적는다. 이동거리와 그때의 상태를 매 줄에 함께 적어
 * "왜 이렇게 판정됐는지"를 로그만 보고 알 수 있게 한다.
 *
 * ```
 * gestureId=101
 * DOWN x=500 y=500
 * MOVE x=505 y=502 distance=5.38 state=PENDING
 * MOVE x=515 y=510 distance=17.69 state=DRAG
 * UP x=600 y=580 distance=127.28
 * FINAL_TYPE=DRAG
 * ```
 */
object GestureLog {

    fun down(session: GestureSession): String = buildString {
        appendLine("gestureId=${session.id}")
        appendLine("DOWN x=${fmt(session.down.x)} y=${fmt(session.down.y)}" +
            " threshold=${fmt(session.dragThresholdPx)}px")
    }.trimEnd()

    fun move(update: MoveUpdate): String =
        "MOVE x=${fmt(update.point.x)} y=${fmt(update.point.y)}" +
            " distance=${fmt(update.distance)} state=${update.stateAfter}" +
            if (update.crossedThreshold) "  ← 임계값 넘음" else ""

    fun up(outcome: GestureOutcome): String {
        val last = outcome.session.points.last()
        return buildString {
            appendLine("UP x=${fmt(last.x)} y=${fmt(last.y)}" +
                " distance=${fmt(outcome.session.distanceFrom(last))}")
            append("FINAL_TYPE=${outcome.type.label}")
        }
    }

    fun cancel(session: GestureSession): String =
        "CANCEL gestureId=${session.id} — 확정하지 않고 버림"

    /** 화면에 한 줄로 요약할 때. */
    fun summary(outcome: GestureOutcome): String {
        val s = outcome.session
        return "#${s.id} ${outcome.type.label}  최대이동=${fmt(s.maxDistance)}px" +
            " / 임계값=${fmt(s.dragThresholdPx)}px  점=${s.points.size}개  ${s.durationMs}ms"
    }

    private fun fmt(value: Float): String = "%.2f".format(value)
}

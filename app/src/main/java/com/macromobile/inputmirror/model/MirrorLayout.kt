package com.macromobile.inputmirror.model

import kotlinx.serialization.Serializable

/**
 * 미러링에 참여하는 영역 하나.
 *
 * MASTER 든 TARGET 이든 같은 모양이다. 본 모드에서 MASTER 게임 역시 **주입으로** 입력을
 * 받기 때문이다(사용자는 게임이 아니라 그 위에 덮인 입력 오버레이를 만진다). 그래서
 * MASTER 를 특별 취급하지 않고 "0번 대상"으로 같은 길에 태운다.
 */
@Serializable
data class MirrorRegion(
    /** 저장·기록에 쓰는 고유 id. 이름이 바뀌어도 기록이 이어진다. */
    val id: String,
    val name: String,
    /** 화면(디스플레이) 좌표. View 좌표가 아니다. */
    val bounds: Region,
    /** 목록에서 아예 제외할지. */
    val enabled: Boolean = true,
    /** 켜져 있어도 입력만 잠시 끊을지. */
    val deliverInput: Boolean = true,
    /** 이 대상에만 추가로 주는 지연(ms). */
    val delayMs: Long = 0L,
    /** 자동 인식으로 찾았을 때의 패키지명. 수동 지정이면 null. */
    val packageName: String? = null,
) {
    val isUsable: Boolean get() = bounds.isUsable()

    /** 입력을 실제로 보낼 대상인가. */
    val isActive: Boolean get() = enabled && deliverInput && isUsable
}

/**
 * 화면 배치가 만들어진 조건.
 *
 * 영역 좌표는 **이 조건에서만 뜻이 있다.** 회전하거나 멀티윈도우 크기가 바뀌면 같은
 * 픽셀 좌표가 전혀 다른 곳을 가리키므로, 저장된 좌표를 그대로 쓰면 안 된다.
 * 그래서 배치와 함께 조건을 기록해 두고 매번 대조한다.
 */
@Serializable
data class ScreenFingerprint(
    val width: Int = 0,
    val height: Int = 0,
    val rotation: Int = 0,
) {
    val isKnown: Boolean get() = width > 0 && height > 0

    fun matches(other: ScreenFingerprint): Boolean =
        isKnown && other.isKnown &&
            width == other.width && height == other.height && rotation == other.rotation

    fun describe(): String = "${width}×${height} 회전=$rotation"
}

/** 배치가 지금 쓸 수 있는 상태인지, 아니면 왜 못 쓰는지. */
sealed interface LayoutStatus {
    data object Ready : LayoutStatus
    data object NoMaster : LayoutStatus
    data object NoTarget : LayoutStatus
    data class MasterTooSmall(val region: MirrorRegion) : LayoutStatus
    data class TargetTooSmall(val region: MirrorRegion) : LayoutStatus
    data class Overlap(val a: MirrorRegion, val b: MirrorRegion) : LayoutStatus
    data class ScreenChanged(val saved: ScreenFingerprint, val now: ScreenFingerprint) :
        LayoutStatus

    val isReady: Boolean get() = this is Ready

    /** 사용자에게 보여줄 이유. 왜 시작할 수 없는지를 말로 알려준다. */
    val reason: String
        get() = when (this) {
            Ready -> "준비됨"
            NoMaster -> "MASTER 영역이 아직 지정되지 않았습니다."
            NoTarget -> "입력을 받을 TARGET 이 하나도 없습니다."
            is MasterTooSmall -> "MASTER 영역이 너무 작습니다 " +
                "(${region.bounds.width}×${region.bounds.height}). " +
                "한 변이 ${Region.MIN_SIDE}px 이상이어야 합니다."
            is TargetTooSmall -> "${region.name} 영역이 너무 작습니다 " +
                "(${region.bounds.width}×${region.bounds.height})."
            is Overlap -> "${a.name} 과(와) ${b.name} 영역이 겹칩니다. " +
                "겹치면 주입이 엉뚱한 창으로 갑니다."
            is ScreenChanged -> "화면 구성이 변경되었습니다 " +
                "(${saved.describe()} → ${now.describe()}). " +
                "MASTER/TARGET 영역을 다시 확인해주세요."
        }
}

/**
 * MASTER 하나와 TARGET 여럿의 화면 배치.
 *
 * 좌표는 하드코딩하지 않는다. 전부 사용자가 화면에서 직접 지정했거나 창 정보에서 읽어온
 * 실제 값이다. 이 클래스는 그 값을 담고, **지금 쓸 수 있는 상태인지**를 판단한다.
 */
@Serializable
data class MirrorLayout(
    val master: MirrorRegion? = null,
    val targets: List<MirrorRegion> = emptyList(),
    /** 이 좌표들이 만들어진 화면 조건. */
    val screen: ScreenFingerprint = ScreenFingerprint(),
) {
    /** 실제로 입력을 보낼 대상들. 순서는 목록 순서 그대로다. */
    val activeTargets: List<MirrorRegion> get() = targets.filter { it.isActive }

    val hasAnyRegion: Boolean get() = master != null || targets.isNotEmpty()

    /**
     * 지금 화면에서 이 배치를 쓸 수 있는지.
     *
     * 판단을 한 곳에 모아 둔다. 화면마다 제각각 검사하면 어떤 화면은 빠뜨리게 된다.
     */
    fun status(now: ScreenFingerprint): LayoutStatus {
        if (screen.isKnown && now.isKnown && !screen.matches(now)) {
            return LayoutStatus.ScreenChanged(screen, now)
        }
        val masterRegion = master ?: return LayoutStatus.NoMaster
        if (!masterRegion.isUsable) return LayoutStatus.MasterTooSmall(masterRegion)

        val active = activeTargets
        if (active.isEmpty()) return LayoutStatus.NoTarget
        active.firstOrNull { !it.isUsable }?.let { return LayoutStatus.TargetTooSmall(it) }

        // MASTER 를 포함해 서로 겹치는 쌍이 있는지 본다.
        val all = listOf(masterRegion) + active
        for (i in all.indices) {
            for (j in i + 1 until all.size) {
                if (all[i].bounds.overlaps(all[j].bounds)) {
                    return LayoutStatus.Overlap(all[i], all[j])
                }
            }
        }
        return LayoutStatus.Ready
    }

    fun withMaster(region: MirrorRegion?): MirrorLayout = copy(master = region)

    fun withTarget(region: MirrorRegion): MirrorLayout {
        val index = targets.indexOfFirst { it.id == region.id }
        return if (index >= 0) {
            copy(targets = targets.toMutableList().apply { set(index, region) })
        } else {
            copy(targets = targets + region)
        }
    }

    fun withoutTarget(id: String): MirrorLayout = copy(targets = targets.filterNot { it.id == id })

    /** 사람이 읽는 요약. 디버그 표시와 로그가 같은 내용을 쓴다. */
    fun describe(): String = buildString {
        appendLine("Display: ${screen.width} × ${screen.height}  회전=${screen.rotation}")
        appendLine(
            "MASTER : " + (master?.let { describeRegion(it) } ?: "(지정 안 됨)"),
        )
        targets.forEachIndexed { index, target ->
            appendLine("TARGET${index + 1}: ${describeRegion(target)}")
        }
    }.trimEnd()

    private fun describeRegion(region: MirrorRegion): String {
        val b = region.bounds
        val flags = buildList {
            if (!region.enabled) add("꺼짐")
            if (region.enabled && !region.deliverInput) add("입력정지")
            if (region.delayMs > 0) add("+${region.delayMs}ms")
            region.packageName?.let { add(it) }
        }
        return "L${b.left} T${b.top} R${b.right} B${b.bottom} " +
            "(${b.width}×${b.height})" + if (flags.isEmpty()) "" else "  ${flags.joinToString(" · ")}"
    }

    companion object {
        const val MASTER_ID = "master"

        /** 다음 TARGET 에 붙일 id. 이름이 바뀌어도 겹치지 않게 만든다. */
        fun nextTargetId(existing: List<MirrorRegion>): String {
            var n = existing.size + 1
            val used = existing.map { it.id }.toSet()
            while ("target_$n" in used) n++
            return "target_$n"
        }

        fun defaultTargetName(existing: List<MirrorRegion>): String =
            "TARGET ${existing.size + 1}"
    }
}

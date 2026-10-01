package ai.govbiz.core.supportprogram.service.projection

import java.util.concurrent.ConcurrentHashMap
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * 이 Core 프로세스가 시작된 뒤 Catalog 투영을 한 번 이상 끝낸 제공처입니다. 공고 분석은 시작 직후 아직 이전 공고 내용이
 * 남아 있는 제공처를 분석했다가 투영으로 내용이 바뀌어 다시 분석하는 일을 막기 위해 이 목록 안에서만 공고를 고릅니다.
 * 투영을 쓰지 않는 환경은 Core가 공고를 직접 동기화하므로 제한하지 않습니다.
 */
@Component
class CatalogProjectionProgress(
    @param:Value("\${app.catalog.projection.enabled:false}") private val projectionEnabled: Boolean,
) {
    private val projectedSources: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 투영 호출이 예외 없이 끝났을 때(변경 반영·변경 없음 모두) 부릅니다. */
    fun markProjected(sourceCode: String) {
        projectedSources += sourceCode
    }

    /** 분석해도 되는 제공처입니다. `null`이면 제한하지 않습니다. */
    fun readySources(): Set<String>? = if (projectionEnabled) projectedSources.toSet() else null
}

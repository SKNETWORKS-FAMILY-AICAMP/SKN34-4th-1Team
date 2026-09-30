import { useCallback, useEffect, useState } from 'react'

import { appContainer } from '../../../../app/appContainer'
import type { SupportProgramConditionCheck } from '../../../../domain/entities/SupportProgramConditionCheck'
import type { SupportProgramIdentity } from '../../../../domain/repositories/SupportProgramRepository'
import type { CheckSupportProgramConditionsUseCase } from '../../../../domain/usecases/CheckSupportProgramConditionsUseCase'

export type SupportProgramConditionCheckState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'ready'; check: SupportProgramConditionCheck }
  | { status: 'failed' }

/**
 * 로그인한 회원이 분석을 마친 공고를 볼 때만 회사 정보 기준 조건 판정을 읽습니다.
 * 판정이 상세와 다른 분석(`analyzedAt`)을 썼다면 조건 순서가 어긋날 수 있어 화면이 결과를 쓰지 않도록 `idle`로 둡니다.
 */
export function useSupportProgramConditionCheckViewModel(
  identity: SupportProgramIdentity,
  analyzedAt: string | null,
  enabled: boolean,
  useCase: Pick<CheckSupportProgramConditionsUseCase, 'execute'> = appContainer.resolve('checkSupportProgramConditionsUseCase'),
): SupportProgramConditionCheckState & { retry: () => void } {
  const { sourceCode, sourceProgramId } = identity
  const [version, setVersion] = useState(0)
  const retry = useCallback(() => setVersion((value) => value + 1), [])
  const [state, setState] = useState<SupportProgramConditionCheckState>({ status: 'idle' })

  useEffect(() => {
    // 판정하지 않는 공고(비로그인·분석 전)는 이미 idle이면 상태를 다시 쓰지 않아 상세 화면을 한 번 더 그리지 않습니다.
    const idle = (current: SupportProgramConditionCheckState): SupportProgramConditionCheckState =>
      current.status === 'idle' ? current : { status: 'idle' }
    if (!enabled || analyzedAt === null) {
      setState(idle)
      return
    }
    const controller = new AbortController()
    setState({ status: 'loading' })
    useCase.execute({ sourceCode, sourceProgramId }, controller.signal)
      .then((check) => {
        if (controller.signal.aborted) return
        if (!check || (check.status === 'CHECKED' && check.analyzedAt !== analyzedAt)) setState(idle)
        else setState({ status: 'ready', check })
      })
      .catch(() => { if (!controller.signal.aborted) setState({ status: 'failed' }) })
    return () => controller.abort()
  }, [enabled, analyzedAt, sourceCode, sourceProgramId, useCase, version])

  return { ...state, retry }
}

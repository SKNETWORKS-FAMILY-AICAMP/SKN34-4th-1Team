import type { SupportProgramConditionCheck } from '../entities/SupportProgramConditionCheck'
import type { SupportProgramIdentity } from './SupportProgramRepository'

/** 로그인한 회원의 회사 정보로 공고 분석 조건을 비교한 결과를 읽습니다. 세션으로 인증합니다. */
export interface SupportProgramConditionCheckRepository {
  /** 없거나 더 이상 노출되지 않는 공고는 `null`입니다. */
  check(identity: SupportProgramIdentity, signal?: AbortSignal): Promise<SupportProgramConditionCheck | null>
}

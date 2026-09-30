import type { SupportProgramCondition } from './SupportProgram'

export type SupportProgramConditionResult = 'MET' | 'NOT_MET' | 'UNKNOWN'

export type SupportProgramConditionCheckReason =
  | 'REGION_MATCH' | 'REGION_MISMATCH' | 'BUSINESS_AGE_WITHIN' | 'BUSINESS_AGE_OUTSIDE'
  | 'BOUNDARY_YEAR' | 'PRE_STARTUP_ONLY' | 'PROFILE_MISSING' | 'NOT_COMPARABLE'

/**
 * 공고 분석의 신청 조건을 로그인한 회원의 회사 정보(소재지·설립연도)와 서버 규칙으로 비교한 결과입니다.
 * AI 판단이 아니며, 회사 정보로 비교할 수 없는 조건은 `UNKNOWN`으로 남습니다.
 * `conditions[].index`는 같은 분석(`analyzedAt`)의 `analysis.conditions` 순서를 가리킵니다.
 */
export type SupportProgramConditionCheck =
  | { status: 'NO_COMPANY' }
  | { status: 'NOT_ANALYZED' }
  | {
    status: 'CHECKED'
    analyzedAt: string
    referenceDate: string
    profile: { region: string | null; foundedYear: number | null }
    overall: SupportProgramConditionResult
    conditions: { index: number; result: SupportProgramConditionResult; reason: SupportProgramConditionCheckReason }[]
  }

/** 조건 종류마다 결과의 뜻이 다릅니다. 제외 대상은 "해당 없음"이 좋은 결과이고, 우대 사항은 전체 판정에 들어가지 않습니다. */
export function supportProgramConditionResultLabel(kind: SupportProgramCondition['kind'], result: SupportProgramConditionResult): string {
  if (result === 'UNKNOWN') return '확인 필요'
  if (kind === 'EXCLUDED') return result === 'MET' ? '해당 없음' : '제외 대상 해당'
  if (kind === 'PREFERRED') return result === 'MET' ? '우대 해당' : '우대 미해당'
  return result === 'MET' ? '충족' : '미충족'
}

export const supportProgramConditionOverallLabels: Record<SupportProgramConditionResult, string> = {
  MET: '조건 충족',
  NOT_MET: '대상 아님 가능성',
  UNKNOWN: '확인 필요',
}

/** 판정 이유를 회사 정보와 함께 한 줄로 풀어 씁니다. */
export function supportProgramConditionReasonText(
  reason: SupportProgramConditionCheckReason,
  profile: { region: string | null; foundedYear: number | null },
): string {
  switch (reason) {
    case 'REGION_MATCH': return `회사 소재지(${profile.region ?? '-'})가 해당 지역이에요`
    case 'REGION_MISMATCH': return `회사 소재지(${profile.region ?? '-'})가 해당 지역이 아니에요`
    case 'BUSINESS_AGE_WITHIN': return `설립연도(${profile.foundedYear ?? '-'}년) 기준으로 업력 조건에 들어요`
    case 'BUSINESS_AGE_OUTSIDE': return `설립연도(${profile.foundedYear ?? '-'}년) 기준으로 업력 조건을 벗어나요`
    case 'BOUNDARY_YEAR': return '설립연도만으로는 경계에 있어 정확한 설립일로 확인해야 해요'
    case 'PRE_STARTUP_ONLY': return '예비창업자 조건이에요. 사업자 등록을 마친 회사는 예비창업자가 아니에요'
    case 'PROFILE_MISSING': return '회사 정보에 없는 항목이라 직접 확인해야 해요'
    case 'NOT_COMPARABLE': return '회사 정보로 비교할 수 없는 조건이라 원문으로 확인해야 해요'
  }
}

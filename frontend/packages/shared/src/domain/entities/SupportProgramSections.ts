import type {
  SupportProgramAnalysisEvidence, SupportProgramAnalysisSummary, SupportProgramCondition, SupportProgramDetail, SupportProgramSupportType,
} from './SupportProgram'

const TARGET_PREFIX = '지원 대상: '
const EXCLUDED_PREFIX = '제외 대상: '

/**
 * 공고 원문의 지원 대상과 제외 대상을 나눕니다. K-Startup은 두 공식 필드를 서버가 `지원 대상: …`, `제외 대상: …` 줄로
 * 이어 붙여 보내므로 그 형식일 때만 나누고, 다른 제공처는 본문이 우연히 같은 글자를 담아도 원문 그대로 둡니다.
 */
export function splitSupportProgramTarget(sourceCode: string, targetDescription: string): { target: string; excluded: string | null } {
  if (sourceCode !== 'KSTARTUP') return { target: targetDescription, excluded: null }
  const lines = targetDescription.split('\n')
  if (!lines.every((line) => line.startsWith(TARGET_PREFIX) || line.startsWith(EXCLUDED_PREFIX))) {
    return { target: targetDescription, excluded: null }
  }
  const target = lines.find((line) => line.startsWith(TARGET_PREFIX))?.slice(TARGET_PREFIX.length).trim() ?? ''
  const excluded = lines.find((line) => line.startsWith(EXCLUDED_PREFIX))?.slice(EXCLUDED_PREFIX.length).trim() || null
  return { target, excluded }
}

/** 공식 신청 필드로 분류한 신청 경로의 짧은 이름입니다. 분류할 수 없으면 `null`이라 화면은 원문 확인을 안내합니다. */
export function supportProgramApplicationRouteLabel(route: SupportProgramDetail['applicationRoute']): string | null {
  switch (route.type) {
    case 'GOOGLE_FORMS': return '온라인 신청 (구글 설문)'
    case 'OTHER_ONLINE_FORM': return '온라인 신청 (접수 사이트)'
    case 'FILE': return '이메일·우편·방문 제출'
    case 'UNKNOWN': return null
  }
}

export const supportProgramSupportTypeLabels: Record<SupportProgramSupportType, string> = {
  GRANT: '사업화 자금', LOAN: '융자', GUARANTEE: '보증', VOUCHER: '바우처', CONSULTING: '컨설팅·멘토링', EDUCATION: '교육',
  SPACE: '공간·입주', MARKETING: '판로·마케팅', RND: '기술개발', EXPORT: '수출·해외진출', HR: '인력·고용', OTHER: '기타 지원',
}

export const supportProgramConditionCategoryLabels: Record<SupportProgramCondition['category'], string> = {
  REGION: '지역', BUSINESS_AGE: '업력', FOUNDER_AGE: '대표자 연령', INDUSTRY: '업종', COMPANY_SIZE: '기업 규모',
  LEGAL_FORM: '기업 형태', CERTIFICATION: '인증', OTHER: '기타',
}

/** 분석 인용이 공고의 어느 부분에서 왔는지 알려 주는 이름입니다. */
export const supportProgramEvidenceFieldLabels: Record<SupportProgramAnalysisEvidence['field'], string> = {
  SUMMARY: '사업 개요', TARGET_DESCRIPTION: '지원 대상', APPLICATION_METHOD: '신청 방법', DETAIL_TEXT: '공고 상세 본문', ATTACHMENT: '첨부파일',
}

/** 인용 출처 이름입니다. 첨부파일이면 파일 이름을 붙입니다. */
export function supportProgramEvidenceSourceLabel(evidence: SupportProgramAnalysisEvidence): string {
  const label = supportProgramEvidenceFieldLabels[evidence.field]
  return evidence.attachmentName ? `${label} ${evidence.attachmentName}` : label
}

export const supportProgramDocumentRequirementLabels = { REQUIRED: '필수', OPTIONAL: '선택', CONDITIONAL: '해당 시' } as const

/**
 * 신청 조건을 필수 조건 · 제외 대상 · 우대 사항 순서로 묶습니다. 비어 있는 묶음은 빠집니다.
 * 조건 판정 결과가 원래 순서(`index`)를 가리키므로 각 항목에 그 순서를 함께 둡니다.
 */
export function groupSupportProgramConditions(conditions: SupportProgramCondition[]) {
  const entries = conditions.map((condition, index) => ({ condition, index }))
  return ([
    ['REQUIRED', '신청 조건'],
    ['EXCLUDED', '제외 대상'],
    ['PREFERRED', '우대 사항'],
  ] as const)
    .map(([kind, title]) => ({ kind, title, entries: entries.filter((entry) => entry.condition.kind === kind) }))
    .filter((group) => group.entries.length > 0)
}

/** 목록 카드의 핵심 정보 한 줄입니다. 지원 규모를 먼저, 지원 형태는 최대 두 개까지 둡니다. 분석 요약이 없으면 빈 목록입니다. */
export function supportProgramAnalysisFacts(summary: SupportProgramAnalysisSummary | null): string[] {
  if (!summary) return []
  return [
    ...(summary.supportAmountText ? [summary.supportAmountText] : []),
    ...summary.supportTypes.slice(0, 2).map((type) => supportProgramSupportTypeLabels[type]),
  ]
}

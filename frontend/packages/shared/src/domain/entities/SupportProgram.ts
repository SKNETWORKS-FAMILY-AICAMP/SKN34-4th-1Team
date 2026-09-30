export type SupportProgramStatus = 'OPEN' | 'UPCOMING' | 'CLOSED' | 'UNKNOWN'

export type SupportProgramEligibilityAxis = {
  status: 'MATCH' | 'UNKNOWN'
  explanation: string
  evidence: { field: 'SUMMARY' | 'TARGET_DESCRIPTION'; quote: string }[]
}

export type SupportProgramEligibilityReview = {
  status: 'MATCH' | 'REVIEW_REQUIRED'
  basis: 'OFFICIAL_API_TEXT'
  target: SupportProgramEligibilityAxis
  region: SupportProgramEligibilityAxis
}

export type SupportProgram = {
  /** 제공처별 원본 식별자의 제공처 코드입니다. */
  sourceCode: string
  /** 제공처 안에서 공고를 식별하는 원본 ID입니다. */
  id: string
  title: string
  organization: string
  summary: string
  categories: string[]
  regions: string[]
  targetDescription: string
  applicationPeriod: string
  applicationStartDate: string | null
  applicationEndDate: string | null
  status: SupportProgramStatus
  sourceName: string
  sourceUrl: string
  matchedReasons: string[]
  recommendationScore: number | null
  eligibilityReview: SupportProgramEligibilityReview | null
  /** 목록 카드용 공고 분석 요약입니다. 현재 원문으로 분석을 마친 공고만 있습니다. */
  analysisSummary: SupportProgramAnalysisSummary | null
}

export type SupportProgramAnalysisSummary = {
  summaryLine: string | null
  supportAmountText: string | null
  maxAmountKrw: number | null
  supportTypes: SupportProgramSupportType[]
}

export type SupportProgramAnalysisEvidence = {
  field: 'SUMMARY' | 'TARGET_DESCRIPTION' | 'APPLICATION_METHOD' | 'DETAIL_TEXT' | 'ATTACHMENT'
  /** 해당 원문 필드에 그대로 들어 있는 인용입니다. 서버가 정확히 일치하는 인용만 남깁니다. */
  quote: string
  /** 첨부파일에서 인용했을 때의 파일 이름입니다. 그 밖의 원문이면 `null`입니다. */
  attachmentName: string | null
}

export type SupportProgramSupportType =
  | 'GRANT' | 'LOAN' | 'GUARANTEE' | 'VOUCHER' | 'CONSULTING' | 'EDUCATION'
  | 'SPACE' | 'MARKETING' | 'RND' | 'EXPORT' | 'HR' | 'OTHER'

export type SupportProgramCondition = {
  kind: 'REQUIRED' | 'EXCLUDED' | 'PREFERRED'
  category: 'REGION' | 'BUSINESS_AGE' | 'FOUNDER_AGE' | 'INDUSTRY' | 'COMPANY_SIZE' | 'LEGAL_FORM' | 'CERTIFICATION' | 'OTHER'
  text: string
  /** 원문이 숫자·지역을 명시한 경우에만 채워지는 정규화 값입니다. */
  values: { regions: string[] | null; minYears: number | null; maxYears: number | null; minAge: number | null; maxAge: number | null }
  evidence: SupportProgramAnalysisEvidence
}

/**
 * AI가 공고 본문(첨부 제외)에서 정리한 결과입니다. 현재 원문으로 분석을 마친 경우만 내용이 있고,
 * 아직 분석하지 않았거나 원문이 바뀐 공고는 `NOT_ANALYZED`, 분석에 실패한 공고는 `FAILED`입니다.
 * 내용 필드의 `null`·빈 목록은 "공고 본문에 명시 없음"을 뜻합니다.
 */
export type SupportProgramAnalysis =
  | { status: 'NOT_ANALYZED' }
  | { status: 'FAILED' }
  | {
    status: 'COMPLETED'
    analyzedAt: string
    summaryLine: string | null
    supportTypes: SupportProgramSupportType[]
    supportAmount: { text: string; maxAmountKrw: number | null; evidence: SupportProgramAnalysisEvidence } | null
    selectionScale: { text: string; evidence: SupportProgramAnalysisEvidence } | null
    conditions: SupportProgramCondition[]
    contact: { text: string; evidence: SupportProgramAnalysisEvidence } | null
    requiredDocuments: { name: string; requirement: 'REQUIRED' | 'OPTIONAL' | 'CONDITIONAL'; note: string | null; evidence: SupportProgramAnalysisEvidence }[]
    /** 원문에 나온 순서대로의 선정 절차입니다. */
    selectionSteps: { name: string; note: string | null; evidence: SupportProgramAnalysisEvidence }[]
    evaluationCriteria: { item: string; points: number | null; evidence: SupportProgramAnalysisEvidence }[]
    /** 접수 외 일정(사전등록·설명회·발표평가·협약 등)입니다. 날짜가 명시되지 않으면 `date`는 `null`이고 원문 표현은 `text`에 있습니다. */
    schedule: { label: string; date: string | null; text: string; evidence: SupportProgramAnalysisEvidence }[]
    /** 분석에 쓴 첨부파일 이름입니다. 첨부가 없거나 첨부 분석 이전 결과면 빈 목록입니다. */
    sourceAttachmentNames: string[]
  }

/**
 * 상세 조회로 받는 공고입니다. 검색 결과와 달리 관련도·추천 이유·자격 판정이 없고, 상세 화면이 제공처를
 * 직접 비교하지 않도록 원문 근거 질문 지원 여부를 서버가 정해 줍니다.
 */
export type SupportProgramDetail = Omit<SupportProgram, 'matchedReasons' | 'recommendationScore' | 'eligibilityReview' | 'analysisSummary'> & {
  evidenceQuestionSupported: boolean
  applicationRoute: { method: string | null; url: string | null; type: 'GOOGLE_FORMS' | 'OTHER_ONLINE_FORM' | 'FILE' | 'UNKNOWN' }
  analysis: SupportProgramAnalysis
}

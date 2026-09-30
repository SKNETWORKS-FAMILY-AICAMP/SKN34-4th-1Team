import { z } from 'zod'
import { conversationContextDtoSchema } from './SupportProgramConversationDto'

import type { SupportProgram, SupportProgramAnalysis, SupportProgramDetail } from '../../domain/entities/SupportProgram'

const isoLocalDateSchema = z.iso.date()
const sourceCodeSchema = z.string().regex(/^[A-Z][A-Z0-9_]{0,63}$/)
const officialSourceHostsByCode: Record<string, readonly string[]> = {
  BIZINFO: ['bizinfo.go.kr'],
  KSTARTUP: ['k-startup.go.kr'],
  MSIT: ['msit.go.kr'],
  CNTRADE_NOTICE: ['cntrade.chungnam.go.kr'],
}

export function isOfficialSupportProgramSourceUrl(sourceCode: string, value: string): boolean {
  try {
    const url = new URL(value)
    const hostname = url.hostname.toLowerCase()
    const officialHosts = officialSourceHostsByCode[sourceCode]

    return (url.protocol === 'https:' || url.protocol === 'http:')
      && !url.username
      && !url.password
      && !url.port
      && officialHosts?.some((officialHost) => (
        hostname === officialHost || hostname.endsWith(`.${officialHost}`)
      )) === true
  } catch {
    return false
  }
}

const eligibilityAxisDtoSchema = z.object({
  status: z.enum(['MATCH', 'UNKNOWN']),
  explanation: z.string().refine((value) => value.trim().length > 0
    && Array.from(value).length <= 160 && !/\p{C}/u.test(value)),
  evidence: z.array(z.object({
    field: z.enum(['SUMMARY', 'TARGET_DESCRIPTION']),
    quote: z.string().refine((value) => value.trim().length > 0
      && Array.from(value).length <= 240 && !/\p{C}/u.test(value)),
  })).max(1),
}).superRefine((axis, context) => {
  if (axis.status === 'MATCH' && axis.evidence.length !== 1) {
    context.addIssue({ code: 'custom', path: ['evidence'], message: '조건 확인에는 공식 본문 인용이 필요합니다.' })
  }
})

const eligibilityReviewDtoSchema = z.object({
  status: z.enum(['MATCH', 'REVIEW_REQUIRED']),
  basis: z.literal('OFFICIAL_API_TEXT'),
  target: eligibilityAxisDtoSchema,
  region: eligibilityAxisDtoSchema,
}).superRefine((review, context) => {
  const bothAxesMatch = review.target.status === 'MATCH' && review.region.status === 'MATCH'
  if ((review.status === 'MATCH') !== bothAxesMatch) {
    context.addIssue({ code: 'custom', path: ['status'], message: '전체 판정과 지원 대상·지역 판정이 일치해야 합니다.' })
  }
})

/** 검색 결과와 상세 조회가 함께 쓰는 공고 기본 필드입니다. */
const supportProgramBaseShape = {
  sourceCode: sourceCodeSchema,
  id: z.string().min(1),
  title: z.string().min(1),
  organization: z.string(),
  summary: z.string(),
  categories: z.array(z.string()),
  regions: z.array(z.string()),
  targetDescription: z.string(),
  applicationPeriod: z.string().min(1),
  applicationStartDate: isoLocalDateSchema.nullable(),
  applicationEndDate: isoLocalDateSchema.nullable(),
  status: z.enum(['OPEN', 'UPCOMING', 'CLOSED', 'UNKNOWN']),
  sourceName: z.string().min(1),
  sourceUrl: z.string().url(),
}

function requireOfficialSourceUrl(program: { sourceCode: string; sourceUrl: string }, context: z.RefinementCtx): void {
  if (!isOfficialSupportProgramSourceUrl(program.sourceCode, program.sourceUrl)) {
    context.addIssue({
      code: 'custom',
      path: ['sourceUrl'],
      message: `${program.sourceCode} 제공처의 공식 http(s) URL이어야 합니다.`,
    })
  }
}

/** 목록 카드용 분석 요약입니다. 이전 서버 응답·저장된 대화 기록에는 없으므로 없으면 `null`로 읽습니다. */
const supportProgramAnalysisSummaryDtoSchema = z.object({
  summaryLine: z.string().refine((value) => value.trim().length > 0 && Array.from(value).length <= 500 && !/(?![\n\r\t])\p{C}/u.test(value)).nullable(),
  supportAmountText: z.string().refine((value) => value.trim().length > 0 && Array.from(value).length <= 1000 && !/(?![\n\r\t])\p{C}/u.test(value)).nullable(),
  maxAmountKrw: z.number().int().positive().nullable(),
  supportTypes: z.array(z.enum(['GRANT', 'LOAN', 'GUARANTEE', 'VOUCHER', 'CONSULTING', 'EDUCATION', 'SPACE', 'MARKETING', 'RND', 'EXPORT', 'HR', 'OTHER'])).max(12),
})

export const supportProgramDtoSchema = z.object({
  ...supportProgramBaseShape,
  matchedReasons: z.array(z.string()),
  recommendationScore: z.number().int().min(0).max(100).nullable(),
  eligibilityReview: eligibilityReviewDtoSchema.nullable().default(null),
  analysisSummary: supportProgramAnalysisSummaryDtoSchema.nullable().default(null),
}).superRefine((program, context) => {
  requireOfficialSourceUrl(program, context)
  if (!program.eligibilityReview) return
  for (const axisName of ['target', 'region'] as const) {
    program.eligibilityReview[axisName].evidence.forEach((evidence, index) => {
      const source = evidence.field === 'SUMMARY' ? program.summary : program.targetDescription
      if (!source.includes(evidence.quote)) {
        context.addIssue({
          code: 'custom',
          path: ['eligibilityReview', axisName, 'evidence', index, 'quote'],
          message: '자격 판정 인용은 지정한 공고 본문과 정확히 일치해야 합니다.',
        })
      }
    })
  }
})

export const supportProgramSearchResponseDtoSchema = z.object({
  query: z.string(),
  programs: z.array(supportProgramDtoSchema).max(5),
  totalCount: z.number().int().min(0).max(5),
  resultToken: z.uuid().refine((value) => value === value.toLowerCase()).nullable(),
  expiresAt: z.iso.datetime().nullable(),
}).superRefine((response, context) => {
  const locked = response.resultToken !== null
  if (locked !== (response.expiresAt !== null)
    || (locked ? response.programs.length !== 2 || response.totalCount <= 2
      : response.totalCount !== response.programs.length)) {
    context.addIssue({ code: 'custom', message: '공개 공고 수와 전체 추천 수, 결과 보관 정보가 일치해야 합니다.' })
  }
  const identities = new Set<string>()
  response.programs.forEach((program, index) => {
    const identity = JSON.stringify([program.sourceCode, program.id])
    if (identities.has(identity)) {
      context.addIssue({ code: 'custom', path: ['programs', index, 'id'], message: '같은 제공처의 공고가 검색 결과에 중복될 수 없습니다.' })
    }
    identities.add(identity)
  })
})

export const restoredSupportProgramSearchResponseDtoSchema = supportProgramSearchResponseDtoSchema.safeExtend({
  context: conversationContextDtoSchema,
}).superRefine((response, context) => {
  if (response.resultToken !== null || response.expiresAt !== null
    || (response.context.query ?? '') !== response.query) {
    context.addIssue({ code: 'custom', message: '복원 결과는 전체 공고와 동일한 검색 조건을 반환해야 합니다.' })
  }
})

export type RestoredSupportProgramSearchResponseDto = z.infer<typeof restoredSupportProgramSearchResponseDtoSchema>

// 공고 상세 본문 인용은 줄바꿈·탭을 담을 수 있으므로 그 밖의 제어 문자만 거절합니다.
const analysisTextSchema = (max: number) => z.string().refine((value) => value.trim().length > 0
  && Array.from(value).length <= max && !/(?![\n\r\t])\p{C}/u.test(value))
const analysisEvidenceDtoSchema = z.object({
  field: z.enum(['SUMMARY', 'TARGET_DESCRIPTION', 'APPLICATION_METHOD', 'DETAIL_TEXT', 'ATTACHMENT']),
  quote: analysisTextSchema(1000),
  // 첨부 분석 이전 응답에는 없으므로 없으면 null입니다.
  attachmentName: analysisTextSchema(255).nullable().default(null),
}).refine((evidence) => (evidence.field === 'ATTACHMENT') === (evidence.attachmentName !== null), {
  message: '첨부파일 인용만 파일 이름을 가집니다.',
})
const nonNegativeNumberSchema = z.number().finite().nonnegative()

/**
 * 공고 분석입니다. 서버는 상태와 관계없이 모든 필드를 보내고, 분석을 마친 경우에만 내용을 채웁니다.
 * 분석 전·실패 응답은 내용이 없으므로 빠진 필드는 비어 있는 값으로 읽습니다.
 * 길이 한도는 Core 응답 검증(AiSupportProgramAnalysisMapper)과 같게 두어 서버가 저장·반환할 수 있는 값을 화면이 거절하지 않게 합니다.
 */
const supportProgramAnalysisDtoSchema = z.object({
  status: z.enum(['COMPLETED', 'FAILED', 'NOT_ANALYZED']),
  analyzedAt: z.iso.datetime({ local: true, offset: true }).nullable().default(null),
  summaryLine: analysisTextSchema(500).nullable().default(null),
  supportTypes: z.array(z.enum(['GRANT', 'LOAN', 'GUARANTEE', 'VOUCHER', 'CONSULTING', 'EDUCATION', 'SPACE', 'MARKETING', 'RND', 'EXPORT', 'HR', 'OTHER'])).max(12).default([]),
  supportAmount: z.object({
    text: analysisTextSchema(1000), maxAmountKrw: z.number().int().positive().nullable(), evidence: analysisEvidenceDtoSchema,
  }).nullable().default(null),
  selectionScale: z.object({ text: analysisTextSchema(1000), evidence: analysisEvidenceDtoSchema }).nullable().default(null),
  conditions: z.array(z.object({
    kind: z.enum(['REQUIRED', 'EXCLUDED', 'PREFERRED']),
    category: z.enum(['REGION', 'BUSINESS_AGE', 'FOUNDER_AGE', 'INDUSTRY', 'COMPANY_SIZE', 'LEGAL_FORM', 'CERTIFICATION', 'OTHER']),
    text: analysisTextSchema(1000),
    values: z.object({
      regions: z.array(analysisTextSchema(20)).max(18).nullable(),
      minYears: nonNegativeNumberSchema.nullable(),
      maxYears: nonNegativeNumberSchema.nullable(),
      minAge: z.number().int().nonnegative().nullable(),
      maxAge: z.number().int().nonnegative().nullable(),
    }),
    evidence: analysisEvidenceDtoSchema,
  })).max(30).default([]),
  contact: z.object({ text: analysisTextSchema(1000), evidence: analysisEvidenceDtoSchema }).nullable().default(null),
  // 아래는 첨부파일까지 분석한 결과입니다. 첨부 분석 이전 응답에는 없으므로 없으면 빈 목록입니다.
  requiredDocuments: z.array(z.object({
    name: analysisTextSchema(1000), requirement: z.enum(['REQUIRED', 'OPTIONAL', 'CONDITIONAL']),
    note: analysisTextSchema(1000).nullable(), evidence: analysisEvidenceDtoSchema,
  })).max(30).default([]),
  selectionSteps: z.array(z.object({
    name: analysisTextSchema(1000), note: analysisTextSchema(1000).nullable(), evidence: analysisEvidenceDtoSchema,
  })).max(10).default([]),
  evaluationCriteria: z.array(z.object({
    item: analysisTextSchema(1000), points: z.number().finite().nonnegative().max(1000).nullable(), evidence: analysisEvidenceDtoSchema,
  })).max(20).default([]),
  schedule: z.array(z.object({
    label: analysisTextSchema(1000), date: z.iso.date().nullable(), text: analysisTextSchema(1000), evidence: analysisEvidenceDtoSchema,
  })).max(15).default([]),
  sourceAttachmentNames: z.array(analysisTextSchema(255)).max(8).default([]),
}).superRefine((analysis, context) => {
  const hasContent = analysis.summaryLine !== null || analysis.supportTypes.length > 0 || analysis.supportAmount !== null
    || analysis.selectionScale !== null || analysis.conditions.length > 0 || analysis.contact !== null
    || analysis.requiredDocuments.length > 0 || analysis.selectionSteps.length > 0
    || analysis.evaluationCriteria.length > 0 || analysis.schedule.length > 0
  if (analysis.status === 'COMPLETED' ? analysis.analyzedAt === null : hasContent) {
    context.addIssue({ code: 'custom', message: '분석을 마친 공고만 분석 시각과 내용을 가질 수 있습니다.' })
  }
})

/** 상세 조회 응답입니다. 검색 전용 필드가 없고 원문 근거 질문 지원 여부를 서버가 정합니다. */
export const supportProgramDetailDtoSchema = z.object({
  ...supportProgramBaseShape,
  evidenceQuestionSupported: z.boolean(),
  applicationRoute: z.object({
    method: z.string().nullable(),
    url: z.string().url().refine((value) => {
      const url = new URL(value)
      return ['http:', 'https:'].includes(url.protocol) && Boolean(url.hostname) && !url.username && !url.password
    }).nullable(),
    type: z.enum(['GOOGLE_FORMS', 'OTHER_ONLINE_FORM', 'FILE', 'UNKNOWN']),
  }),
  // 공고 분석보다 먼저 배포된 서버는 이 필드를 보내지 않으므로, 없으면 아직 분석 전으로 읽습니다.
  analysis: supportProgramAnalysisDtoSchema.optional(),
}).superRefine(requireOfficialSourceUrl)

export type SupportProgramDto = z.infer<typeof supportProgramDtoSchema>
export type SupportProgramDetailDto = z.infer<typeof supportProgramDetailDtoSchema>
export type SupportProgramSearchResponseDto = z.infer<
  typeof supportProgramSearchResponseDtoSchema
>

/** HTTP DTO와 Domain 객체가 우연히 같은 모양이어도 경계를 명시적으로 유지합니다. */
export function toSupportProgram(dto: SupportProgramDto): SupportProgram {
  return {
    sourceCode: dto.sourceCode,
    id: dto.id,
    title: dto.title,
    organization: dto.organization,
    summary: dto.summary,
    categories: [...dto.categories],
    regions: [...dto.regions],
    targetDescription: dto.targetDescription,
    applicationPeriod: dto.applicationPeriod,
    applicationStartDate: dto.applicationStartDate,
    applicationEndDate: dto.applicationEndDate,
    status: dto.status,
    sourceName: dto.sourceName,
    sourceUrl: dto.sourceUrl,
    matchedReasons: [...dto.matchedReasons],
    recommendationScore: dto.recommendationScore,
    eligibilityReview: dto.eligibilityReview ? {
      status: dto.eligibilityReview.status,
      basis: dto.eligibilityReview.basis,
      target: {
        ...dto.eligibilityReview.target,
        evidence: dto.eligibilityReview.target.evidence.map((evidence) => ({ ...evidence })),
      },
      region: {
        ...dto.eligibilityReview.region,
        evidence: dto.eligibilityReview.region.evidence.map((evidence) => ({ ...evidence })),
      },
    } : null,
    analysisSummary: dto.analysisSummary ? { ...dto.analysisSummary, supportTypes: [...dto.analysisSummary.supportTypes] } : null,
  }
}

export function toSupportProgramDetail(dto: SupportProgramDetailDto): SupportProgramDetail {
  return {
    sourceCode: dto.sourceCode,
    id: dto.id,
    title: dto.title,
    organization: dto.organization,
    summary: dto.summary,
    categories: [...dto.categories],
    regions: [...dto.regions],
    targetDescription: dto.targetDescription,
    applicationPeriod: dto.applicationPeriod,
    applicationStartDate: dto.applicationStartDate,
    applicationEndDate: dto.applicationEndDate,
    status: dto.status,
    sourceName: dto.sourceName,
    sourceUrl: dto.sourceUrl,
    evidenceQuestionSupported: dto.evidenceQuestionSupported,
    applicationRoute: { ...dto.applicationRoute },
    analysis: dto.analysis ? toSupportProgramAnalysis(dto.analysis) : { status: 'NOT_ANALYZED' },
  }
}

function toSupportProgramAnalysis(dto: z.infer<typeof supportProgramAnalysisDtoSchema>): SupportProgramAnalysis {
  if (dto.status !== 'COMPLETED' || dto.analyzedAt === null) return { status: dto.status === 'FAILED' ? 'FAILED' : 'NOT_ANALYZED' }
  return {
    status: 'COMPLETED',
    analyzedAt: dto.analyzedAt,
    summaryLine: dto.summaryLine,
    supportTypes: [...dto.supportTypes],
    supportAmount: dto.supportAmount ? { ...dto.supportAmount, evidence: { ...dto.supportAmount.evidence } } : null,
    selectionScale: dto.selectionScale ? { ...dto.selectionScale, evidence: { ...dto.selectionScale.evidence } } : null,
    conditions: dto.conditions.map((condition) => ({
      ...condition,
      values: { ...condition.values, regions: condition.values.regions ? [...condition.values.regions] : null },
      evidence: { ...condition.evidence },
    })),
    contact: dto.contact ? { ...dto.contact, evidence: { ...dto.contact.evidence } } : null,
    requiredDocuments: dto.requiredDocuments.map((document) => ({ ...document, evidence: { ...document.evidence } })),
    selectionSteps: dto.selectionSteps.map((step) => ({ ...step, evidence: { ...step.evidence } })),
    evaluationCriteria: dto.evaluationCriteria.map((criterion) => ({ ...criterion, evidence: { ...criterion.evidence } })),
    schedule: dto.schedule.map((entry) => ({ ...entry, evidence: { ...entry.evidence } })),
    sourceAttachmentNames: [...dto.sourceAttachmentNames],
  }
}

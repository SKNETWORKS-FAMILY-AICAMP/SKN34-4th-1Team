import { describe, expect, it } from 'vitest'

import { supportProgramDetailDtoSchema, toSupportProgramDetail } from './SupportProgramDto'

const detail = {
  sourceCode: 'BIZINFO', id: 'PBLN_1', title: '창업 지원', organization: '중소벤처기업부', summary: '최대 5천만원 지원',
  categories: [], regions: ['서울'], targetDescription: '창업 7년 이내 중소기업', applicationPeriod: '2026-10-01 ~ 2026-10-31',
  applicationStartDate: '2026-10-01', applicationEndDate: '2026-10-31', status: 'OPEN', sourceName: '기업마당',
  sourceUrl: 'https://www.bizinfo.go.kr/program', evidenceQuestionSupported: true,
  applicationRoute: { method: null, url: null, type: 'UNKNOWN' },
}
const empty = { analyzedAt: null, summaryLine: null, supportTypes: [], supportAmount: null, selectionScale: null, conditions: [], contact: null }
const completed = {
  status: 'COMPLETED', analyzedAt: '2026-10-01T10:00:00.123456', summaryLine: '창업기업에 최대 5천만원 사업화 자금',
  supportTypes: ['GRANT'],
  supportAmount: { text: '최대 5천만원', maxAmountKrw: 50_000_000, evidence: { field: 'SUMMARY', quote: '최대 5천만원 지원' } },
  selectionScale: null,
  conditions: [{
    kind: 'REQUIRED', category: 'BUSINESS_AGE', text: '창업 7년 이내',
    values: { regions: null, minYears: null, maxYears: 7, minAge: null, maxAge: null },
    evidence: { field: 'TARGET_DESCRIPTION', quote: '창업 7년 이내' },
  }],
  contact: null,
}

describe('공고 상세의 공고 분석', () => {
  it('분석을 마친 내용을 도메인 값으로 옮긴다', () => {
    const program = toSupportProgramDetail(supportProgramDetailDtoSchema.parse({ ...detail, analysis: completed }))
    expect(program.analysis).toMatchObject({ status: 'COMPLETED', supportTypes: ['GRANT'], conditions: [{ text: '창업 7년 이내' }] })
  })

  it('분석 필드를 보내지 않는 이전 서버 응답은 분석 전으로 읽는다', () => {
    expect(toSupportProgramDetail(supportProgramDetailDtoSchema.parse(detail)).analysis).toEqual({ status: 'NOT_ANALYZED' })
  })

  it('실패와 분석 전 상태는 내용 없이 전달한다', () => {
    expect(toSupportProgramDetail(supportProgramDetailDtoSchema.parse({ ...detail, analysis: { status: 'FAILED', ...empty } })).analysis)
      .toEqual({ status: 'FAILED' })
  })

  it('분석을 마치지 않았는데 내용이 있거나, 마쳤는데 분석 시각이 없으면 거절한다', () => {
    expect(supportProgramDetailDtoSchema.safeParse({ ...detail, analysis: { ...completed, status: 'NOT_ANALYZED' } }).success).toBe(false)
    expect(supportProgramDetailDtoSchema.safeParse({ ...detail, analysis: { ...completed, analyzedAt: null } }).success).toBe(false)
  })

  it('상세 본문 인용의 줄바꿈은 허용한다', () => {
    const condition = { ...completed.conditions[0], evidence: { field: 'DETAIL_TEXT', quote: '창업 7년 이내\n중소기업' } }
    expect(supportProgramDetailDtoSchema.safeParse({ ...detail, analysis: { ...completed, conditions: [condition] } }).success).toBe(true)
  })

  it('알 수 없는 조건 종류나 제어 문자가 든 인용은 거절한다', () => {
    const condition = completed.conditions[0]
    expect(supportProgramDetailDtoSchema.safeParse({ ...detail, analysis: { ...completed, conditions: [{ ...condition, kind: 'MAYBE' }] } }).success).toBe(false)
    expect(supportProgramDetailDtoSchema.safeParse({ ...detail, analysis: { ...completed, conditions: [{ ...condition, evidence: { field: 'SUMMARY', quote: '줄\u0000바꿈' } }] } }).success).toBe(false)
  })
})

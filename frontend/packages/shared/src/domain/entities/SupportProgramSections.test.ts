import { describe, expect, it } from 'vitest'

import { groupSupportProgramConditions, splitSupportProgramTarget, supportProgramAnalysisFacts, supportProgramApplicationRouteLabel } from './SupportProgramSections'

describe('splitSupportProgramTarget', () => {
  it('K-Startup의 지원 대상과 제외 대상 줄을 나눈다', () => {
    expect(splitSupportProgramTarget('KSTARTUP', '지원 대상: 창업 3년 이내 기업\n제외 대상: 휴·폐업 중인 기업'))
      .toEqual({ target: '창업 3년 이내 기업', excluded: '휴·폐업 중인 기업' })
  })

  it('제외 대상만 있으면 지원 대상은 비워 둔다', () => {
    expect(splitSupportProgramTarget('KSTARTUP', '제외 대상: 대기업')).toEqual({ target: '', excluded: '대기업' })
  })

  it('K-Startup이라도 약속한 줄 형식이 아니면 원문 그대로 둔다', () => {
    expect(splitSupportProgramTarget('KSTARTUP', '정보 없음')).toEqual({ target: '정보 없음', excluded: null })
  })

  it('다른 제공처는 같은 글자가 있어도 나누지 않는다', () => {
    const text = '지원 대상: 중소기업\n제외 대상: 대기업'
    expect(splitSupportProgramTarget('BIZINFO', text)).toEqual({ target: text, excluded: null })
  })
})

describe('supportProgramApplicationRouteLabel', () => {
  it.each([
    ['GOOGLE_FORMS', '온라인 신청 (구글 설문)'],
    ['OTHER_ONLINE_FORM', '온라인 신청 (접수 사이트)'],
    ['FILE', '이메일·우편·방문 제출'],
    ['UNKNOWN', null],
  ] as const)('%s 경로의 이름을 돌려준다', (type, label) => {
    expect(supportProgramApplicationRouteLabel({ method: null, url: null, type })).toBe(label)
  })
})

describe('groupSupportProgramConditions', () => {
  const evidence = { field: 'TARGET_DESCRIPTION' as const, quote: '원문', attachmentName: null }
  const values = { regions: null, minYears: null, maxYears: null, minAge: null, maxAge: null }
  it('필수 · 제외 · 우대 순서로 묶고 빈 묶음은 뺀다', () => {
    const groups = groupSupportProgramConditions([
      { kind: 'PREFERRED', category: 'CERTIFICATION', text: '벤처기업 우대', values, evidence },
      { kind: 'REQUIRED', category: 'BUSINESS_AGE', text: '창업 7년 이내', values: { ...values, maxYears: 7 }, evidence },
    ])
    expect(groups.map((group) => [group.title, group.entries.map((entry) => [entry.index, entry.condition.text])])).toEqual([
      ['신청 조건', [[1, '창업 7년 이내']]],
      ['우대 사항', [[0, '벤처기업 우대']]],
    ])
  })
})

describe('supportProgramAnalysisFacts', () => {
  it('지원 규모와 지원 형태 두 개까지 한 줄로 만든다', () => {
    expect(supportProgramAnalysisFacts({ summaryLine: null, supportAmountText: '최대 5천만원', maxAmountKrw: 50_000_000, supportTypes: ['GRANT', 'CONSULTING', 'SPACE'] }))
      .toEqual(['최대 5천만원', '사업화 자금', '컨설팅·멘토링'])
    expect(supportProgramAnalysisFacts(null)).toEqual([])
  })
})

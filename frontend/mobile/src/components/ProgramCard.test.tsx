import { render, screen } from '@testing-library/react-native'
import type { SupportProgram } from '@govbiz/shared/domain/entities/SupportProgram'
import { ProgramCard } from './ProgramCard'

const axis = { status: 'MATCH' as const, explanation: '원문 조건과 맞습니다.', evidence: [] }
const program: SupportProgram = {
  sourceCode: 'BIZINFO', id: 'P/1', title: '테스트 공고', organization: '기관', summary: '사업 내용', categories: [], regions: [],
  targetDescription: '중소기업', applicationPeriod: '상시', applicationStartDate: null, applicationEndDate: null, status: 'OPEN',
  sourceName: '기업마당', sourceUrl: 'https://www.bizinfo.go.kr/program', matchedReasons: [], recommendationScore: null, eligibilityReview: null, analysisSummary: null,
}

test.each([
  ['matched search result', { recommendationScore: 80, eligibilityReview: { status: 'MATCH', basis: 'OFFICIAL_API_TEXT', target: axis, region: axis } }, '조건 확인'],
  ['search result that needs review', { recommendationScore: 60, eligibilityReview: { status: 'REVIEW_REQUIRED', basis: 'OFFICIAL_API_TEXT', target: axis, region: { ...axis, status: 'UNKNOWN' } } }, '확인 필요'],
  ['ranked result without a review', { recommendationScore: 60 }, '확인 필요'],
] as const)('%s shows the same eligibility badge as the web card', (_, patch, label) => {
  render(<ProgramCard program={{ ...program, ...patch } as SupportProgram} onOpen={jest.fn()} />)
  expect(screen.getByText(label)).toBeTruthy()
})

test('unranked catalog cards show no eligibility badge', () => {
  render(<ProgramCard program={program} onOpen={jest.fn()} />)
  expect(screen.queryByText('조건 확인')).toBeNull()
  expect(screen.queryByText('확인 필요')).toBeNull()
})

test('analyzed cards add the support amount and type line', () => {
  render(<ProgramCard program={{ ...program, analysisSummary: { summaryLine: null, supportAmountText: '최대 5천만원', maxAmountKrw: 50000000, supportTypes: ['GRANT'] } }} onOpen={jest.fn()} />)
  expect(screen.getByText('최대 5천만원 · 사업화 자금')).toBeTruthy()
})

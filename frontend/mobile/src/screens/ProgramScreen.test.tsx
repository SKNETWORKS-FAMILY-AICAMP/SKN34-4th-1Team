import { fireEvent, render, screen, waitFor } from '@testing-library/react-native'
import { ApiError, apiRequest, programClient } from '../api/client'
import { useAuth } from '../auth/session'
import { ProgramScreen } from './ProgramScreen'
import { preparation, preparationDetail, programDetail } from '../test/preparationFixtures'

jest.mock('expo-router', () => ({ useFocusEffect: (effect: () => void) => { const React = jest.requireActual<typeof import('react')>('react'); React.useEffect(effect, [effect]) } }))
jest.mock('../auth/session', () => ({ useAuth: jest.fn() }))
jest.mock('../api/client', () => ({ ...jest.requireActual('../api/client'), apiRequest: jest.fn(), programClient: jest.fn() }))
const answer = jest.fn()
const identity = { sourceCode: 'BIZINFO', sourceProgramId: 'P/123' }
const invalidateSession = jest.fn()
function respond(path: string) {
  if (path.includes('/saved-programs/status?')) return Promise.resolve({ saved: true })
  if (path.startsWith('/api/v1/application-preparations?')) return Promise.resolve({ items: [preparation], nextBeforeId: null })
  if (path.startsWith('/api/v1/combination-reviews?')) return Promise.resolve({ items: [], nextBeforeId: null })
  if (path.startsWith('/api/v1/me/support-programs/condition-check?')) return Promise.resolve({
    status: 'CHECKED', analyzedAt: '2026-10-01T10:00:00', referenceDate: '2026-10-01', profile: { region: '서울', foundedYear: 2023 },
    overall: 'MET', conditions: [{ index: 0, result: 'MET', reason: 'BUSINESS_AGE_WITHIN' }],
  })
  throw new Error(`Unexpected request ${path}`)
}
beforeEach(() => {
  jest.mocked(useAuth).mockReturnValue({ status: 'signedIn', session: { accessToken: 'owner' }, invalidateSession } as unknown as ReturnType<typeof useAuth>)
  jest.mocked(apiRequest).mockReset().mockImplementation(respond)
  answer.mockReset().mockResolvedValue({ answerStatus: 'ANSWERED', answer: '공고 원문 답변', citations: [] })
  jest.mocked(programClient).mockReturnValue({ getDetail: jest.fn().mockResolvedValue(programDetail), answerEvidenceQuestion: answer } as unknown as ReturnType<typeof programClient>)
})
test('saved program preparation changes only the selected document with its stored progress revision', async () => {
  let updated = false
  jest.mocked(apiRequest).mockImplementation((path, options) => {
    if (options?.method === 'PUT') { updated = true; return Promise.resolve({ ...preparationDetail, progressStage: 'APPLIED', progressRevision: 2 }) }
    if (updated && path.startsWith('/api/v1/application-preparations?')) return Promise.resolve({ items: [{ ...preparation, progressStage: 'APPLIED', progressRevision: 2 }], nextBeforeId: null })
    return respond(path)
  })
  render(<ProgramScreen identity={identity} onLogin={jest.fn()} />)
  await screen.findByText('담은 공고라 보여요')
  await screen.findByLabelText('진행 단계 바꾸기')
  fireEvent.press(screen.getByLabelText('진행 단계 바꾸기'))
  fireEvent.press(screen.getByLabelText('지원 완료'))
  fireEvent.press(screen.getByText('저장'))
  await waitFor(() => expect(apiRequest).toHaveBeenCalledWith('/api/v1/application-preparations/9/progress-stage', expect.objectContaining({
    method: 'PUT', accessToken: 'owner', body: { expectedProgressRevision: 1, progressStage: 'APPLIED' },
  })))
  await waitFor(() => expect(screen.queryByText('진행 단계 바꾸기')).toBeNull())
  await waitFor(() => expect(screen.getAllByText('지원 완료').length).toBeGreaterThan(0))
})
test('a progress conflict stays visible instead of reporting a successful stage update', async () => {
  jest.mocked(apiRequest).mockImplementation((path, options) => options?.method === 'PUT' ? Promise.reject(new ApiError(409, 'conflict')) : respond(path))
  render(<ProgramScreen identity={identity} onLogin={jest.fn()} />)
  await screen.findByLabelText('진행 단계 바꾸기')
  fireEvent.press(screen.getByLabelText('진행 단계 바꾸기'))
  fireEvent.press(screen.getByLabelText('지원 완료'))
  fireEvent.press(screen.getByText('저장'))
  await screen.findByText(/다른 화면에서 진행 단계가 변경/)
  expect(screen.getByText('진행 단계 바꾸기')).toBeTruthy()
})
test('question entry preserves the existing explicit AI request and more details', async () => {
  render(<ProgramScreen identity={identity} onLogin={jest.fn()} />)
  await screen.findByText('테스트 지원사업')
  expect(answer).not.toHaveBeenCalled()
  fireEvent.press(screen.getByText('더 보기'))
  expect(screen.getByText('중소기업')).toBeTruthy()
  fireEvent.press(screen.getByText('원문에 질문하기'))
  expect(answer).not.toHaveBeenCalled()
  fireEvent.changeText(screen.getByLabelText('공고에 대해 궁금한 점'), '신청 서류는?')
  fireEvent.press(screen.getByText('원문에서 답변 찾기'))
  await screen.findByText('공고 원문 답변')
  expect(answer).toHaveBeenCalledWith({ ...identity, question: '신청 서류는?' }, expect.anything())
})
test('details show the official application route and split K-Startup exclusions', async () => {
  jest.mocked(programClient).mockReturnValue({ getDetail: jest.fn().mockResolvedValue({
    ...programDetail, sourceCode: 'KSTARTUP', targetDescription: '지원 대상: 창업 3년 이내 기업\n제외 대상: 휴·폐업 중인 기업',
    applicationRoute: { method: '구글 설문으로 신청', url: 'https://forms.gle/abc', type: 'GOOGLE_FORMS' },
  }), answerEvidenceQuestion: answer } as unknown as ReturnType<typeof programClient>)
  render(<ProgramScreen identity={identity} onLogin={jest.fn()} />)
  await screen.findByText('테스트 지원사업')
  expect(screen.getByText('온라인 신청 (구글 설문)')).toBeTruthy()
  expect(screen.queryByText('지원 규모')).toBeNull()
  fireEvent.press(screen.getByText('더 보기'))
  expect(screen.getByText('창업 3년 이내 기업')).toBeTruthy()
  expect(screen.getByText('휴·폐업 중인 기업')).toBeTruthy()
  expect(screen.getByText('구글 설문으로 신청')).toBeTruthy()
  expect(screen.getByText('구글 설문 신청서 열기')).toBeTruthy()
})
test('completed analyses show the AI summary, amount and quoted conditions; failures stay visible', async () => {
  const values = { regions: null, minYears: null, maxYears: 7, minAge: null, maxAge: null }
  const getDetail = jest.fn().mockResolvedValueOnce({ ...programDetail, analysis: {
    status: 'COMPLETED', analyzedAt: '2026-10-01T10:00:00', summaryLine: '창업기업 사업화 자금 지원', supportTypes: ['GRANT'],
    supportAmount: { text: '최대 5천만원', maxAmountKrw: 50000000, evidence: { field: 'SUMMARY', quote: '최대 5천만원', attachmentName: null } }, selectionScale: null,
    conditions: [{ kind: 'REQUIRED', category: 'BUSINESS_AGE', text: '창업 7년 이내', values, evidence: { field: 'TARGET_DESCRIPTION', quote: '창업 7년 이내 기업', attachmentName: null } }],
    contact: null,
    schedule: [{ label: '발표평가', date: '2026-11-03', text: '11월 3일', evidence: { field: 'ATTACHMENT', quote: '11월 3일 발표평가', attachmentName: '공고문.pdf' } }],
    requiredDocuments: [{ name: '사업계획서', requirement: 'REQUIRED', note: null, evidence: { field: 'ATTACHMENT', quote: '사업계획서 1부', attachmentName: '공고문.pdf' } }],
    selectionSteps: [], evaluationCriteria: [], sourceAttachmentNames: ['공고문.pdf'],
  } }).mockResolvedValueOnce({ ...programDetail, analysis: { status: 'FAILED', analyzedAt: null, summaryLine: null, supportTypes: [], supportAmount: null, selectionScale: null, conditions: [], contact: null } })
  jest.mocked(programClient).mockReturnValue({ getDetail, answerEvidenceQuestion: answer } as unknown as ReturnType<typeof programClient>)
  const view = render(<ProgramScreen identity={identity} onLogin={jest.fn()} />)
  await screen.findByText(/창업기업 사업화 자금 지원/)
  expect(screen.getByText('최대 5천만원')).toBeTruthy()
  expect(screen.getByText('사업화 자금')).toBeTruthy()
  expect(screen.getByText('[업력] 창업 7년 이내')).toBeTruthy()
  // 원문 인용은 접혀 있고 항목 옆 ! 아이콘을 눌러야 펼쳐집니다.
  expect(screen.queryByText('원문(지원 대상): “창업 7년 이내 기업”')).toBeNull()
  const hints = screen.getAllByLabelText('원문 근거 보기')
  fireEvent.press(hints[0])
  expect(screen.getByText('원문(지원 대상): “창업 7년 이내 기업”')).toBeTruthy()
  expect(await screen.findByText('충족 · 설립연도(2023년) 기준으로 업력 조건에 들어요')).toBeTruthy()
  expect(screen.getByText('조건 충족')).toBeTruthy()
  expect(apiRequest).toHaveBeenCalledWith(expect.stringContaining('/api/v1/me/support-programs/condition-check?'), expect.objectContaining({ accessToken: 'owner' }))
  expect(screen.getByText('2026-11-03 · 발표평가 · 11월 3일')).toBeTruthy()
  expect(screen.getByText('[필수] 사업계획서')).toBeTruthy()
  fireEvent.press(screen.getAllByLabelText('원문 근거 보기')[2])
  expect(screen.getByText('원문(첨부파일 공고문.pdf): “사업계획서 1부”')).toBeTruthy()
  expect(screen.getByText(/AI 정리 · 첨부 1개/)).toBeTruthy()
  view.unmount()
  render(<ProgramScreen identity={identity} onLogin={jest.fn()} />)
  await screen.findByText(/AI가 조건을 정리하지 못했어요/)
  expect(screen.queryByText('지원 규모')).toBeNull()
})
test('guests do not request a private preparation workspace', async () => {
  jest.mocked(useAuth).mockReturnValue({ status: 'signedOut', session: null, invalidateSession } as unknown as ReturnType<typeof useAuth>)
  const login = jest.fn()
  render(<ProgramScreen identity={identity} onLogin={login} />)
  await screen.findByText('테스트 지원사업')
  expect(apiRequest).not.toHaveBeenCalled()
  expect(screen.queryByText('담은 공고라 보여요')).toBeNull()
  fireEvent.press(screen.getByLabelText('로그인하고 관심 공고 저장'))
  expect(login).toHaveBeenCalledTimes(1)
})

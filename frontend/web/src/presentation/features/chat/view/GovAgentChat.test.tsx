// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { Provider } from 'react-redux'
import { MemoryRouter } from 'react-router'
import { afterEach, expect, it, vi } from 'vitest'
import { createAppStore } from '../../../../app/store'
import { appContainer } from '../../../../app/appContainer'
import { supportProgramClient } from '../../../../data/api/supportProgramClient'
import { emptyConversationContext, readyConversationProposal } from '../../../../data/fixtures/supportProgramConversation'
import { supportPrograms } from '../../../../data/fixtures/supportPrograms'
import { completeSearchResult } from '../../../../data/fixtures/supportProgramSearchResult'
import { signedIn } from '../../../shared/auth/state/authSlice'
import { RouteDocumentTitle } from '../../../shared/routes/RouteDocumentTitle'
import { SupportProgramSearchPage } from '../../support-program-catalog/view/SupportProgramSearchPage'

vi.mock('../hooks/useSupportProgramSearchReadiness', () => ({ useSupportProgramSearchReadiness: () => ({
  canSearch: true, isError: false, isInitialLoading: false, isRefreshing: false, refetch: vi.fn(), data: { searchState: 'SEARCHABLE' },
}) }))
vi.mock('../viewmodel/useSearchResultInterests', async (importOriginal) => ({
  ...await importOriginal<typeof import('../viewmodel/useSearchResultInterests')>(), useSearchResultInterests: () => null,
}))
vi.mock('../../../shared/plan-usage/usePlanUsage', () => ({ usePlanUsage: () => ({ usage: null, reload: vi.fn() }) }))
afterEach(() => { cleanup(); vi.restoreAllMocks() })

function setup(role: 'ADMIN' | 'USER') {
  const store = createAppStore()
  store.dispatch(signedIn({ email: 'test@govbiz.local', role, tier: role === 'ADMIN' ? 'ADMIN' : 'MEMBER',
    emailVerified: true, hasPassword: true, accountType: null, onboarded: true, company: null }))
  render(<Provider store={store}><MemoryRouter initialEntries={['/app/chat']}>
    <RouteDocumentTitle /><SupportProgramSearchPage layout="workspace" />
  </MemoryRouter></Provider>)
  return store
}

it('관리자는 검색 확인 후 같은 입력창에서 선택 공고의 근거를 읽는다', async () => {
  const gov = vi.spyOn(supportProgramClient, 'sendGovAgentMessage').mockResolvedValue({ outcome: 'SEARCH',
    interpretation: readyConversationProposal({ ...emptyConversationContext, query: 'AI 창업' }) })
  const search = vi.spyOn(appContainer.resolve('searchSupportProgramsUseCase'), 'execute')
    .mockResolvedValue(completeSearchResult({ query: 'AI 창업', programs: [supportPrograms[0]] }))
  setup('ADMIN')
  expect(screen.getByRole('tab', { name: 'Gov 에이전트' })).toBeTruthy()
  expect(document.title).toBe('Gov 에이전트 · GovBiz')
  const input = screen.getByRole('textbox', { name: '지원사업 검색어' })
  fireEvent.change(input, { target: { value: 'AI 창업지원 찾아줘' } })
  await act(async () => fireEvent.click(screen.getByRole('button', { name: '검색 전송' })))
  expect(search).not.toHaveBeenCalled()
  await act(async () => fireEvent.click(screen.getByRole('button', { name: '이 조건으로 검색' })))
  fireEvent.click(screen.getByRole('button', { name: '이 공고 질문' }))
  expect(screen.getByText('질문할 공고:')).toBeTruthy()
  const program = supportPrograms[0]
  gov.mockResolvedValue({ outcome: 'EVIDENCE', program: { sourceCode: program.sourceCode, sourceProgramId: program.id },
    evidence: { answer: '누리집에서 신청하세요.', answerStatus: 'ANSWERED', citations: [{ excerpt: '누리집 온라인 신청',
      sourceUrl: program.sourceUrl, sourceLabel: '기업마당 상세 본문', chunkOrder: 0 }] } })
  fireEvent.change(input, { target: { value: '신청 방법은?' } })
  await act(async () => fireEvent.click(screen.getByRole('button', { name: '검색 전송' })))
  expect(screen.getByText('누리집에서 신청하세요.')).toBeTruthy()
  expect(screen.getByRole('link', { name: /근거 1.*기업마당/ }).getAttribute('href')).toBe(program.sourceUrl)
  expect(screen.getByRole('textbox', { name: '지원사업 검색어' })).toBe(input)
  expect(search).toHaveBeenCalledOnce()
})

it('일반 회원은 AI 대화 검색 이름을 유지한다', () => {
  setup('USER')
  expect(screen.getByRole('tab', { name: 'AI 대화 검색' })).toBeTruthy()
  expect(screen.queryByRole('tab', { name: 'Gov 에이전트' })).toBeNull()
  expect(screen.queryByText(/질문할 공고:/)).toBeNull()
  expect(document.title).toBe('AI 대화 검색 · GovBiz')
})

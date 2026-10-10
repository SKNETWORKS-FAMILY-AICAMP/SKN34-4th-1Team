// @vitest-environment jsdom
import { createElement, type ComponentType, type PropsWithChildren } from 'react'
import { act, cleanup, renderHook } from '@testing-library/react'
import { Provider } from 'react-redux'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createAppStore } from '../../../../app/store'
import { supportProgramClient } from '../../../../data/api/supportProgramClient'
import { supportPrograms } from '../../../../data/fixtures/supportPrograms'
import { completeSearchResult } from '../../../../data/fixtures/supportProgramSearchResult'
import { emptyConversationContext, readyConversationProposal } from '../../../../data/fixtures/supportProgramConversation'
import { chatConversationSnapshotSchema } from '../../../../data/models/ChatConversationDto'
import type { GovAgentResult } from '@govbiz/shared/domain/entities/GovAgent'
import { signedIn, signedOut } from '../../../shared/auth/state/authSlice'
import { conversationHistoryOpened, createChatConversationSnapshot } from '../state/chatSlice'
import { useSupportProgramChat } from './useSupportProgramChat'

afterEach(() => { cleanup(); vi.restoreAllMocks() })
const account = { email: 'admin@test.local', role: 'ADMIN' as const, tier: 'ADMIN' as const,
  emailVerified: true, hasPassword: true, accountType: null, onboarded: true, company: null }
const program = { sourceCode: supportPrograms[0].sourceCode, sourceProgramId: supportPrograms[0].id, title: supportPrograms[0].title }
const proposal = readyConversationProposal({ ...emptyConversationContext, query: 'AI 사업' })
const evidence = { answer: '온라인으로 신청하세요.', answerStatus: 'ANSWERED' as const,
  citations: [{ excerpt: '온라인 신청', sourceUrl: supportPrograms[0].sourceUrl, sourceLabel: '기업마당 상세 본문', chunkOrder: 0 }] }

function setup(admin = true) {
  const store = createAppStore()
  store.dispatch(signedIn(admin ? account : { ...account, role: 'USER', tier: 'MEMBER' }))
  const search = vi.fn().mockResolvedValue(completeSearchResult({ query: 'AI 사업', programs: [supportPrograms[0]] }))
  const interpret = vi.fn().mockResolvedValue(proposal)
  const gov = vi.spyOn(supportProgramClient, 'sendGovAgentMessage').mockResolvedValue({ outcome: 'SEARCH', interpretation: proposal })
  const StoreProvider = Provider as unknown as ComponentType<PropsWithChildren<{ store: typeof store }>>
  const hook = renderHook(() => useSupportProgramChat({ execute: search }, { execute: interpret }), {
    wrapper: ({ children }: PropsWithChildren) => createElement(StoreProvider, { store }, children),
  })
  return { ...hook, store, search, interpret, gov }
}

async function searchAndSelect(hook: ReturnType<typeof setup>) {
  act(() => hook.result.current.updateDraft('AI 사업 찾아줘'))
  await act(async () => hook.result.current.submitMessage())
  expect(hook.search).not.toHaveBeenCalled()
  await act(async () => hook.result.current.confirmInterpretation())
  act(() => hook.result.current.selectGovProgram(program))
}

describe('관리자 Gov 에이전트 대화', () => {
  it('searches only after confirmation, then asks evidence in the same saved conversation', async () => {
    const hook = setup()
    await searchAndSelect(hook)
    expect(hook.search).toHaveBeenCalledOnce()
    expect(hook.interpret).not.toHaveBeenCalled()
    hook.gov.mockResolvedValue({ outcome: 'EVIDENCE', program, evidence })
    act(() => hook.result.current.updateDraft('신청 방법 알려줘'))
    await act(async () => hook.result.current.submitMessage())
    expect(hook.gov.mock.calls.at(-1)?.[0].selectedProgram).toEqual({ sourceCode: program.sourceCode, sourceProgramId: program.sourceProgramId })
    expect(hook.result.current.messages.at(-1)?.govEvidence?.answer).toEqual(evidence)
    const snapshot = chatConversationSnapshotSchema.parse(createChatConversationSnapshot(hook.store.getState().chat))
    act(() => hook.result.current.startNewConversation())
    expect(hook.result.current.govProgram).toBeNull()
    act(() => hook.store.dispatch(conversationHistoryOpened({ accountEmail: account.email, snapshot })))
    expect(hook.result.current.govProgram).toEqual(program)
    expect(hook.result.current.messages.at(-1)?.govEvidence?.answer).toEqual(evidence)
    expect(hook.gov).toHaveBeenCalledTimes(2)
  })

  it('ignores late responses after reset and clears selection on sign out', async () => {
    const hook = setup()
    await searchAndSelect(hook)
    let resolve!: (value: GovAgentResult) => void
    hook.gov.mockReturnValue(new Promise((done) => { resolve = done }))
    act(() => hook.result.current.updateDraft('신청 방법은?'))
    let request!: Promise<void>
    act(() => { request = hook.result.current.submitMessage() })
    act(() => hook.result.current.startNewConversation())
    await act(async () => { resolve({ outcome: 'APPLICATION', program, message: '신청 준비를 시작해 주세요.' }); await request })
    expect(hook.result.current.messages).toHaveLength(1)
    expect(hook.result.current.govProgram).toBeNull()
    act(() => hook.store.dispatch(signedOut()))
    expect(hook.result.current.isGovAgent).toBe(false)
  })

  it('saves and restores application requests without creating a document or rerouting the request', async () => {
    const hook = setup()
    await searchAndSelect(hook)
    const application = { program, message: '양식을 골라 신청 준비를 시작해 주세요.' }
    hook.gov.mockResolvedValue({ outcome: 'APPLICATION', ...application })
    act(() => hook.result.current.updateDraft('이 공고 신청 준비해줘'))
    await act(async () => hook.result.current.submitMessage())
    expect(hook.result.current.messages.at(-1)?.govApplication).toEqual(application)
    const snapshot = chatConversationSnapshotSchema.parse(createChatConversationSnapshot(hook.store.getState().chat))
    act(() => hook.result.current.startNewConversation())
    act(() => hook.store.dispatch(conversationHistoryOpened({ accountEmail: account.email, snapshot })))
    expect(hook.result.current.govProgram).toEqual(program)
    expect(hook.result.current.messages.at(-1)?.govApplication).toEqual(application)
    expect(hook.search).toHaveBeenCalledOnce()
    expect(hook.gov).toHaveBeenCalledTimes(2)
  })

  it('members keep the existing conversation API', async () => {
    const hook = setup(false)
    act(() => hook.result.current.updateDraft('서울 사업 찾아줘'))
    await act(async () => hook.result.current.submitMessage())
    expect(hook.interpret).toHaveBeenCalledOnce()
    expect(hook.gov).not.toHaveBeenCalled()
    act(() => hook.result.current.selectGovProgram(program))
    expect(hook.result.current.govProgram).toBeNull()
  })

  it('requests selection without inventing a target and does not launch a search', async () => {
    const hook = setup()
    hook.gov.mockResolvedValue({ outcome: 'NEEDS_PROGRAM', message: '공고를 선택해 주세요.' })
    act(() => hook.result.current.updateDraft('이 공고 지원 대상은?'))
    await act(async () => hook.result.current.submitMessage())
    expect(hook.result.current.messages.at(-1)?.text).toBe('공고를 선택해 주세요.')
    expect(hook.search).not.toHaveBeenCalled()
    expect(hook.result.current.govProgram).toBeNull()
  })
})

// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { Provider } from 'react-redux'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { appContainer } from '../../../../app/appContainer'
import { createAppStore } from '../../../../app/store'
import { supportProgramDetails, supportPrograms } from '../../../../data/fixtures/supportPrograms'
import type { Account } from '../../../../domain/entities/Account'
import { SupportProgramDetailPage } from './SupportProgramDetailPage'
import { SupportProgramEvidenceQuestionPage } from './SupportProgramEvidenceQuestionPage'
import { sessionRestored } from '../../../shared/auth/state/authSlice'

afterEach(() => { cleanup(); vi.restoreAllMocks() })

describe('상세 오류 복구와 검색 화면 복귀', () => {
  it('일시 실패 후 같은 화면에서 수동 재시도하고 근거 질문은 자동 호출하지 않는다', async () => {
    const detail = vi.spyOn(appContainer.resolve('getSupportProgramDetailUseCase'), 'execute')
      .mockRejectedValueOnce(new Error('private failure')).mockResolvedValueOnce(supportProgramDetails[0])
    const question = vi.spyOn(appContainer.resolve('askSupportProgramEvidenceQuestionUseCase'), 'execute')
      .mockResolvedValue({ outcome: 'unavailable' })
    renderDetail()
    fireEvent.click(await screen.findByRole('button', { name: '다시 시도' }))
    await screen.findByRole('heading', { name: supportPrograms[0].title })
    expect(detail).toHaveBeenCalledTimes(2)
    expect(question).not.toHaveBeenCalled()
    // 비로그인은 로그인 뒤 신청 문서 작성으로 이어지고, 관심 공고 저장도 로그인 뒤 이 공고로 돌아옵니다.
    const preparationPath = `/app/application-preparations/new?${new URLSearchParams({ sourceCode: supportPrograms[0].sourceCode, sourceProgramId: supportPrograms[0].id })}`
    expect(screen.getByRole('link', { name: '로그인하고 이 공고로 신청 문서 작성' }).getAttribute('href')).toBe(`/login?next=${encodeURIComponent(preparationPath)}`)
    expect(screen.queryByRole('link', { name: '이 공고로 신청 문서 작성' })).toBeNull()
    const detailPath = `/support-programs/detail?${new URLSearchParams({ sourceCode: supportPrograms[0].sourceCode, sourceProgramId: supportPrograms[0].id })}`
    expect(screen.getByRole('link', { name: '로그인하고 관심 공고에 담기' }).getAttribute('href')).toBe(`/login?next=${encodeURIComponent(detailPath)}`)
    expect(screen.queryByRole('button', { name: /관심 공고 저장/ })).toBeNull()
    expect(screen.queryByText('지원사업 상세')).toBeNull()
    expect(screen.queryByText('private failure')).toBeNull()
  })

  it('상세·질문을 왕복해도 원래 작업 채팅으로 복귀한다', async () => {
    vi.spyOn(appContainer.resolve('getSupportProgramDetailUseCase'), 'execute').mockResolvedValue(supportProgramDetails[0])
    // 원문 질문은 회원 기능이라 로그인한 상태로 왕복합니다.
    vi.spyOn(appContainer.resolve('checkSavedSupportProgramUseCase'), 'execute').mockResolvedValue(false)
    renderDetail({ searchReturnTo: '/app/chat' }, undefined, memberAccount)
    expect(screen.getByRole('link', { name: '검색 결과로 돌아가기' }).getAttribute('href')).toBe('/app/chat')
    fireEvent.click(await screen.findByRole('button', { name: '원문에 질문하기' }))
    expect(screen.getByRole('textbox', { name: '공고 원문에 질문하기' })).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '질문 패널 닫기' }))
    await screen.findByRole('heading', { name: supportPrograms[0].title })
    expect(screen.getByRole('link', { name: '검색 결과로 돌아가기' }).getAttribute('href')).toBe('/app/chat')
  })

  it('질문 패널은 상세를 떠나지 않고 답과 근거를 쌓으며, ?ask=1로 열어 둔 채 새로고침해도 열린다', async () => {
    vi.spyOn(appContainer.resolve('getSupportProgramDetailUseCase'), 'execute').mockResolvedValue(supportProgramDetails[0])
    vi.spyOn(appContainer.resolve('checkSavedSupportProgramUseCase'), 'execute').mockResolvedValue(false)
    const ask = vi.spyOn(appContainer.resolve('askSupportProgramEvidenceQuestionUseCase'), 'execute')
      .mockResolvedValueOnce({ outcome: 'answer', answer: { answer: '중소기업이 대상입니다.', answerStatus: 'ANSWERED', citations: [{ excerpt: '지원 대상: 중소기업', sourceUrl: 'https://example.com/1', chunkOrder: 1 }] } })
      .mockResolvedValueOnce({ outcome: 'answer', answer: { answer: '', answerStatus: 'INSUFFICIENT_EVIDENCE', citations: [] } })
    renderDetail({ searchReturnTo: '/app/chat' }, `?${new URLSearchParams({ sourceCode: supportPrograms[0].sourceCode, sourceProgramId: supportPrograms[0].id, ask: '1' })}`, memberAccount)

    await screen.findByRole('heading', { name: supportPrograms[0].title })
    const panel = screen.getByRole('region', { name: '원문에 질문하기' })
    // 처음에는 본문에서 답할 수 있는 예시 키워드가 있고, 누르면 입력에 질문 문장이 채워집니다.
    fireEvent.click(within(panel).getByRole('button', { name: '지원 대상' }))
    expect((within(panel).getByRole('textbox', { name: '공고 원문에 질문하기' }) as HTMLTextAreaElement).value).toBe('지원 대상이 어떻게 되나요?')
    fireEvent.click(within(panel).getByRole('button', { name: '질문 보내기' }))
    expect(await within(panel).findByText('중소기업이 대상입니다.')).toBeTruthy()
    expect(within(panel).getByRole('link', { name: '근거 1 원문 보기 ↗' }).getAttribute('href')).toBe('https://example.com/1')
    expect(ask).toHaveBeenCalledWith({ sourceCode: supportPrograms[0].sourceCode, sourceProgramId: supportPrograms[0].id, question: '지원 대상이 어떻게 되나요?' }, expect.any(AbortSignal))
    // 답이 오면 입력이 비고 자주 묻는 질문은 사라집니다. 두 번째 질문은 첫 답 아래에 쌓입니다.
    expect((within(panel).getByRole('textbox', { name: '공고 원문에 질문하기' }) as HTMLTextAreaElement).value).toBe('')
    expect(within(panel).queryByRole('button', { name: '지원 대상' })).toBeNull()
    fireEvent.change(within(panel).getByRole('textbox', { name: '공고 원문에 질문하기' }), { target: { value: '제출 서류는?' } })
    fireEvent.click(within(panel).getByRole('button', { name: '질문 보내기' }))
    expect(await within(panel).findByText(/충분한 근거를 찾지 못했습니다/)).toBeTruthy()
    expect(within(panel).getByText('지원 대상이 어떻게 되나요?')).toBeTruthy()
    expect(within(panel).getByText('제출 서류는?')).toBeTruthy()
    expect(within(panel).getByText('중소기업이 대상입니다.')).toBeTruthy()
    // 본문은 그대로 옆에 있습니다.
    expect(screen.getByRole('note')).toBeTruthy()
  })

  it('과기정통부 공고는 근거 질문 없이 신청 문서 작성 도우미로 연결한다', async () => {
    const program = { ...supportProgramDetails[0], sourceCode: 'MSIT', id: '3186573', sourceName: '과학기술정보통신부', evidenceQuestionSupported: false }
    vi.spyOn(appContainer.resolve('getSupportProgramDetailUseCase'), 'execute').mockResolvedValue(program)
    renderDetail(null, `?${new URLSearchParams({ sourceCode: program.sourceCode, sourceProgramId: program.id })}`)

    await screen.findByRole('heading', { name: program.title })
    expect(screen.queryByRole('link', { name: '원문에 질문하기' })).toBeNull()
    expect(screen.getByRole('link', { name: '로그인하고 이 공고로 신청 문서 작성' }).getAttribute('href')).toBe(
      `/login?next=${encodeURIComponent(`/app/application-preparations/new?${new URLSearchParams({ sourceCode: program.sourceCode, sourceProgramId: program.id })}`)}`,
    )
  })

  it.each([
    ['KSTARTUP', '177911'],
    ['CNTRADE_NOTICE', '3862'],
  ])('%s 공고도 신청 문서 작성 도우미로 연결한다', async (sourceCode, id) => {
    const program = { ...supportProgramDetails[0], sourceCode, id, evidenceQuestionSupported: false }
    vi.spyOn(appContainer.resolve('getSupportProgramDetailUseCase'), 'execute').mockResolvedValue(program)
    renderDetail(null, `?${new URLSearchParams({ sourceCode, sourceProgramId: id })}`)

    await screen.findByRole('heading', { name: program.title })
    const preparationPath = `/app/application-preparations/new?${new URLSearchParams({ sourceCode, sourceProgramId: id })}`
    expect(screen.getByRole('link', { name: '로그인하고 이 공고로 신청 문서 작성' }).getAttribute('href')).toBe(
      `/login?next=${encodeURIComponent(preparationPath)}`,
    )
  })

  it.each([null, {}, { searchReturnTo: 'https://example.com' }, { searchReturnTo: '//example.com' }, { searchReturnTo: '/admin' }])(
    '직접 진입 또는 허용하지 않는 복귀 상태 %j는 첫 검색 화면으로 돌아간다', async (state) => {
      vi.spyOn(appContainer.resolve('getSupportProgramDetailUseCase'), 'execute').mockResolvedValue(null)
      renderDetail(state)
      await screen.findByRole('heading', { name: '공고 정보를 찾을 수 없습니다' })
      expect(screen.getByRole('link', { name: '검색 결과로 돌아가기' }).getAttribute('href')).toBe('/')
      expect(screen.queryByRole('button', { name: '다시 시도' })).toBeNull()
    },
  )

  it('이동 상태가 없으면 주소의 back으로 필터 검색 화면에 돌아가고, 허용하지 않는 back은 무시한다', async () => {
    vi.spyOn(appContainer.resolve('getSupportProgramDetailUseCase'), 'execute').mockResolvedValue(supportProgramDetails[0])
    const identity = new URLSearchParams({ sourceCode: supportPrograms[0].sourceCode, sourceProgramId: supportPrograms[0].id })
    renderDetail(null, `?${identity}&back=${encodeURIComponent('/?mode=filter&keyword=AI')}`)
    await screen.findByRole('heading', { name: supportPrograms[0].title })
    expect(screen.getByRole('link', { name: '검색 결과로 돌아가기' }).getAttribute('href')).toBe('/?mode=filter&keyword=AI')

    cleanup()
    renderDetail(null, `?${identity}&back=${encodeURIComponent('https://example.com')}`)
    await screen.findByRole('heading', { name: supportPrograms[0].title })
    expect(screen.getByRole('link', { name: '검색 결과로 돌아가기' }).getAttribute('href')).toBe('/')
  })

  it('잘못된 식별자는 조회하지 않고 원래 검색 화면의 복귀 링크를 유지한다', () => {
    const detail = vi.spyOn(appContainer.resolve('getSupportProgramDetailUseCase'), 'execute').mockResolvedValue(null)
    renderDetail({ searchReturnTo: '/app/chat' }, '?sourceCode=BIZINFO')
    expect(screen.getByRole('heading', { name: '공고 정보를 찾을 수 없습니다' })).toBeTruthy()
    expect(screen.getByRole('link', { name: '검색 결과로 돌아가기' }).getAttribute('href')).toBe('/app/chat')
    expect(detail).not.toHaveBeenCalled()
  })
})

describe('공식 신청 경로와 제외 대상 표시', () => {
  it('신청 방법을 분류해 보여 주고 공식 신청 사이트를 새 창 링크로 연다', async () => {
    vi.spyOn(appContainer.resolve('getSupportProgramDetailUseCase'), 'execute').mockResolvedValue({
      ...supportProgramDetails[0],
      applicationRoute: { method: '온라인 접수 후 사업계획서 제출', url: 'https://apply.example.go.kr/form', type: 'OTHER_ONLINE_FORM' },
    })
    renderDetail()
    await screen.findByRole('heading', { name: supportPrograms[0].title })
    expect(screen.getByText('온라인 신청 (접수 사이트)')).toBeTruthy()
    expect(screen.getByRole('heading', { name: '신청 방법' })).toBeTruthy()
    expect(screen.getByText('온라인 접수 후 사업계획서 제출')).toBeTruthy()
    const apply = screen.getByRole('link', { name: '신청 사이트 열기 ↗' })
    expect(apply.getAttribute('href')).toBe('https://apply.example.go.kr/form')
    expect(apply.getAttribute('target')).toBe('_blank')
    expect(screen.queryByText('지원 규모')).toBeNull()
  })

  it('신청 경로를 모르면 원문 확인을 안내하고 신청 링크를 두지 않는다', async () => {
    vi.spyOn(appContainer.resolve('getSupportProgramDetailUseCase'), 'execute').mockResolvedValue(supportProgramDetails[0])
    renderDetail()
    await screen.findByRole('heading', { name: supportPrograms[0].title })
    expect(screen.getByText('공고 원문에서 확인해 주세요')).toBeTruthy()
    expect(screen.queryByRole('heading', { name: '신청 방법' })).toBeNull()
    expect(screen.queryByRole('link', { name: /신청 사이트 열기|구글 설문 신청서 열기/ })).toBeNull()
  })

  it('K-Startup 공고는 지원 대상과 제외 대상을 나눠 보여 준다', async () => {
    vi.spyOn(appContainer.resolve('getSupportProgramDetailUseCase'), 'execute').mockResolvedValue({
      ...supportProgramDetails[0], sourceCode: 'KSTARTUP', evidenceQuestionSupported: false,
      targetDescription: '지원 대상: 창업 3년 이내 기업\n제외 대상: 휴·폐업 중인 기업',
    })
    renderDetail()
    await screen.findByRole('heading', { name: supportPrograms[0].title })
    expect(screen.getByText('창업 3년 이내 기업')).toBeTruthy()
    expect(screen.getByRole('heading', { name: '제외 대상' })).toBeTruthy()
    expect(screen.getByText('휴·폐업 중인 기업')).toBeTruthy()
  })
})

describe('공고 분석 표시', () => {
  const evidence = { field: 'TARGET_DESCRIPTION' as const, quote: '창업 7년 이내 중소기업', attachmentName: null }
  const values = { regions: null, minYears: null, maxYears: null, minAge: null, maxAge: null }
  // 첨부파일 분석 항목이 없는 분석입니다.
  const noAttachmentItems = { requiredDocuments: [], selectionSteps: [], evaluationCriteria: [], schedule: [], sourceAttachmentNames: [] }

  it('분석을 마친 공고는 AI 요약 · 지원 규모 · 조건 묶음과 원문 인용을 보여 준다', async () => {
    vi.spyOn(appContainer.resolve('getSupportProgramDetailUseCase'), 'execute').mockResolvedValue({
      ...supportProgramDetails[0],
      analysis: {
        status: 'COMPLETED', analyzedAt: '2026-10-01T10:00:00', summaryLine: '창업기업에 최대 5천만원 사업화 자금',
        supportTypes: ['GRANT', 'CONSULTING'],
        supportAmount: { text: '최대 5천만원', maxAmountKrw: 50_000_000, evidence: { field: 'SUMMARY', quote: '최대 5천만원', attachmentName: null } },
        selectionScale: { text: '20개사 내외', evidence: { field: 'SUMMARY', quote: '20개사 내외', attachmentName: null } },
        conditions: [
          { kind: 'EXCLUDED', category: 'OTHER', text: '국세 체납 기업', values, evidence },
          { kind: 'REQUIRED', category: 'BUSINESS_AGE', text: '창업 7년 이내', values: { ...values, maxYears: 7 }, evidence },
        ],
        contact: { text: '창업진흥과 02-000-0000', evidence: { field: 'DETAIL_TEXT', quote: '문의 02-000-0000', attachmentName: null } },
        ...noAttachmentItems,
      },
    })
    renderDetail()
    await screen.findByRole('heading', { name: supportPrograms[0].title })
    expect(screen.getByText('창업기업에 최대 5천만원 사업화 자금')).toBeTruthy()
    expect(screen.getByText('최대 5천만원')).toBeTruthy()
    expect(screen.getByText('20개사 내외')).toBeTruthy()
    expect(screen.getByText('사업화 자금')).toBeTruthy()
    const analysis = screen.getByRole('region', { name: '공고 분석' })
    const headings = within(analysis).getAllByRole('heading', { level: 3 }).map((heading) => heading.textContent)
    expect(headings).toEqual(['신청 조건', '제외 대상', '문의처'])
    expect(within(analysis).getByText('창업 7년 이내')).toBeTruthy()
    // 원문 근거는 항목마다 ! 아이콘 도움말로 숨겨 두고, 누르면 출처와 인용을 보여 줍니다.
    const hints = within(analysis).getAllByRole('button', { name: '원문 근거 보기' })
    expect(hints).toHaveLength(3)
    expect(within(analysis).queryByRole('tooltip')).toBeNull()
    fireEvent.click(hints[2])
    const tooltip = within(analysis).getByRole('tooltip')
    expect(within(tooltip).getByText('원문 근거 · 공고 상세 본문')).toBeTruthy()
    expect(within(tooltip).getByText('문의 02-000-0000')).toBeTruthy()
    expect(hints[2].getAttribute('aria-describedby')).toBe(tooltip.id)
    fireEvent.keyDown(hints[2], { key: 'Escape' })
    expect(within(analysis).queryByRole('tooltip')).toBeNull()
    // 마우스를 올리면 보이고, 올린 채 눌러도 닫히지 않고 고정됩니다.
    fireEvent.pointerEnter(hints[0].parentElement as HTMLElement, { pointerType: 'mouse' })
    expect(within(within(analysis).getByRole('tooltip')).getByText('원문 근거 · 지원 대상')).toBeTruthy()
    fireEvent.click(hints[0])
    fireEvent.pointerLeave(hints[0].parentElement as HTMLElement, { pointerType: 'mouse' })
    expect(within(within(analysis).getByRole('tooltip')).getByText('원문 근거 · 지원 대상')).toBeTruthy()
    fireEvent.click(hints[0])
    expect(within(analysis).queryByRole('tooltip')).toBeNull()
    // 터치는 올림으로 열지 않습니다.
    fireEvent.pointerEnter(hints[1].parentElement as HTMLElement, { pointerType: 'touch' })
    expect(within(analysis).queryByRole('tooltip')).toBeNull()
  })

  it('첨부 공고문까지 분석한 공고는 일정 · 제출 서류 · 선정 절차 · 평가 기준을 첨부 인용과 함께 보여 준다', async () => {
    const attachment = { field: 'ATTACHMENT' as const, quote: '사업계획서 1부', attachmentName: '공고문.hwpx' }
    vi.spyOn(appContainer.resolve('getSupportProgramDetailUseCase'), 'execute').mockResolvedValue({
      ...supportProgramDetails[0],
      analysis: {
        status: 'COMPLETED', analyzedAt: '2026-10-01T10:00:00', summaryLine: null, supportTypes: [], supportAmount: null,
        selectionScale: null, conditions: [], contact: null,
        schedule: [{ label: '발표평가', date: null, text: '11월 중', evidence: attachment }],
        requiredDocuments: [{ name: '사업계획서', requirement: 'REQUIRED', note: null, evidence: attachment }],
        selectionSteps: [{ name: '서류평가', note: null, evidence: attachment }, { name: '발표평가', note: '온라인', evidence: attachment }],
        evaluationCriteria: [{ item: '사업성', points: 40, evidence: attachment }],
        sourceAttachmentNames: ['공고문.hwpx'],
      },
    })
    renderDetail()
    await screen.findByRole('heading', { name: supportPrograms[0].title })
    const preparation = screen.getByRole('region', { name: '신청 준비' })
    expect(within(preparation).getAllByRole('heading', { level: 3 }).map((heading) => heading.textContent))
      .toEqual(['일정', '제출 서류', '선정 절차', '평가 기준'])
    expect(within(preparation).getByText('날짜 미정')).toBeTruthy()
    expect(within(preparation).getByText(/AI 정리 · 첨부 1개/)).toBeTruthy()
    const attachmentsHint = within(preparation).getByRole('button', { name: '분석에 쓴 첨부 보기' })
    fireEvent.click(attachmentsHint)
    expect(within(within(preparation).getByRole('tooltip')).getByText('공고문.hwpx')).toBeTruthy()
    // 터치 화면에서는 같은 아이콘을 다시 눌러 닫습니다.
    fireEvent.click(attachmentsHint)
    expect(within(preparation).queryByRole('tooltip')).toBeNull()
    expect(within(preparation).getByText('필수')).toBeTruthy()
    expect(within(preparation).getByText('2단계')).toBeTruthy()
    expect(within(preparation).getByText('발표평가 · 온라인')).toBeTruthy()
    expect(within(preparation).getByText('40점')).toBeTruthy()
    const evidenceHints = within(preparation).getAllByRole('button', { name: '원문 근거 보기' })
    expect(evidenceHints).toHaveLength(5)
    fireEvent.click(evidenceHints[4])
    expect(within(within(preparation).getByRole('tooltip')).getByText('원문 근거 · 첨부파일 공고문.hwpx')).toBeTruthy()
  })

  it('로그인한 회원에게 회사 정보 기준 조건 판정을 조건마다 보여 준다', async () => {
    const analysis = {
      status: 'COMPLETED' as const, analyzedAt: '2026-10-01T10:00:00', summaryLine: null, supportTypes: [], supportAmount: null,
      selectionScale: null, contact: null, ...noAttachmentItems,
      conditions: [
        { kind: 'REQUIRED' as const, category: 'REGION' as const, text: '서울 소재 기업', values: { ...values, regions: ['서울'] }, evidence },
        { kind: 'EXCLUDED' as const, category: 'OTHER' as const, text: '국세 체납 기업', values, evidence },
      ],
    }
    vi.spyOn(appContainer.resolve('getSupportProgramDetailUseCase'), 'execute').mockResolvedValue({ ...supportProgramDetails[0], analysis })
    vi.spyOn(appContainer.resolve('checkSavedSupportProgramUseCase'), 'execute').mockResolvedValue(false)
    const check = vi.spyOn(appContainer.resolve('checkSupportProgramConditionsUseCase'), 'execute').mockResolvedValue({
      status: 'CHECKED', analyzedAt: '2026-10-01T10:00:00', referenceDate: '2026-10-01', profile: { region: '부산', foundedYear: 2021 },
      overall: 'NOT_MET',
      conditions: [{ index: 0, result: 'NOT_MET', reason: 'REGION_MISMATCH' }, { index: 1, result: 'UNKNOWN', reason: 'NOT_COMPARABLE' }],
    })
    renderDetail(null, undefined, memberAccount)
    expect(await screen.findByText('대상 아님 가능성')).toBeTruthy()
    expect(check).toHaveBeenCalledWith({ sourceCode: supportPrograms[0].sourceCode, sourceProgramId: supportPrograms[0].id }, expect.any(AbortSignal))
    const region = screen.getByText('서울 소재 기업').closest('li') as HTMLElement
    expect(within(region).getByText('미충족')).toBeTruthy()
    expect(within(region).getByText('회사 소재지(부산)가 해당 지역이 아니에요')).toBeTruthy()
    const excluded = screen.getByText('국세 체납 기업').closest('li') as HTMLElement
    expect(within(excluded).getByText('확인 필요')).toBeTruthy()
  })

  it('회사 정보가 없으면 등록을 안내하고, 다른 분석으로 판정한 결과는 쓰지 않는다', async () => {
    const analysis = { status: 'COMPLETED' as const, analyzedAt: '2026-10-01T10:00:00', summaryLine: null, supportTypes: [], supportAmount: null, selectionScale: null, contact: null, ...noAttachmentItems,
      conditions: [{ kind: 'REQUIRED' as const, category: 'REGION' as const, text: '서울 소재 기업', values: { ...values, regions: ['서울'] }, evidence }] }
    vi.spyOn(appContainer.resolve('getSupportProgramDetailUseCase'), 'execute').mockResolvedValue({ ...supportProgramDetails[0], analysis })
    vi.spyOn(appContainer.resolve('checkSavedSupportProgramUseCase'), 'execute').mockResolvedValue(false)
    const check = vi.spyOn(appContainer.resolve('checkSupportProgramConditionsUseCase'), 'execute').mockResolvedValueOnce({ status: 'NO_COMPANY' })
    renderDetail(null, undefined, memberAccount)
    expect((await screen.findByRole('link', { name: '회사 정보 등록' })).getAttribute('href')).toBe('/app/welcome/company')
    cleanup()

    check.mockResolvedValueOnce({ status: 'CHECKED', analyzedAt: '2026-09-01T10:00:00', referenceDate: '2026-10-01', profile: { region: '서울', foundedYear: 2021 },
      overall: 'MET', conditions: [{ index: 0, result: 'MET', reason: 'REGION_MATCH' }] })
    renderDetail(null, undefined, memberAccount)
    await screen.findByRole('heading', { name: supportPrograms[0].title })
    await vi.waitFor(() => expect(check).toHaveBeenCalledTimes(2))
    expect(screen.queryByText('조건 충족')).toBeNull()
    expect(screen.queryByText('충족')).toBeNull()
  })

  it('비로그인은 로그인하고 비교하기를 안내하고 판정을 요청하지 않는다', async () => {
    vi.spyOn(appContainer.resolve('getSupportProgramDetailUseCase'), 'execute').mockResolvedValue({
      ...supportProgramDetails[0],
      analysis: { status: 'COMPLETED', analyzedAt: '2026-10-01T10:00:00', summaryLine: null, supportTypes: [], supportAmount: null, selectionScale: null, conditions: [], contact: null, requiredDocuments: [], selectionSteps: [], evaluationCriteria: [], schedule: [], sourceAttachmentNames: [] },
    })
    const check = vi.spyOn(appContainer.resolve('checkSupportProgramConditionsUseCase'), 'execute')
    renderDetail()
    expect((await screen.findByRole('link', { name: '로그인하고 내 회사 조건과 비교하기' })).getAttribute('href')).toMatch(/^\/login\?next=/)
    expect(check).not.toHaveBeenCalled()
  })

  it('본문에 지원 규모가 없으면 명시 없음으로 밝힌다', async () => {
    vi.spyOn(appContainer.resolve('getSupportProgramDetailUseCase'), 'execute').mockResolvedValue({
      ...supportProgramDetails[0],
      analysis: { status: 'COMPLETED', analyzedAt: '2026-10-01T10:00:00', summaryLine: null, supportTypes: [], supportAmount: null, selectionScale: null, conditions: [], contact: null, requiredDocuments: [], selectionSteps: [], evaluationCriteria: [], schedule: [], sourceAttachmentNames: [] },
    })
    renderDetail()
    await screen.findByRole('heading', { name: supportPrograms[0].title })
    expect(screen.getByText('공고 본문에 명시 없음')).toBeTruthy()
    expect(screen.getByText(/내 조건 비교는 회사 소재지·업력만 확인해요/)).toBeTruthy()
    expect(screen.getByText('공고 본문에 명시된 신청 조건이 없어요. 원문 공고에서 확인해 주세요.')).toBeTruthy()
    expect(screen.queryByText('AI 요약')).toBeNull()
  })

  it('분석 실패는 숨기지 않고, 분석 전 공고에는 분석 카드와 지원 규모 줄을 두지 않는다', async () => {
    const detail = vi.spyOn(appContainer.resolve('getSupportProgramDetailUseCase'), 'execute')
      .mockResolvedValueOnce({ ...supportProgramDetails[0], analysis: { status: 'FAILED' } })
    renderDetail()
    await screen.findByRole('heading', { name: supportPrograms[0].title })
    expect(screen.getByText('분석 실패')).toBeTruthy()
    cleanup()

    detail.mockResolvedValueOnce(supportProgramDetails[0])
    renderDetail()
    await screen.findByRole('heading', { name: supportPrograms[0].title })
    expect(screen.queryByRole('region', { name: '공고 분석' })).toBeNull()
    expect(screen.queryByText('지원 규모')).toBeNull()
  })
})

const memberAccount: Account = { email: 'member@govbiz.local', role: 'USER', tier: 'MEMBER', emailVerified: true, hasPassword: true, accountType: null, onboarded: true, company: null }

function renderDetail(
  state: unknown = null,
  search = `?${new URLSearchParams({ sourceCode: supportPrograms[0].sourceCode, sourceProgramId: supportPrograms[0].id })}`,
  account: Account | null = null,
) {
  // 기본은 비로그인 공개 화면입니다. 로그인한 상세의 저장 흐름은 App 테스트(App.savedPrograms)가 확인합니다.
  const store = createAppStore()
  store.dispatch(sessionRestored(account))
  render(<Provider store={store}><MemoryRouter initialEntries={[{ pathname: '/support-programs/detail', search, state }]}><Routes>
    <Route path="/support-programs/detail" element={<SupportProgramDetailPage />} />
    <Route path="/support-programs/detail/question" element={<SupportProgramEvidenceQuestionPage />} />
  </Routes></MemoryRouter></Provider>)
}

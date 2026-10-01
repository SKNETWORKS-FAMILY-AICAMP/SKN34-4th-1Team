import { useState, type ReactNode } from 'react'
import { Link, useLocation, useSearchParams } from 'react-router'

import { loginPathFor } from '../../../shared/auth/returnPath'
import { appPaths, isAppPath, supportProgramQuestionPath } from '../../../shared/routes/appPaths'
import { workspacePageStyles } from '../../../shared/workspace/WorkspacePage.styles'

import type { SupportProgramAnalysis, SupportProgramAnalysisEvidence, SupportProgramCondition, SupportProgramDetail } from '../../../../domain/entities/SupportProgram'
import {
  supportProgramConditionOverallLabels, supportProgramConditionReasonText, supportProgramConditionResultLabel,
} from '../../../../domain/entities/SupportProgramConditionCheck'
import {
  groupSupportProgramConditions, splitSupportProgramTarget, supportProgramApplicationRouteLabel, supportProgramConditionCategoryLabels,
  supportProgramDocumentRequirementLabels, supportProgramEvidenceSourceLabel, supportProgramSupportTypeLabels,
} from '../../../../domain/entities/SupportProgramSections'
import type { SupportProgramIdentity } from '../../../../domain/repositories/SupportProgramRepository'
import { useSupportProgramDetailViewModel } from '../viewmodel/useSupportProgramDetailViewModel'
import { useSupportProgramConditionCheckViewModel, type SupportProgramConditionCheckState } from '../viewmodel/useSupportProgramConditionCheckViewModel'
import { supportProgramSaveMessages, supportProgramSaveNoticeDurationMs, useSupportProgramSaveViewModel } from '../../../shared/support-program/useSupportProgramSaveViewModel'
import { WorkspaceToast } from '../../../shared/workspace/WorkspaceToast'
import { workspaceToastActionClassName } from '../../../shared/workspace/WorkspaceToast.styles'
import { EvidenceQuestionPanel } from './EvidenceQuestionPanel'
import { supportProgramDetailStyles as s } from './SupportProgramDetailPage.styles'
import { supportProgramEvidenceQuestionStyles as q } from './SupportProgramEvidenceQuestionPage.styles'
import { getSupportProgramFromPipeline, getSupportProgramSearchReturnTo, isWorkspaceListReturnTo, supportProgramBackLabel, type SupportProgramSearchReturnTo } from './supportProgramNavigation'
import { supportProgramDeadlineChip, supportProgramStatusLabel } from './supportProgramStatus'

/** URL의 제공처·원본 공고 ID로 최신 상세 정보를 조회하는 화면입니다. */
export function SupportProgramDetailPage() {
  const location = useLocation()
  const locationState = location.state
  // 새로고침·공유 URL로 들어와 이동 상태가 없으면 비로그인 링크가 실어 둔 `back`으로 검색 화면을 복원합니다.
  const searchReturnTo = getSupportProgramSearchReturnTo(locationState, location.search)
  const fromPipeline = getSupportProgramFromPipeline(locationState)
  const [searchParams] = useSearchParams()
  const identity = getSupportProgramIdentity(
    searchParams.get('sourceCode') ?? undefined,
    searchParams.get('sourceProgramId') ?? undefined,
  )

  if (!identity) {
    return (
      <UnavailableSupportProgramDetail
        searchReturnTo={searchReturnTo}
        icon="search"
        description="공고 주소가 올바르지 않아요. 검색 결과에서 공고를 다시 선택해 주세요."
        title="공고 정보를 찾을 수 없습니다"
      />
    )
  }

  return (
    <SupportProgramDetailContent
      key={JSON.stringify([identity.sourceCode, identity.sourceProgramId])}
      identity={identity}
      searchReturnTo={searchReturnTo}
      fromPipeline={fromPipeline}
    />
  )
}

function SupportProgramDetailContent({ identity, searchReturnTo, fromPipeline }: {
  identity: SupportProgramIdentity
  searchReturnTo: SupportProgramSearchReturnTo
  fromPipeline: boolean
}) {
  const detail = useSupportProgramDetailViewModel(identity)

  if (detail.status === 'loading') {
    return <LoadingSupportProgramDetail searchReturnTo={searchReturnTo} />
  }

  if (detail.status === 'not-found') {
    return (
      <UnavailableSupportProgramDetail
        searchReturnTo={searchReturnTo}
        icon="search"
        description="존재하지 않거나 더 이상 제공되지 않는 공고예요. 검색 결과에서 다른 공고를 확인해 주세요."
        title="공고 정보를 찾을 수 없습니다"
      />
    )
  }

  if (detail.status === 'failed') {
    return <UnavailableSupportProgramDetail
      searchReturnTo={searchReturnTo}
      retry={detail.retry}
      icon="alert"
      description="공고 상세 정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요."
      title="공고 정보를 불러오지 못했습니다"
    />
  }

  return <SupportProgramDetail program={detail.program} searchReturnTo={searchReturnTo} fromPipeline={fromPipeline} />
}

/** 불러오는 동안은 완성 화면과 같은 자리에 스켈레톤을 그려 화면이 튀지 않게 합니다. 제목은 읽기 전용으로만 알립니다. */
function LoadingSupportProgramDetail({ searchReturnTo }: { searchReturnTo: SupportProgramSearchReturnTo }) {
  const bar = (width: string, tall = false) => <span className={`${tall ? s.skeletonBarTall : s.skeletonBar} ${width}`} aria-hidden="true" />
  return (
    <main className={s.page} aria-busy="true">
      <TopBar searchReturnTo={searchReturnTo} />
      <h1 className="sr-only" aria-live="polite">공고 정보를 불러오는 중입니다</h1>
      <div className={s.layout}>
        <div className={s.article}>
          <div className={s.skeletonCard}>
            <div className="flex gap-2">{bar('w-12')}{bar('w-14')}{bar('w-16')}</div>
            {bar('h-6 w-4/5')}
            {bar('w-2/5')}
          </div>
          <div className={s.skeletonCard}>
            {bar('w-20')}
            {bar('w-full')}{bar('w-11/12')}{bar('w-3/4')}{bar('w-2/3')}
          </div>
          <div className={s.skeletonCard}>
            {bar('w-16')}
            {bar('w-full')}{bar('w-5/6')}
            {bar('mt-2 w-16')}
            {bar('w-full')}{bar('w-2/3')}
          </div>
        </div>
        <aside className={s.aside} aria-hidden="true">
          {bar('w-full', true)}
          {bar('w-full', true)}
          <div className={s.divider} />
          {bar('w-3/5')}{bar('w-2/5')}
        </aside>
      </div>
    </main>
  )
}

/**
 * 맨 위 줄입니다. 화면 폭과 관계없이 모바일 앱 바처럼 "‹ 공고 상세" 한 덩어리이고, 이 영역 전체를 누르면 들어온 화면으로 돌아갑니다.
 * 보이는 글자는 "공고 상세"지만 접근성 이름은 어디로 가는지("검색 결과로 돌아가기" 등)를 말합니다. 작업 화면에서 관심 공고함 · 신청 문서
 * 목록으로부터 열린 상세는 `WorkspaceSearchDetailLayout`이 머리글 높이의 줄에 같은 링크를 두므로 여기서는 그리지 않습니다.
 */
function TopBar({ searchReturnTo }: { searchReturnTo: SupportProgramSearchReturnTo }) {
  const inApp = isAppPath(useLocation().pathname)
  if (inApp && isWorkspaceListReturnTo(searchReturnTo)) return null
  const backLabel = supportProgramBackLabel(searchReturnTo)
  return (
    <div className={s.topBar}>
      <Link className={s.backLink} to={searchReturnTo} aria-label={backLabel} title={backLabel}>
        <Icon name="chevronLeft" size={22} />
        <span aria-hidden="true">공고 상세</span>
      </Link>
    </div>
  )
}


/**
 * 공고 상세 본문입니다. 웹 화면 v2의 공고 상세 보드를 따릅니다. 왼쪽은 접수 상태·D-day·출처, 제목, AI 요약, "한눈에 보기",
 * 공고 분석(AI가 정리한 신청 조건·문의처), 공고 내용(지원 내용·지원 대상·제외 대상·신청 방법)과 자격 안내이고 오른쪽은 이 공고로 할 일(원문에 질문하기,
 * 관심 공고, 신청 문서 작성, 중복 검토, 신청 사이트·원문 보기)입니다.
 * 좁은 화면은 할 일 카드가 아래 고정 동작 바가 되고 나머지 줄은 [더 보기]로 펼칩니다.
 */
function SupportProgramDetail({ program, searchReturnTo, fromPipeline }: {
  program: SupportProgramDetail
  searchReturnTo: SupportProgramSearchReturnTo
  fromPipeline: boolean
}) {
  // 작업 채팅에서 연 상세는 질문 화면도 사이드바 안(/app)에서 열리도록 현재 경로로 판단합니다.
  const location = useLocation()
  const [searchParams, setSearchParams] = useSearchParams()
  const save = useSupportProgramSaveViewModel({ sourceCode: program.sourceCode, sourceProgramId: program.id })
  // 진행 관리에서 들어온 공고는 신청 준비 중인 사업이라, 관심 공고함에서 빼면 진행 관리 보드에서도 사라집니다.
  // 책갈피 한 번에 실수로 빠지지 않도록 이때만 확인을 받습니다. 담기는 되돌리기 쉬우므로 바로 처리합니다.
  const [confirmingRemove, setConfirmingRemove] = useState(false)
  const [moreOpen, setMoreOpen] = useState(false)
  const needsRemoveConfirm = fromPipeline && save.isSaved === true
  const identity = { sourceCode: program.sourceCode, sourceProgramId: program.id }
  const applicationPreparationPath = `${appPaths.applicationPreparationNew}?${new URLSearchParams(identity)}`
  // 원문 질문은 상세를 떠나지 않고 위에 겹치는 옆 패널(좁은 화면은 아래 시트)로 엽니다. 열림은 `?ask=1`로 주소에 남겨 뒤로가기·새로고침이 그대로 됩니다.
  const isAsking = searchParams.get('ask') === '1' && save.isAuthenticated && program.evidenceQuestionSupported
  const openAsk = () => {
    const next = new URLSearchParams(searchParams)
    next.set('ask', '1')
    setSearchParams(next, { state: location.state })
  }
  const closeAsk = () => {
    const next = new URLSearchParams(searchParams)
    next.delete('ask')
    setSearchParams(next, { replace: true, state: location.state })
  }
  const status = supportProgramStatusLabel(program.status)
  const deadline = supportProgramDeadlineChip(program.status, program.applicationEndDate)
  const isOfficialNoticeList = program.sourceCode === 'CNTRADE_NOTICE'
  const target = splitSupportProgramTarget(program.sourceCode, program.targetDescription)
  const routeLabel = supportProgramApplicationRouteLabel(program.applicationRoute)
  const analysis = program.analysis
  const conditionCheck = useSupportProgramConditionCheckViewModel(
    identity, analysis.status === 'COMPLETED' ? analysis.analyzedAt : null, save.isAuthenticated,
  )
  const statusTone = { open: s.statusOpen, upcoming: s.statusUpcoming, closed: s.statusClosed, unknown: s.statusUnknown }[status.tone]
  const dotTone = { open: s.statusDotOpen, upcoming: s.statusDotUpcoming, closed: s.statusDotClosed, unknown: s.statusDotUnknown }[status.tone]

  // 관심 공고는 [원문에 질문하기] 바로 아래에서 담기·빼기를 오가는 버튼 하나입니다. 비로그인은 로그인 뒤 이 공고로 돌아옵니다.
  const saveLabel = save.isSaved ? '관심 공고에서 빼기' : '관심 공고에 담기'
  const saveControl = save.isAuthenticated ? (
    <button
      className={s.saveButton}
      type="button"
      aria-label={saveLabel}
      title={saveLabel}
      aria-pressed={save.isSaved === true}
      disabled={save.isBusy}
      onClick={() => { if (needsRemoveConfirm) setConfirmingRemove(true); else void save.toggle() }}
    >
      <span className={s.saveIcon}><Icon name="bookmark" size={18} filled={save.isSaved === true} /></span>
      <span className={s.saveLabel} aria-hidden="true">{saveLabel}</span>
    </button>
  ) : (
    <Link className={s.saveButton} to={save.loginPath} aria-label="로그인하고 관심 공고에 담기" title="로그인하고 관심 공고에 담기">
      <span className={s.saveIcon}><Icon name="bookmark" size={18} filled={false} /></span>
      <span className={s.saveLabel} aria-hidden="true">로그인하고 관심 공고에 담기</span>
    </Link>
  )

  // 원문 질문은 회원 기능입니다. 회원은 패널을 열고, 비로그인은 로그인 뒤 작업 화면의 질문 화면으로 이어집니다.
  const questionControl = !program.evidenceQuestionSupported ? null : save.isAuthenticated ? (
    <button type="button" className={s.primaryAction} onClick={openAsk} aria-expanded={isAsking} aria-controls="support-program-ask">
      <Icon name="chat" />원문에 질문하기
    </button>
  ) : (
    <Link className={s.primaryAction} to={loginPathFor(supportProgramQuestionPath(identity, true))}>
      <Icon name="chat" />로그인하고 원문에 질문하기
    </Link>
  )

  return (
    <main className={s.page}>
      <TopBar searchReturnTo={searchReturnTo} />

      {confirmingRemove ? (
        <div className={s.removeConfirm} role="alertdialog" aria-label="관심 공고 빼기 확인">
          <span>
            신청 준비 중인 공고입니다. 관심 공고함에서 빼면 진행 관리에서도 보이지 않습니다.
            작성한 신청 문서는 지워지지 않고 신청 준비 화면에 그대로 남습니다.
          </span>
          <span className="flex items-center gap-3">
            <button className={workspacePageStyles.dangerButton} type="button" disabled={save.isBusy}
              onClick={() => { setConfirmingRemove(false); void save.toggle() }}>정말 빼기</button>
            <button className={workspacePageStyles.quietLink} type="button" onClick={() => setConfirmingRemove(false)}>취소</button>
          </span>
        </div>
      ) : null}

      {/* 담기·빼기 결과는 흰 토스트로 알립니다. 담으면 [관심 공고함 보기], 빼면 [되돌리기]. */}
      <WorkspaceToast notice={save.notice} onClose={save.dismissNotice} durationMs={supportProgramSaveNoticeDurationMs}
        tone={save.notice?.text === supportProgramSaveMessages.saved || save.notice?.text === supportProgramSaveMessages.removed ? 'success' : 'danger'}
        action={save.notice?.text === supportProgramSaveMessages.saved
          ? <Link className={workspaceToastActionClassName} to={save.savedProgramsPath}>관심 공고함 보기</Link>
          : save.notice?.text === supportProgramSaveMessages.removed
            ? <button type="button" className={workspaceToastActionClassName} disabled={save.isBusy} onClick={() => void save.toggle()}>되돌리기</button>
            : null} />

      <div className={s.layout}>
        {/* 본문은 화면 통일안 18번처럼 요약 hero → 한눈에 보기 → 공고 내용 세 카드로 나눕니다. */}
        <article className={s.article} aria-labelledby="support-program-title">
          <header className={s.heading}>
            <div className={s.meta}>
              <span className={`${s.status} ${statusTone}`}>
                <span className={`${s.statusDot} ${dotTone}`} aria-hidden="true" />
                {status.label}
              </span>
              {deadline ? <span className={`${s.deadline} ${deadline.urgent ? s.deadlineUrgent : s.deadlineCalm}`}>{deadline.label}</span> : null}
              <span className={s.source}>{program.sourceName}</span>
            </div>
            <h1 id="support-program-title" className={s.title}>{program.title}</h1>
            <p className={s.organization}>{program.organization}</p>
            {analysis.status === 'COMPLETED' && analysis.summaryLine ? (
              <p className={s.summaryLine}><span className={s.aiPill}>AI 요약</span><span>{analysis.summaryLine}</span></p>
            ) : null}
          </header>

          <section className={s.glance} aria-labelledby="support-program-glance">
            <h2 id="support-program-glance" className={s.glanceTitle}>한눈에 보기</h2>
            <dl className={s.glanceList}>
              {/* 지원 규모·지원 형태는 공식 API가 주지 않아 공고 분석을 마친 공고에만 둡니다. 신청 방법은 공식 신청 필드로 분류한 경로입니다. */}
              {analysis.status === 'COMPLETED' ? <AnalysisGlanceRows analysis={analysis} /> : null}
              <GlanceRow label="접수 기간"><span className={s.glanceValueStrong}>{program.applicationPeriod}</span></GlanceRow>
              <GlanceRow label="신청 방법">
                {routeLabel ? <span className={s.glanceValueStrong}>{routeLabel}</span> : <span className={s.glanceValueMuted}>공고 원문에서 확인해 주세요</span>}
              </GlanceRow>
              <GlanceRow label="지역" tight><TagList values={program.regions} emptyLabel="지역 정보 없음" /></GlanceRow>
              <GlanceRow label="분야" tight><TagList values={program.categories} emptyLabel="분야 정보 없음" /></GlanceRow>
            </dl>
          </section>

          <AnalysisSection analysis={analysis} check={conditionCheck}
            loginPath={save.isAuthenticated ? null : loginPathFor(`${location.pathname}${location.search}`)} />
          {analysis.status === 'COMPLETED' ? <PreparationSection analysis={analysis} /> : null}

          <section className={s.prose} aria-labelledby="support-program-prose">
            <h2 id="support-program-prose" className="sr-only">공고 내용</h2>
            <section className={s.proseSection}>
              <h3 className={s.proseTitle}>지원 내용</h3>
              <p className={s.summary}>{program.summary}</p>
            </section>
            <section className={s.proseSection}>
              <h3 className={s.proseTitle}>지원 대상</h3>
              <p className={s.summary}>{target.target || '공고 원문에서 확인해 주세요.'}</p>
            </section>
            {target.excluded ? (
              <section className={s.proseSection}>
                <h3 className={s.proseTitle}>제외 대상</h3>
                <p className={s.summary}>{target.excluded}</p>
              </section>
            ) : null}
            {program.applicationRoute.method ? (
              <section className={s.proseSection}>
                <h3 className={s.proseTitle}>신청 방법</h3>
                <p className={s.summary}>{program.applicationRoute.method}</p>
              </section>
            ) : null}
            <p className={s.note} role="note">
              <span className={s.notePill}>자격 안내</span>
              {/* 공고 분석을 마친 공고는 "내 조건과 비교"가 소재지·업력만 비교한다는 범위를, 분석 전 공고는 비교하지 않았다는 사실을 알립니다. */}
              <span>
                {analysis.status === 'COMPLETED'
                  ? '공고 분석의 내 조건 비교는 회사 소재지·업력만 확인해요. 지역·분야 태그는 제공처 분류라 신청 자격이 아니니, 그 밖의 조건과 최종 자격은 원문 공고에서 확인해 주세요.'
                  : '이 공고는 아직 공고 분석 전이라 회사 조건과 비교하지 않았어요. 지역·분야 태그만으로 신청 자격을 판단하지 말고 최종 조건은 원문 공고에서 확인해 주세요.'}
              </span>
            </p>
          </section>
        </article>

        <aside className={s.aside} aria-label="이 공고로 할 일">
          {/* 넓은 화면은 관심 공고 → 질문 → 설명 순서로 세로로, 좁은 화면은 동작 바 한 줄(관심 공고 · 더 보기 · 질문)로 다시 정렬됩니다. */}
          <div className={s.asideBar}>
            {saveControl}
            {questionControl}
            <p className={s.primaryHint}>
              {program.evidenceQuestionSupported
                ? '궁금한 신청 조건을 물으면 원문에서 근거를 찾아 답해요.'
                : '이 제공처 공고는 아직 원문 근거 답변을 지원하지 않습니다. 원문 공고에서 확인해 주세요.'}
            </p>
            <button
              type="button"
              className={s.moreButton}
              aria-expanded={moreOpen}
              aria-controls="support-program-more"
              onClick={() => setMoreOpen((open) => !open)}
            >
              {moreOpen ? '접기' : '더 보기'}
            </button>
          </div>
          <div className={s.divider} />
          <div id="support-program-more" className={`${s.more} ${moreOpen ? '' : s.moreHidden}`}>
            <nav aria-label="관련 작업" className="contents">
              {save.isAuthenticated ? (
                <Link className={s.row} to={applicationPreparationPath}>
                  <span className={s.rowIcon}><Icon name="document" /></span><span className={s.rowLabel}>이 공고로 신청 문서 작성</span>
                </Link>
              ) : (
                // 신청 문서 작성은 로그인 화면이라 비로그인에는 로그인 뒤 그 화면으로 이어지는 링크를 둡니다.
                <Link className={s.row} to={loginPathFor(applicationPreparationPath)}>
                  <span className={s.rowIcon}><Icon name="document" /></span><span className={s.rowLabel}>로그인하고 이 공고로 신청 문서 작성</span>
                </Link>
              )}
              <Link className={s.row} to={save.isAuthenticated ? appPaths.combinationReviewNew : loginPathFor(appPaths.combinationReviewNew)}>
                <span className={s.rowIcon}><Icon name="shield" /></span><span className={s.rowLabel}>중복 지원·수혜 검토</span>
              </Link>
            </nav>
            <div className={s.divider} />
            <div className={s.sourceBlock}>
              <p className={s.sourceNote}>
                <b className={s.sourceNoteLead}>신청 전 확인</b> · 지원 자격, 제출 서류, 신청 방법은 공고 원문을 기준으로 해요.
              </p>
              {isOfficialNoticeList ? <p className={s.sourceNote}>제목으로 해당 공지를 확인해 주세요.</p> : null}
              {program.applicationRoute.url ? (
                <a className={s.sourceLink} href={program.applicationRoute.url} target="_blank" rel="noreferrer">
                  {program.applicationRoute.type === 'GOOGLE_FORMS' ? '구글 설문 신청서 열기' : '신청 사이트 열기'} ↗
                </a>
              ) : null}
              <a className={s.sourceLink} href={program.sourceUrl} target="_blank" rel="noreferrer">
                {isOfficialNoticeList ? '공식 공지 목록' : `${program.sourceName} 원문 보기`} ↗
              </a>
            </div>
          </div>
        </aside>
      </div>

      {/* 원문 질문 옆 패널은 상세 위에 겹칩니다. 좁은 화면은 아래 시트라 뒤를 어둡게 덮고, 덮개를 누르면 닫힙니다. */}
      {isAsking ? <>
        <button type="button" className={q.panelScrim} aria-label="닫기" onClick={closeAsk} />
        <EvidenceQuestionPanel identity={identity} programTitle={program.title} onClose={closeAsk} />
      </> : null}
    </main>
  )
}

/**
 * 없음·실패 상태입니다. 상세와 같은 폭·맨 위 줄 아래에 상태 카드(원 표지 + 제목 + 설명 + 동작) 하나를 둡니다.
 * 보여 줄 공고가 없으니 오른쪽 할 일 열도 없이 카드가 본문 전체 폭을 씁니다.
 * 실패는 빨간 표지와 [다시 시도], 없음은 회색 표지만 두고, 돌아가는 길은 맨 위 줄의 "‹ 공고 상세"가 맡습니다.
 */
function UnavailableSupportProgramDetail({ description, icon, retry, searchReturnTo, title }: {
  description: string
  icon: 'alert' | 'search'
  retry?: () => void
  searchReturnTo: SupportProgramSearchReturnTo
  title: string
}) {
  return (
    <main className={s.page}>
      <TopBar searchReturnTo={searchReturnTo} />
      <div className={s.stateLayout}>
        <section className={s.stateCard} aria-live={retry ? 'polite' : undefined}>
          <span className={`${s.stateGlyph} ${retry ? s.stateGlyphDanger : ''}`} aria-hidden="true"><Icon name={icon} size={22} /></span>
          <h1 className={s.stateTitle}>{title}</h1>
          <p className={s.stateDescription}>{description}</p>
          {retry ? <button type="button" className={s.retryButton} onClick={retry}>다시 시도</button> : null}
        </section>
      </div>
    </main>
  )
}

type CompletedAnalysis = Extract<SupportProgramAnalysis, { status: 'COMPLETED' }>

/** 분석을 마친 공고의 지원 규모·선정 규모·지원 형태 줄입니다. 공고 본문에 없으면 없다고 밝힙니다. */
function AnalysisGlanceRows({ analysis }: { analysis: CompletedAnalysis }) {
  return <>
    <GlanceRow label="지원 규모">
      {analysis.supportAmount
        ? <span className={s.glanceValueStrong}>{analysis.supportAmount.text}</span>
        : <span className={s.glanceValueMuted}>공고 본문에 명시 없음</span>}
    </GlanceRow>
    {analysis.selectionScale ? <GlanceRow label="선정 규모"><span>{analysis.selectionScale.text}</span></GlanceRow> : null}
    {analysis.supportTypes.length ? (
      <GlanceRow label="지원 형태" tight>
        <TagList values={analysis.supportTypes.map((type) => supportProgramSupportTypeLabels[type])} emptyLabel="" />
      </GlanceRow>
    ) : null}
  </>
}

/**
 * 공고 분석 카드입니다. AI가 공고 원문에서 정리한 신청 조건과 문의처를 원문 인용과 함께 보여 줍니다.
 * 아직 분석하지 않은 공고는 카드를 두지 않고, 분석에 실패한 공고는 실패를 숨기지 않고 원문 확인을 안내합니다.
 */
function AnalysisSection({ analysis, check, loginPath }: {
  analysis: SupportProgramAnalysis
  check: SupportProgramConditionCheckState & { retry: () => void }
  /** 비로그인이면 로그인 뒤 이 상세로 돌아오는 경로, 로그인했으면 `null`입니다. */
  loginPath: string | null
}) {
  if (analysis.status === 'NOT_ANALYZED') return null
  if (analysis.status === 'FAILED') {
    return (
      <section className={s.prose} aria-labelledby="support-program-analysis">
        <h2 id="support-program-analysis" className={s.proseTitle}>공고 분석</h2>
        <p className={s.note} role="note">
          <span className={s.notePill}>분석 실패</span>
          <span>이 공고는 AI가 조건을 정리하지 못했어요. 신청 조건은 아래 공고 내용과 원문 공고에서 확인해 주세요.</span>
        </p>
      </section>
    )
  }
  const groups = groupSupportProgramConditions(analysis.conditions)
  return (
    <section className={s.prose} aria-labelledby="support-program-analysis">
      <div className={s.analysisHeader}>
        <h2 id="support-program-analysis" className={s.proseTitle}>공고 분석</h2>
        <span className={s.analysisMeta}>AI 정리 · {analysis.analyzedAt.slice(0, 10)}</span>
      </div>
      <ConditionCheckSummary check={check} loginPath={loginPath} />
      {groups.length ? groups.map((group) => (
        <section key={group.kind} className={s.proseSection} aria-label={group.title}>
          <h3 className={s.proseTitle}>{group.title}</h3>
          <ul className={s.conditionList}>
            {group.entries.map(({ condition, index }) => (
              <li key={index} className={s.conditionItem} data-condition-index={index}>
                <span className={s.conditionLine}>
                  <span className={s.conditionChip}>{supportProgramConditionCategoryLabels[condition.category]}</span>
                  <span>{condition.text}</span>
                </span>
                <ConditionCheckResult check={check} index={index} kind={condition.kind} />
                <EvidenceQuote evidence={condition.evidence} />
              </li>
            ))}
          </ul>
        </section>
      )) : <p className={s.emptyValue}>공고 본문에 명시된 신청 조건이 없어요. 원문 공고에서 확인해 주세요.</p>}
      {analysis.contact ? (
        <section className={s.proseSection}>
          <h3 className={s.proseTitle}>문의처</h3>
          <p className={s.summary}>{analysis.contact.text}</p>
          <EvidenceQuote evidence={analysis.contact.evidence} />
        </section>
      ) : null}
      <p className={s.note} role="note">
        <span className={s.notePill}>AI 정리</span>
        <span>공고 원문에서 그대로 인용할 수 있는 내용만 정리했어요. 최종 조건은 원문 공고를 기준으로 해요.</span>
      </p>
    </section>
  )
}

/**
 * 신청 준비 카드입니다. 공고 분석이 첨부파일(공고문)까지 읽어 정리한 일정·제출 서류·선정 절차·평가 기준을 보여 줍니다.
 * 네 항목이 모두 비어 있으면(첨부 분석 전이거나 원문에 없음) 카드를 두지 않습니다.
 */
function PreparationSection({ analysis }: { analysis: CompletedAnalysis }) {
  const { schedule, requiredDocuments, selectionSteps, evaluationCriteria } = analysis
  if (!schedule.length && !requiredDocuments.length && !selectionSteps.length && !evaluationCriteria.length) return null
  return (
    <section className={s.prose} aria-labelledby="support-program-preparation">
      <div className={s.analysisHeader}>
        <h2 id="support-program-preparation" className={s.proseTitle}>신청 준비</h2>
        <span className={s.analysisMeta}>
          AI 정리{analysis.sourceAttachmentNames.length ? ` · 첨부 ${analysis.sourceAttachmentNames.join(', ')}` : ''}
        </span>
      </div>
      {schedule.length ? (
        <section className={s.proseSection} aria-label="일정">
          <h3 className={s.proseTitle}>일정</h3>
          <ol className={s.conditionList}>
            {schedule.map((entry, index) => (
              <li key={index} className={s.conditionItem}>
                <span className={s.conditionLine}>
                  <span className={s.conditionChip}>{entry.date ?? '날짜 미정'}</span>
                  <span><b>{entry.label}</b> · {entry.text}</span>
                </span>
                <EvidenceQuote evidence={entry.evidence} />
              </li>
            ))}
          </ol>
        </section>
      ) : null}
      {requiredDocuments.length ? (
        <section className={s.proseSection} aria-label="제출 서류">
          <h3 className={s.proseTitle}>제출 서류</h3>
          <ul className={s.conditionList}>
            {requiredDocuments.map((document, index) => (
              <li key={index} className={s.conditionItem}>
                <span className={s.conditionLine}>
                  <span className={s.conditionChip}>{supportProgramDocumentRequirementLabels[document.requirement]}</span>
                  <span>{document.name}{document.note ? ` · ${document.note}` : ''}</span>
                </span>
                <EvidenceQuote evidence={document.evidence} />
              </li>
            ))}
          </ul>
        </section>
      ) : null}
      {selectionSteps.length ? (
        <section className={s.proseSection} aria-label="선정 절차">
          <h3 className={s.proseTitle}>선정 절차</h3>
          <ol className={s.conditionList}>
            {selectionSteps.map((step, index) => (
              <li key={index} className={s.conditionItem}>
                <span className={s.conditionLine}>
                  <span className={s.conditionChip}>{index + 1}단계</span>
                  <span>{step.name}{step.note ? ` · ${step.note}` : ''}</span>
                </span>
                <EvidenceQuote evidence={step.evidence} />
              </li>
            ))}
          </ol>
        </section>
      ) : null}
      {evaluationCriteria.length ? (
        <section className={s.proseSection} aria-label="평가 기준">
          <h3 className={s.proseTitle}>평가 기준</h3>
          <ul className={s.conditionList}>
            {evaluationCriteria.map((criterion, index) => (
              <li key={index} className={s.conditionItem}>
                <span className={s.conditionLine}>
                  <span className={s.conditionChip}>{criterion.points === null ? '배점 미기재' : `${criterion.points}점`}</span>
                  <span>{criterion.item}</span>
                </span>
                <EvidenceQuote evidence={criterion.evidence} />
              </li>
            ))}
          </ul>
        </section>
      ) : null}
      <p className={s.note} role="note">
        <span className={s.notePill}>AI 정리</span>
        <span>공고 본문과 첨부 공고문에서 원문 그대로 인용할 수 있는 내용만 정리했어요. 제출 전 원문 공고의 서류·일정을 다시 확인해 주세요.</span>
      </p>
    </section>
  )
}

/**
 * "내 조건과 비교" 한 줄입니다. 회사 정보(소재지·설립연도)로 서버 규칙이 비교한 결과이며 AI 판단이 아닙니다.
 * 비로그인·회사 정보 없음은 비교할 수 있는 방법을 안내하고, 실패는 숨기지 않고 다시 시도를 둡니다.
 */
function ConditionCheckSummary({ check, loginPath }: { check: SupportProgramConditionCheckState & { retry: () => void }; loginPath: string | null }) {
  if (loginPath) {
    return <p className={s.checkBanner}><Link className={s.checkLink} to={loginPath}>로그인하고 내 회사 조건과 비교하기</Link></p>
  }
  if (check.status === 'loading') return <p className={s.checkBanner} aria-live="polite">회사 정보와 비교하는 중이에요…</p>
  if (check.status === 'failed') {
    return (
      <p className={s.checkBanner} role="alert">
        회사 조건과 비교하지 못했어요.
        <button type="button" className={s.checkLink} onClick={check.retry}>다시 시도</button>
      </p>
    )
  }
  if (check.status !== 'ready') return null
  if (check.check.status === 'NO_COMPANY') {
    return (
      <p className={s.checkBanner}>
        회사 정보를 등록하면 소재지·업력 조건을 비교해 드려요.
        <Link className={s.checkLink} to={appPaths.welcomeCompany}>회사 정보 등록</Link>
      </p>
    )
  }
  if (check.check.status !== 'CHECKED') return null
  const { overall, profile, referenceDate } = check.check
  return (
    <p className={s.checkBanner}>
      <span className={`${s.checkPill} ${checkToneClass[overall]}`}>{supportProgramConditionOverallLabels[overall]}</span>
      <span>
        내 회사 기준 · 소재지 {profile.region ?? '미등록'} · 설립 {profile.foundedYear ? `${profile.foundedYear}년` : '미등록'} · {referenceDate} 기준.
        소재지·업력 외 조건은 직접 확인해 주세요.
      </span>
    </p>
  )
}

function ConditionCheckResult({ check, index, kind }: {
  check: SupportProgramConditionCheckState
  index: number
  kind: SupportProgramCondition['kind']
}) {
  if (check.status !== 'ready' || check.check.status !== 'CHECKED') return null
  const item = check.check.conditions.find((condition) => condition.index === index)
  if (!item) return null
  return (
    <span className={s.checkResult}>
      <span className={`${s.checkPill} ${checkToneClass[item.result]}`}>{supportProgramConditionResultLabel(kind, item.result)}</span>
      <span>{supportProgramConditionReasonText(item.reason, check.check.profile)}</span>
    </span>
  )
}

const checkToneClass = { MET: s.checkMet, NOT_MET: s.checkNotMet, UNKNOWN: s.checkUnknown } as const

/** 분석 항목의 원문 인용입니다. 기본은 접어 두고 어느 부분에서 왔는지 함께 보여 줍니다. */
function EvidenceQuote({ evidence }: { evidence: SupportProgramAnalysisEvidence }) {
  return (
    <details className={s.evidence}>
      <summary>원문 근거 · {supportProgramEvidenceSourceLabel(evidence)}</summary>
      <blockquote className={s.evidenceQuote}>{evidence.quote}</blockquote>
    </details>
  )
}

/** "한눈에 보기" 한 줄입니다. 넓은 화면은 이름 112px과 값 두 열, 좁은 화면은 위아래로 쌓입니다. */
function GlanceRow({ label, tight = false, children }: { label: string; tight?: boolean; children: ReactNode }) {
  return (
    <div className={`${s.glanceRow} ${tight ? s.glanceRowTight : ''}`}>
      <dt className={s.glanceLabel}>{label}</dt>
      <dd className={s.glanceValue}>{children}</dd>
    </div>
  )
}

function TagList({ emptyLabel, values }: { emptyLabel: string; values: string[] }) {
  if (values.length === 0) return <span className={s.emptyValue}>{emptyLabel}</span>
  return (
    <ul className={s.tagList}>
      {values.map((value) => <li key={value} className={s.tag}>{value}</li>)}
    </ul>
  )
}

const iconPaths = {
  chevronLeft: 'm15 18-6-6 6-6',
  bookmark: 'M6 4h12v16l-6-4-6 4z',
  chat: 'M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12z',
  document: 'M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8zM14 3v5h5M9 13h6M9 17h6',
  shield: 'M12 3 5 6v6c0 4.5 3 7.5 7 9 4-1.5 7-4.5 7-9V6zM9 12l2 2 4-4',
  alert: 'M12 9v4m0 4h.01M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z',
  search: 'm21 21-4.3-4.3M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14z',
} as const

function Icon({ name, size = 20, filled = false }: { name: keyof typeof iconPaths; size?: number; filled?: boolean }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill={filled ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth={1.75} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
      <path d={iconPaths[name]} />
    </svg>
  )
}

function getSupportProgramIdentity(sourceCode: string | undefined, sourceProgramId: string | undefined): SupportProgramIdentity | null {
  if (!sourceCode?.trim() || !sourceProgramId?.trim()) return null
  return { sourceCode, sourceProgramId }
}

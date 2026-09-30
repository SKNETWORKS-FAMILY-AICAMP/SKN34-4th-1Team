import { useEffect, useMemo, useRef, useState } from 'react'
import { ActivityIndicator, Linking, Pressable, StyleSheet, Text, View } from 'react-native'
import { useSafeAreaInsets } from 'react-native-safe-area-context'
import type { SupportProgramDetail } from '@govbiz/shared/domain/entities/SupportProgram'
import { splitSupportProgramTarget, supportProgramApplicationRouteLabel } from '@govbiz/shared/domain/entities/SupportProgramSections'
import type { SupportProgramIdentity } from '@govbiz/shared/domain/repositories/SupportProgramRepository'
import type { SupportProgramEvidenceAnswer } from '@govbiz/shared/domain/entities/SupportProgramEvidenceAnswer'
import { savedSupportProgramDtoSchema, savedSupportProgramStatusDtoSchema } from '@govbiz/shared/data/models/SavedSupportProgramDto'
import { toSupportProgramDetail } from '@govbiz/shared/data/models/SupportProgramDto'
import { supportProgramConditionCheckDtoSchema, toSupportProgramConditionCheck } from '@govbiz/shared/data/models/SupportProgramConditionCheckDto'
import type { SupportProgramConditionCheck } from '@govbiz/shared/domain/entities/SupportProgramConditionCheck'
import { ApiError, apiRequest, errorMessage, programClient } from '../api/client'
import { useAuth } from '../auth/session'
import { statusLabels } from '../components/ProgramCard'
import { AppIcon } from '../components/AppIcon'
import { partnerDeadlineDay } from '../components/PartnerDates'
import { PartnerSheet } from '../components/PartnerSheet'
import { ProgramAnalysisCard, ProgramAnalysisFacts, ProgramAnalysisPreparation } from '../components/ProgramAnalysis'
import { ProgramPreparationSection } from '../components/PreparationRows'
import { Button, Card, Field, Notice, Page, StatusBadge, Subtitle, Title, colors, styles } from '../ui'

export function ProgramScreen({ identity, onLogin }: { identity: SupportProgramIdentity; onLogin: () => void }) {
  const { session, status, invalidateSession } = useAuth()
  const token = status === 'signedIn' ? session?.accessToken : undefined
  const client = useMemo(() => programClient(token), [token])
  const [program, setProgram] = useState<SupportProgramDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [retry, setRetry] = useState(0)
  const [saved, setSaved] = useState<boolean | null>(null)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [question, setQuestion] = useState('')
  const [answer, setAnswer] = useState<SupportProgramEvidenceAnswer | null>(null)
  const [answerError, setAnswerError] = useState<string | null>(null)
  const [answering, setAnswering] = useState(false)
  const [expanded, setExpanded] = useState(false)
  const [questionOpen, setQuestionOpen] = useState(false)
  const [conditionCheck, setConditionCheck] = useState<SupportProgramConditionCheck | 'failed' | null>(null)
  const insets = useSafeAreaInsets()
  const work = useRef<AbortController | null>(null)
  const saveWork = useRef<AbortController | null>(null)
  const sourceCode = identity.sourceCode
  const sourceProgramId = identity.sourceProgramId
  const params = new URLSearchParams({ sourceCode, sourceProgramId }).toString()

  useEffect(() => {
    let active = true
    const controller = new AbortController()
    setLoading(true); setError(null); setProgram(null)
    client.getDetail({ sourceCode, sourceProgramId }, controller.signal)
      .then((value) => { if (active) setProgram(value ? toSupportProgramDetail(value) : null) })
      .catch((cause: unknown) => { if (active) setError(errorMessage(cause)) })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false; controller.abort() }
  }, [client, sourceCode, sourceProgramId, retry])

  useEffect(() => {
    let active = true
    const controller = new AbortController()
    work.current?.abort(); saveWork.current?.abort()
    setAnswer(null); setAnswerError(null); setQuestion(''); setAnswering(false)
    setSaved(null); setSaveError(null); setSaving(false)
    if (token) apiRequest(`/api/v1/me/saved-programs/status?${params}`, { accessToken: token, signal: controller.signal })
      .then((value) => { if (active) setSaved(savedSupportProgramStatusDtoSchema.parse(value).saved) })
      .catch((cause: unknown) => {
        if (!active) return
        if (cause instanceof ApiError && cause.status === 401) void invalidateSession().catch(() => undefined)
        setSaveError(errorMessage(cause))
      })
    return () => { active = false; controller.abort(); work.current?.abort(); saveWork.current?.abort() }
  }, [token, params, retry, invalidateSession])

  const analyzed = program?.analysis.status === 'COMPLETED'
  useEffect(() => {
    // 분석을 마친 공고만 회원의 회사 정보로 조건을 비교합니다. 실패해도 분석 내용은 그대로 보여 주고 비교 실패만 알립니다.
    setConditionCheck(null)
    if (!token || !analyzed) return
    let active = true
    const controller = new AbortController()
    apiRequest(`/api/v1/me/support-programs/condition-check?${params}`, { accessToken: token, signal: controller.signal })
      .then((value) => { if (active) setConditionCheck(toSupportProgramConditionCheck(supportProgramConditionCheckDtoSchema.parse(value))) })
      .catch((cause: unknown) => {
        if (!active) return
        if (cause instanceof ApiError && cause.status === 401) void invalidateSession().catch(() => undefined)
        setConditionCheck('failed')
      })
    return () => { active = false; controller.abort() }
  }, [token, analyzed, params, retry, invalidateSession])

  async function toggleSave() {
    if (!token) { onLogin(); return }
    if (saved === null || saving) return
    const controller = new AbortController(); saveWork.current = controller
    setSaving(true); setSaveError(null)
    try {
      if (saved) await apiRequest(`/api/v1/me/saved-programs?${params}`, { method: 'DELETE', accessToken: token, signal: controller.signal })
      else savedSupportProgramDtoSchema.parse(await apiRequest('/api/v1/me/saved-programs', { method: 'POST', body: { sourceCode, sourceProgramId }, accessToken: token, signal: controller.signal }))
      if (!controller.signal.aborted) setSaved(!saved)
    } catch (cause) {
      if (!controller.signal.aborted) {
        if (cause instanceof ApiError && cause.status === 401) void invalidateSession().catch(() => undefined)
        setSaveError(errorMessage(cause))
      }
    } finally { if (!controller.signal.aborted) setSaving(false) }
  }

  async function ask() {
    if (!token) { onLogin(); return }
    if (!question.trim() || answering) return
    const controller = new AbortController(); work.current = controller
    setAnswering(true); setAnswerError(null); setAnswer(null)
    try {
      const result = await client.answerEvidenceQuestion({ sourceCode, sourceProgramId, question: question.trim() }, controller.signal)
      if (!controller.signal.aborted) setAnswer(result)
    } catch (cause) {
      if (!controller.signal.aborted) {
        if (cause instanceof ApiError && cause.status === 401) void invalidateSession().catch(() => undefined)
        setAnswerError(errorMessage(cause))
      }
    } finally { if (!controller.signal.aborted) setAnswering(false) }
  }

  async function openSource(url: string) {
    try { await Linking.openURL(url) } catch { setError('원문 링크를 열지 못했습니다. 다시 시도해 주세요.') }
  }

  const deadline = program?.applicationEndDate ? partnerDeadlineDay(program.applicationEndDate) : null
  const statusColor = program?.status === 'OPEN' ? colors.primary : program?.status === 'UPCOMING' ? colors.info : colors.muted
  const target = program ? splitSupportProgramTarget(program.sourceCode, program.targetDescription) : null
  const routeLabel = program ? supportProgramApplicationRouteLabel(program.applicationRoute) : null
  const applicationUrl = program?.applicationRoute.url ?? null
  function closeQuestion() { work.current?.abort(); setAnswering(false); setQuestionOpen(false) }
  return <View style={local.page}><Page backgroundColor={colors.surface}>
    {loading && <ActivityIndicator color={colors.primary} accessibilityLabel="공고 상세를 불러오는 중" />}
    {error && <><Notice error>{error}</Notice><Button label="다시 불러오기" onPress={() => setRetry((value) => value + 1)} /></>}
    {!loading && !error && !program && <Notice>공고를 찾을 수 없습니다. 공고가 삭제되었거나 더 이상 제공되지 않을 수 있습니다.</Notice>}
    {program && <>
      <View style={local.meta}><View style={[local.dot, { backgroundColor: statusColor }]} /><Text style={[local.status, { color: statusColor }]}>{statusLabels[program.status]}</Text>
        {deadline !== null && <StatusBadge label={deadline < 0 ? '마감' : deadline === 0 ? 'D-day' : `D-${deadline}`} tone={deadline >= 0 && deadline <= 3 ? 'warning' : 'neutral'} />}
        <View style={{ flex: 1 }} /><Text style={styles.muted}>{program.sourceName}</Text></View>
      <Title>{program.title}</Title>
      {program.analysis.status === 'COMPLETED' && program.analysis.summaryLine && <Text style={styles.body}><Text style={local.aiLabel}>AI 요약 </Text>{program.analysis.summaryLine}</Text>}
      <View style={local.glance}>{program.analysis.status === 'COMPLETED' && <ProgramAnalysisFacts analysis={program.analysis} />}<View style={local.fact}><Text style={styles.muted}>접수 기간</Text><Text style={[styles.body, local.factValue]}>{program.applicationPeriod}</Text></View>
        <View style={local.fact}><Text style={styles.muted}>신청 방법</Text><Text style={[routeLabel ? styles.body : styles.muted, local.factValue]}>{routeLabel ?? '공고 원문에서 확인해 주세요'}</Text></View></View>
      <ProgramAnalysisCard analysis={program.analysis} check={conditionCheck} signedIn={Boolean(token)} />
      <ProgramAnalysisPreparation analysis={program.analysis} />
      {token && saved && <ProgramPreparationSection key={`${token}:${sourceCode}:${sourceProgramId}`} identity={identity} token={token} />}
      {saveError && <><Notice error>{saveError}</Notice><Button variant="ghost" label="저장 상태 다시 확인" onPress={() => setRetry((value) => value + 1)} /></>}
      {expanded && <><Subtitle>{program.organization}</Subtitle><Card>
        <Text style={styles.heading}>지원 대상</Text><Text style={styles.body}>{target?.target || '원문을 확인해 주세요.'}</Text>
        {target?.excluded && <><Text style={styles.heading}>제외 대상</Text><Text style={styles.body}>{target.excluded}</Text></>}
        <Text style={styles.muted}>{program.regions.join(' · ')} / {program.categories.join(' · ')}</Text>
      </Card>
      <Card><Text style={styles.heading}>사업 내용</Text><Text selectable style={styles.body}>{program.summary || '공고 원문에서 확인해 주세요.'}</Text></Card>
      {program.applicationRoute.method && <Card><Text style={styles.heading}>신청 방법</Text><Text selectable style={styles.body}>{program.applicationRoute.method}</Text></Card>}
      {applicationUrl && <Button variant="secondary" label={program.applicationRoute.type === 'GOOGLE_FORMS' ? '구글 설문 신청서 열기' : '신청 사이트 열기'}
        onPress={() => void openSource(applicationUrl)} />}
      <Notice>공고 정보는 신청 자격의 확정 판정이 아닙니다. 제출 전 공식 공고의 요건과 마감일을 확인해 주세요.</Notice>
      <Button label={program.sourceCode === 'CNTRADE_NOTICE' ? '공식 공지 목록 열기' : '공식 공고 원문 열기'} onPress={() => void openSource(program.sourceUrl)} />
      </>}
    </>}
  </Page>
    {program && <View style={[local.footer, { paddingBottom: Math.max(insets.bottom, 12) }]}>
      <Pressable accessibilityRole="button" accessibilityLabel={!token ? '로그인하고 관심 공고 저장' : saved ? '관심 공고에서 빼기' : '관심 공고에 저장'}
        accessibilityState={{ selected: !!saved, disabled: saving || Boolean(token) && saved === null }} disabled={saving || Boolean(token) && saved === null}
        onPress={() => void toggleSave()} style={local.bookmark}>{saving ? <ActivityIndicator color={colors.primary} />
          : <AppIcon name="bookmark" color={colors.primary} selected={!!saved} size={21} />}</Pressable>
      <Button label={expanded ? '접기' : '더 보기'} variant="secondary" onPress={() => setExpanded(!expanded)} />
      <View style={{ flex: 1 }}><Button label="원문에 질문하기" onPress={() => setQuestionOpen(true)} /></View>
    </View>}
    <PartnerSheet visible={questionOpen} title="원문에 질문하기" onClose={closeQuestion} actions={<Button label="닫기" variant="secondary" onPress={closeQuestion} />}>
      {program && <>
        {!program.evidenceQuestionSupported ? <Notice>이 제공처 공고는 아직 원문 근거 답변을 지원하지 않습니다. 공식 공고 원문에서 확인해 주세요.</Notice>
        : <>
        <Subtitle>AI가 이 공고의 원문에서 근거를 찾아 답합니다.</Subtitle>
        {token ? <>
          <Field label="공고에 대해 궁금한 점" value={question} onChangeText={setQuestion} multiline maxLength={500}
            placeholder="신청할 때 필요한 서류는 무엇인가요?" editable={!answering} />
          <Button label="원문에서 답변 찾기" busy={answering} disabled={!question.trim()} onPress={() => void ask()} />
          {answering && <Button variant="ghost" label="답변 요청 취소" onPress={() => { work.current?.abort(); setAnswering(false) }} />}
        </> : <Button label="로그인하고 질문하기" variant="secondary" onPress={onLogin} />}
        {answerError && <Notice error>{answerError}</Notice>}
        {answer && <><Text style={styles.badge}>{answer.answerStatus === 'ANSWERED' ? 'AI 답변 · 원문 근거 포함' : '원문 근거 부족'}</Text>
          <Text selectable style={styles.body}>{answer.answer}</Text>
          {answer.citations.map((citation, index) => <Card key={`${citation.chunkOrder}-${index}`}>
            <Text selectable style={styles.body}>“{citation.excerpt}”</Text>
            <Button variant="ghost" label={`근거 ${index + 1} 원문 열기`} onPress={() => void openSource(citation.sourceUrl)} />
          </Card>)}
        </>}
        </>}
      </>}
    </PartnerSheet>
  </View>
}

const local = StyleSheet.create({
  page: { flex: 1, backgroundColor: colors.surface },
  meta: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  dot: { width: 5, height: 5, borderRadius: 3 },
  status: { fontSize: 12, fontWeight: '600' },
  aiLabel: { fontSize: 12, fontWeight: '700', color: colors.primary },
  glance: { backgroundColor: colors.background, borderRadius: 12, paddingHorizontal: 12 },
  fact: { minHeight: 48, flexDirection: 'row', alignItems: 'center', gap: 12, borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: colors.border },
  factValue: { flex: 1, textAlign: 'right' },
  footer: { flexDirection: 'row', alignItems: 'center', gap: 8, padding: 12, backgroundColor: colors.surface,
    borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: colors.border },
  bookmark: { width: 44, height: 44, borderRadius: 22, backgroundColor: colors.soft, alignItems: 'center', justifyContent: 'center' },
})

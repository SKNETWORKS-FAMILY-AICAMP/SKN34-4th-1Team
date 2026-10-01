import { useState, type ReactNode } from 'react'
import { Pressable, StyleSheet, Text, View } from 'react-native'
import type { SupportProgramAnalysis, SupportProgramAnalysisEvidence } from '@govbiz/shared/domain/entities/SupportProgram'
import {
  supportProgramConditionOverallLabels, supportProgramConditionReasonText, supportProgramConditionResultLabel, type SupportProgramConditionCheck,
} from '@govbiz/shared/domain/entities/SupportProgramConditionCheck'
import {
  groupSupportProgramConditions, supportProgramConditionCategoryLabels, supportProgramDocumentRequirementLabels, supportProgramEvidenceSourceLabel,
  supportProgramSupportTypeLabels,
} from '@govbiz/shared/domain/entities/SupportProgramSections'
import { Card, Notice, colors, styles } from '../ui'
import { AppIcon } from './AppIcon'

type CompletedAnalysis = Extract<SupportProgramAnalysis, { status: 'COMPLETED' }>

/** 한눈에 보기의 지원 규모·지원 형태 줄입니다. 분석을 마친 공고에만 두고, 본문에 없으면 없다고 밝힙니다. */
export function ProgramAnalysisFacts({ analysis }: { analysis: CompletedAnalysis }) {
  return <>
    <View style={local.fact}><Text style={styles.muted}>지원 규모</Text>
      <Text style={[analysis.supportAmount ? styles.body : styles.muted, local.factValue]}>{analysis.supportAmount?.text ?? '공고 본문에 명시 없음'}</Text></View>
    {analysis.supportTypes.length > 0 && <View style={local.fact}><Text style={styles.muted}>지원 형태</Text>
      <Text style={[styles.body, local.factValue]}>{analysis.supportTypes.map((type) => supportProgramSupportTypeLabels[type]).join(' · ')}</Text></View>}
  </>
}

/**
 * AI가 공고 원문에서 정리한 신청 조건·문의처 카드입니다. 항목마다 ! 아이콘을 누르면 어느 부분의 원문을 인용했는지 보여 줍니다.
 * 분석 전 공고는 카드를 두지 않고, 분석 실패는 숨기지 않고 원문 확인을 안내합니다.
 */
export function ProgramAnalysisCard({ analysis, check, signedIn }: {
  analysis: SupportProgramAnalysis
  /** 회사 정보 기준 조건 판정입니다. 불러오는 중이면 `null`, 비교에 실패했으면 `'failed'`입니다. */
  check: SupportProgramConditionCheck | 'failed' | null
  signedIn: boolean
}) {
  if (analysis.status === 'NOT_ANALYZED') return null
  if (analysis.status === 'FAILED') return <Notice>이 공고는 AI가 조건을 정리하지 못했어요. 신청 조건은 공고 내용과 원문 공고에서 확인해 주세요.</Notice>
  const groups = groupSupportProgramConditions(analysis.conditions)
  // 다른 분석으로 판정한 결과는 조건 순서가 어긋날 수 있어 쓰지 않습니다.
  const checked = check !== 'failed' && check?.status === 'CHECKED' && check.analyzedAt === analysis.analyzedAt ? check : null
  return <Card>
    <Text style={styles.heading}>공고 분석</Text>
    {!signedIn && <Text style={styles.muted}>로그인하면 회사 소재지·업력 조건을 비교해 드려요.</Text>}
    {check === 'failed' && <Text style={styles.muted}>회사 조건과 비교하지 못했어요. 화면을 다시 열어 주세요.</Text>}
    {check !== 'failed' && check?.status === 'NO_COMPANY' && <Text style={styles.muted}>회사 정보를 등록하면 소재지·업력 조건을 비교해 드려요.</Text>}
    {checked && <Text style={styles.body}><Text style={local.overall}>{supportProgramConditionOverallLabels[checked.overall]}</Text>
      {` · 내 회사 기준(소재지 ${checked.profile.region ?? '미등록'}, 설립 ${checked.profile.foundedYear ?? '미등록'}) · 그 밖의 조건은 직접 확인`}</Text>}
    {groups.length === 0 && <Text style={styles.muted}>공고 본문에 명시된 신청 조건이 없어요.</Text>}
    {groups.map((group) => <View key={group.kind} style={local.group}>
      <Text style={styles.label}>{group.title}</Text>
      {group.entries.map(({ condition, index }) => <EvidenceItem key={index} evidence={condition.evidence}>
        <Text style={styles.body}>[{supportProgramConditionCategoryLabels[condition.category]}] {condition.text}</Text>
        {checked?.conditions.filter((item) => item.index === index).map((item) => <Text key="check" style={styles.muted}>
          {supportProgramConditionResultLabel(condition.kind, item.result)} · {supportProgramConditionReasonText(item.reason, checked.profile)}</Text>)}
      </EvidenceItem>)}
    </View>)}
    {analysis.contact && <View style={local.group}><Text style={styles.label}>문의처</Text>
      <EvidenceItem evidence={analysis.contact.evidence}><Text selectable style={styles.body}>{analysis.contact.text}</Text></EvidenceItem></View>}
    <Text style={styles.muted}>AI 정리 · {analysis.analyzedAt.slice(0, 10)} · 원문 인용이 확인된 내용만 담았어요.</Text>
  </Card>
}

/**
 * 신청 준비 카드입니다. 공고 분석이 첨부 공고문까지 읽어 정리한 일정·제출 서류·선정 절차·평가 기준이며, 모두 비어 있으면 두지 않습니다.
 */
export function ProgramAnalysisPreparation({ analysis }: { analysis: SupportProgramAnalysis }) {
  if (analysis.status !== 'COMPLETED') return null
  const { schedule, requiredDocuments, selectionSteps, evaluationCriteria } = analysis
  if (!schedule.length && !requiredDocuments.length && !selectionSteps.length && !evaluationCriteria.length) return null
  return <Card>
    <Text style={styles.heading}>신청 준비</Text>
    {schedule.length > 0 && <View style={local.group}><Text style={styles.label}>일정</Text>
      {schedule.map((entry, index) => <EvidenceItem key={index} evidence={entry.evidence}>
        <Text style={styles.body}>{entry.date ?? '날짜 미정'} · {entry.label} · {entry.text}</Text></EvidenceItem>)}</View>}
    {requiredDocuments.length > 0 && <View style={local.group}><Text style={styles.label}>제출 서류</Text>
      {requiredDocuments.map((document, index) => <EvidenceItem key={index} evidence={document.evidence}>
        <Text style={styles.body}>[{supportProgramDocumentRequirementLabels[document.requirement]}] {document.name}{document.note ? ` · ${document.note}` : ''}</Text>
      </EvidenceItem>)}</View>}
    {selectionSteps.length > 0 && <View style={local.group}><Text style={styles.label}>선정 절차</Text>
      {selectionSteps.map((step, index) => <EvidenceItem key={index} evidence={step.evidence}>
        <Text style={styles.body}>{index + 1}단계 · {step.name}{step.note ? ` · ${step.note}` : ''}</Text></EvidenceItem>)}</View>}
    {evaluationCriteria.length > 0 && <View style={local.group}><Text style={styles.label}>평가 기준</Text>
      {evaluationCriteria.map((criterion, index) => <EvidenceItem key={index} evidence={criterion.evidence}>
        <Text style={styles.body}>{criterion.item} · {criterion.points === null ? '배점 미기재' : `${criterion.points}점`}</Text>
      </EvidenceItem>)}</View>}
    <Text style={styles.muted}>AI 정리{analysis.sourceAttachmentNames.length ? ` · 첨부 ${analysis.sourceAttachmentNames.length}개` : ''} · 제출 전 원문 공고의 서류·일정을 다시 확인해 주세요.</Text>
  </Card>
}

/** 분석 항목 한 줄입니다. 원문 인용은 길어서 접어 두고, 항목 오른쪽 ! 아이콘을 누르면 출처와 함께 펼칩니다. */
function EvidenceItem({ evidence, children }: { evidence: SupportProgramAnalysisEvidence; children: ReactNode }) {
  const [open, setOpen] = useState(false)
  return <View style={local.item}>
    <View style={local.itemRow}>
      <View style={local.itemText}>{children}</View>
      <Pressable accessibilityRole="button" accessibilityLabel="원문 근거 보기" accessibilityState={{ expanded: open }} hitSlop={10}
        onPress={() => setOpen((value) => !value)} style={local.hintButton}>
        <AppIcon name="info" color={open ? colors.primary : colors.muted} size={16} />
      </Pressable>
    </View>
    {open && <Text selectable style={local.quote}>원문({supportProgramEvidenceSourceLabel(evidence)}): “{evidence.quote}”</Text>}
  </View>
}

const local = StyleSheet.create({
  fact: { minHeight: 48, flexDirection: 'row', alignItems: 'center', gap: 12, borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: colors.border },
  factValue: { flex: 1, textAlign: 'right' },
  group: { gap: 6 },
  item: { gap: 2, paddingVertical: 4 },
  itemRow: { flexDirection: 'row', alignItems: 'flex-start', gap: 6 },
  itemText: { flex: 1, gap: 2 },
  hintButton: { paddingTop: 3 },
  quote: { fontSize: 12, lineHeight: 18, color: colors.muted },
  overall: { fontWeight: '700', color: colors.primary },
})

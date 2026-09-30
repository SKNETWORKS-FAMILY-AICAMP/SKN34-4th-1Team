import { Pressable, Text, View } from 'react-native'
import type { SupportProgram } from '@govbiz/shared/domain/entities/SupportProgram'
import type { SupportProgramIdentity } from '@govbiz/shared/domain/repositories/SupportProgramRepository'
import { supportProgramAnalysisFacts } from '@govbiz/shared/domain/entities/SupportProgramSections'
import { Card, StatusBadge, styles } from '../ui'

export const statusLabels = { OPEN: '접수 중', UPCOMING: '접수 예정', CLOSED: '마감', UNKNOWN: '상태 미확인' }

export function ProgramCard({ program, onOpen }: { program: SupportProgram; onOpen: (identity: SupportProgramIdentity) => void }) {
  // 검색 결과는 웹 카드와 같은 기준으로 자격 판정을 표시하고, 판정하지 않은 카탈로그 목록은 뱃지를 두지 않습니다.
  const eligibility = program.eligibilityReview?.status === 'MATCH' ? '조건 확인'
    : program.eligibilityReview || program.recommendationScore !== null ? '확인 필요' : null
  const facts = supportProgramAnalysisFacts(program.analysisSummary)
  return <Pressable accessibilityRole="button" accessibilityLabel={`${program.title}, 상세 보기`}
    onPress={() => onOpen({ sourceCode: program.sourceCode, sourceProgramId: program.id })}>
    <Card>
      <View style={styles.row}><StatusBadge label={statusLabels[program.status]}
        tone={program.status === 'OPEN' ? 'success' : program.status === 'UPCOMING' ? 'info' : 'neutral'} />
        {eligibility && <StatusBadge label={eligibility} tone={eligibility === '조건 확인' ? 'success' : 'warning'} />}
        <Text style={styles.muted}>{program.sourceName}</Text></View>
      <Text style={styles.heading}>{program.title}</Text>
      <Text style={styles.muted}>{program.organization}</Text>
      {/* 공고 분석을 마친 공고만 지원 규모·지원 형태를 한 줄로 덧붙입니다. */}
      {facts.length > 0 && <Text style={styles.label}>{facts.join(' · ')}</Text>}
      <Text style={styles.body} numberOfLines={2}>{program.summary}</Text>
      <Text style={styles.muted}>{program.applicationPeriod}</Text>
      {program.regions.length > 0 && <Text style={styles.muted}>{program.regions.join(' · ')}</Text>}
      {program.matchedReasons.map((reason) => <Text key={reason} style={styles.muted}>• {reason}</Text>)}
      <Text style={[styles.label, { textAlign: 'right' }]}>공고 상세 →</Text>
    </Card>
  </Pressable>
}

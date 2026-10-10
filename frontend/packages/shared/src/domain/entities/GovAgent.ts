import type { SupportProgramInterpretation, SupportProgramInterpretRequest } from './SupportProgramConversation'
import type { SupportProgramEvidenceAnswer } from './SupportProgramEvidenceAnswer'
import type { SupportProgramIdentity } from '../repositories/SupportProgramRepository'

export type GovAgentProgram = SupportProgramIdentity & { title: string }
export type GovAgentRequest = { conversation: SupportProgramInterpretRequest; selectedProgram: SupportProgramIdentity | null }
export type GovAgentResult =
  | { outcome: 'SEARCH'; interpretation: SupportProgramInterpretation }
  | { outcome: 'EVIDENCE'; program: SupportProgramIdentity; evidence: SupportProgramEvidenceAnswer }
  | { outcome: 'APPLICATION'; program: SupportProgramIdentity; message: string }
  | { outcome: 'NEEDS_PROGRAM' | 'UNSUPPORTED'; message: string }
export type GovAgentEvidence = { program: GovAgentProgram; answer: SupportProgramEvidenceAnswer }
/** 선택 공고의 신청 준비 진입점입니다. 실행 권한·양식·작업 결과는 기존 신청 준비 API에서 다시 확인합니다. */
export type GovAgentApplication = { program: GovAgentProgram; message: string }

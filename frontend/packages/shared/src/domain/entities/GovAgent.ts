import type { SupportProgramInterpretation, SupportProgramInterpretRequest } from './SupportProgramConversation'
import type { SupportProgramEvidenceAnswer } from './SupportProgramEvidenceAnswer'
import type { SupportProgramIdentity } from '../repositories/SupportProgramRepository'

export type GovAgentProgram = SupportProgramIdentity & { title: string }
export type GovAgentRequest = { conversation: SupportProgramInterpretRequest; selectedProgram: SupportProgramIdentity | null }
export type GovAgentResult =
  | { outcome: 'SEARCH'; interpretation: SupportProgramInterpretation }
  | { outcome: 'EVIDENCE'; program: SupportProgramIdentity; evidence: SupportProgramEvidenceAnswer }
  | { outcome: 'NEEDS_PROGRAM' | 'UNSUPPORTED'; message: string }
export type GovAgentEvidence = { program: GovAgentProgram; answer: SupportProgramEvidenceAnswer }

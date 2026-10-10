import { z } from 'zod'
import type { GovAgentResult } from '../../domain/entities/GovAgent'
import type { SupportProgramIdentity } from '../../domain/repositories/SupportProgramRepository'
import { supportProgramInterpretationDtoSchema } from './SupportProgramConversationDto'
import { parseSupportProgramEvidenceAnswerDto, supportProgramEvidenceAnswerDtoSchema } from './SupportProgramEvidenceAnswerDto'
import { isOfficialSupportProgramSourceUrl } from './SupportProgramDto'

const identitySchema = z.object({ sourceCode: z.string().regex(/^[A-Z][A-Z0-9_]{0,63}$/),
  sourceProgramId: z.string().min(1).refine((value) => Array.from(value).length <= 255 && value.trim() === value && !/\p{C}/u.test(value)) })
export const govAgentProgramSchema = identitySchema.extend({ title: z.string().min(1).max(1_000) })
export const govAgentEvidenceSchema = z.object({ program: govAgentProgramSchema, answer: supportProgramEvidenceAnswerDtoSchema })
  .refine((value) => value.answer.citations.every((citation) => isOfficialSupportProgramSourceUrl(value.program.sourceCode, citation.sourceUrl)),
    '근거는 선택한 제공처의 공식 원문이어야 합니다.')

const resultSchema = z.discriminatedUnion('outcome', [
  z.object({ outcome: z.literal('SEARCH'), interpretation: supportProgramInterpretationDtoSchema,
    evidence: z.null().optional(), program: z.null().optional(), message: z.null().optional() }),
  z.object({ outcome: z.literal('EVIDENCE'), evidence: supportProgramEvidenceAnswerDtoSchema, program: identitySchema,
    interpretation: z.null().optional(), message: z.null().optional() }),
  z.object({ outcome: z.enum(['NEEDS_PROGRAM', 'UNSUPPORTED']), message: z.string().min(1).max(1_000),
    interpretation: z.null().optional(), evidence: z.null().optional(), program: z.null().optional() }),
])

export function parseGovAgentResult(payload: unknown, selectedProgram: SupportProgramIdentity | null): GovAgentResult {
  const result = resultSchema.parse(payload)
  if (result.outcome === 'EVIDENCE') {
    if (!selectedProgram || result.program.sourceCode !== selectedProgram.sourceCode
      || result.program.sourceProgramId !== selectedProgram.sourceProgramId) throw new Error('Gov agent program mismatch')
    return { ...result, evidence: parseSupportProgramEvidenceAnswerDto(result.evidence, selectedProgram.sourceCode) }
  }
  return result
}

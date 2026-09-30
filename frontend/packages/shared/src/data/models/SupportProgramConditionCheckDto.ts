import { z } from 'zod'

import type { SupportProgramConditionCheck } from '../../domain/entities/SupportProgramConditionCheck'

const resultSchema = z.enum(['MET', 'NOT_MET', 'UNKNOWN'])

/** `GET /api/v1/me/support-programs/condition-check` 응답입니다. 판정을 마친 경우에만 분석 시각·회사 정보·조건 결과가 있습니다. */
export const supportProgramConditionCheckDtoSchema = z.object({
  status: z.enum(['CHECKED', 'NO_COMPANY', 'NOT_ANALYZED']),
  analyzedAt: z.iso.datetime({ local: true, offset: true }).nullable(),
  referenceDate: z.iso.date(),
  profile: z.object({ region: z.string().max(40).nullable(), foundedYear: z.number().int().nullable() }).nullable(),
  overall: resultSchema.nullable(),
  conditions: z.array(z.object({
    index: z.number().int().nonnegative(),
    result: resultSchema,
    reason: z.enum(['REGION_MATCH', 'REGION_MISMATCH', 'BUSINESS_AGE_WITHIN', 'BUSINESS_AGE_OUTSIDE',
      'BOUNDARY_YEAR', 'PRE_STARTUP_ONLY', 'PROFILE_MISSING', 'NOT_COMPARABLE']),
  })).max(30),
}).superRefine((check, context) => {
  const checked = check.status === 'CHECKED'
  const complete = check.analyzedAt !== null && check.profile !== null && check.overall !== null
  const indexes = check.conditions.map((condition) => condition.index)
  if (checked ? !complete || new Set(indexes).size !== indexes.length : check.conditions.length > 0 || check.overall !== null) {
    context.addIssue({ code: 'custom', message: '판정을 마친 응답만 분석 시각·회사 정보·조건 결과를 가질 수 있습니다.' })
  }
})

export type SupportProgramConditionCheckDto = z.infer<typeof supportProgramConditionCheckDtoSchema>

export function toSupportProgramConditionCheck(dto: SupportProgramConditionCheckDto): SupportProgramConditionCheck {
  if (dto.status !== 'CHECKED' || dto.analyzedAt === null || dto.profile === null || dto.overall === null) {
    return { status: dto.status === 'NO_COMPANY' ? 'NO_COMPANY' : 'NOT_ANALYZED' }
  }
  return {
    status: 'CHECKED',
    analyzedAt: dto.analyzedAt,
    referenceDate: dto.referenceDate,
    profile: { ...dto.profile },
    overall: dto.overall,
    conditions: dto.conditions.map((condition) => ({ ...condition })),
  }
}

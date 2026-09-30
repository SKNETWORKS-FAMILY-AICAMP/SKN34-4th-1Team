import type { SupportProgramIdentity } from '../../domain/repositories/SupportProgramRepository'
import { AccountApiError } from './accountApi'
import { getCoreApiBaseUrl } from './coreApiConfig'
import { supportProgramConditionCheckDtoSchema, type SupportProgramConditionCheckDto } from '../models/SupportProgramConditionCheckDto'

/** 회원의 회사 정보로 판정하므로 세션 쿠키를 보내고, 없는 공고(404)는 `null`로 돌려줍니다. */
export async function getSupportProgramConditionCheckApi(
  identity: SupportProgramIdentity,
  signal?: AbortSignal,
): Promise<SupportProgramConditionCheckDto | null> {
  const params = new URLSearchParams({ sourceCode: identity.sourceCode, sourceProgramId: identity.sourceProgramId })
  const response = await fetch(`${getCoreApiBaseUrl()}/api/v1/me/support-programs/condition-check?${params}`, {
    headers: { Accept: 'application/json' },
    credentials: 'include',
    cache: 'no-store',
    signal,
  })
  if (response.status === 404) return null
  if (!response.ok) throw new AccountApiError(response.status, null)
  return supportProgramConditionCheckDtoSchema.parse(await response.json())
}

import type { SupportProgramConditionCheck } from '../../domain/entities/SupportProgramConditionCheck'
import type { SupportProgramConditionCheckRepository } from '../../domain/repositories/SupportProgramConditionCheckRepository'
import type { SupportProgramIdentity } from '../../domain/repositories/SupportProgramRepository'
import { getSupportProgramConditionCheckApi } from '../api/supportProgramConditionCheckApi'
import { toSupportProgramConditionCheck } from '../models/SupportProgramConditionCheckDto'

/** Core API 조건 판정 DTO를 Domain 값으로 바꾸는 adapter입니다. */
export class SupportProgramConditionCheckRepositoryImpl implements SupportProgramConditionCheckRepository {
  async check(identity: SupportProgramIdentity, signal?: AbortSignal): Promise<SupportProgramConditionCheck | null> {
    const dto = await getSupportProgramConditionCheckApi(identity, signal)
    return dto ? toSupportProgramConditionCheck(dto) : null
  }
}

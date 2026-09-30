import type { SupportProgramConditionCheck } from '../entities/SupportProgramConditionCheck'
import type { SupportProgramConditionCheckRepository } from '../repositories/SupportProgramConditionCheckRepository'
import type { SupportProgramIdentity } from '../repositories/SupportProgramRepository'

/** 공고 상세가 "내 조건과 비교"를 그리기 위해 회사 정보 기준 조건 판정을 읽습니다. */
export class CheckSupportProgramConditionsUseCase {
  private readonly repository: SupportProgramConditionCheckRepository

  constructor(repository: SupportProgramConditionCheckRepository) {
    this.repository = repository
  }

  execute(identity: SupportProgramIdentity, signal?: AbortSignal): Promise<SupportProgramConditionCheck | null> {
    if (identity.sourceCode.trim() === '' || identity.sourceProgramId.trim() === '') {
      throw new RangeError('support program identity must not be blank')
    }
    return this.repository.check(identity, signal)
  }
}

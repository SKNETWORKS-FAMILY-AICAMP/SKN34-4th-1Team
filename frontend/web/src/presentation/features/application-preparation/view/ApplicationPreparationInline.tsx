import type { GovAgentProgram } from '@govbiz/shared/domain/entities/GovAgent'
import { useId } from 'react'
import { Link } from 'react-router'
import { appPaths } from '../../../shared/routes/appPaths'
import { WorkspaceToast } from '../../../shared/workspace/WorkspaceToast'
import { useApplicationPreparationNewViewModel } from '../viewmodel/useApplicationPreparationNewViewModel'
import { newPreparationStyles as n } from './ApplicationPreparation.styles'
import { ApplicationPreparationStartAction, FormSectionBody } from './ApplicationPreparationNewPage'

/** 선택한 공고의 신청 준비를 기존 새 문서 흐름 그대로 대화 안에서 진행합니다. */
export function ApplicationPreparationInline({ program }: { program: GovAgentProgram }) {
  const vm = useApplicationPreparationNewViewModel(program.sourceCode, program.sourceProgramId)
  const headingId = useId()
  const path = `${appPaths.applicationPreparationNew}?${new URLSearchParams({ sourceCode: program.sourceCode, sourceProgramId: program.sourceProgramId })}`
  return <section className="mt-3 flex flex-col gap-4" aria-labelledby={headingId}>
    <h2 className={n.sectionTitle} id={headingId}>신청 준비 · {program.title}</h2>
    <p className={n.muted}>저장된 양식을 확인하고 작성할 양식과 분야를 골라 주세요. 입력칸별 AI 분석은 버튼을 눌러 시작합니다.</p>
    {vm.programLoad.status === 'loading' && <p role="status" className={n.muted}>공고를 불러오는 중입니다.</p>}
    {vm.programLoad.status === 'failed' && <div role="alert" className={`${n.alert} ${n.alertDanger}`}>
      <p>{vm.programLoad.error.message}</p>
      <button type="button" className={n.secondarySm} onClick={vm.retryProgramLoad}>다시 시도</button>
    </div>}
    {vm.program && <>
      <FormSectionBody vm={vm} />
      <ApplicationPreparationStartAction vm={vm}>
        <Link className={n.ghost} to={path}>신청 준비 화면에서 열기</Link>
      </ApplicationPreparationStartAction>
    </>}
    <WorkspaceToast notice={vm.toast} onClose={vm.dismissToast} />
  </section>
}

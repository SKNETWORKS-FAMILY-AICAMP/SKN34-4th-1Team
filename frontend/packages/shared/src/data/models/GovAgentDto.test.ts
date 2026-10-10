import { describe, expect, it } from 'vitest'
import { govAgentEvidenceSchema, parseGovAgentResult } from './GovAgentDto'

const program = { sourceCode: 'BIZINFO', sourceProgramId: 'P001' }
const answer = { answer: '온라인 신청입니다.', answerStatus: 'ANSWERED', citations: [{ excerpt: '온라인 신청',
  sourceUrl: 'https://www.bizinfo.go.kr/web/lay1/bbs/S1T122C128/AS/74/view.do?pblancId=P001', sourceLabel: '기업마당 상세 본문', chunkOrder: 0 }] }

describe('Gov agent public contract', () => {
  it('keeps the selected identity and validated official citations', () => {
    expect(parseGovAgentResult({ outcome: 'EVIDENCE', program, evidence: answer }, program)).toMatchObject({ evidence: answer })
  })
  it('rejects another provider with the same original id', () => {
    expect(() => parseGovAgentResult({ outcome: 'EVIDENCE', program: { ...program, sourceCode: 'KSTARTUP' }, evidence: answer }, program)).toThrow()
  })
  it('rejects evidence without selection or citations', () => {
    expect(() => parseGovAgentResult({ outcome: 'EVIDENCE', program, evidence: answer }, null)).toThrow()
    expect(() => parseGovAgentResult({ outcome: 'EVIDENCE', program, evidence: { ...answer, citations: [] } }, program)).toThrow()
  })
  it('rejects nonofficial links from API and saved history', () => {
    const invalid = { ...answer, citations: [{ ...answer.citations[0], sourceUrl: 'https://example.com' }] }
    expect(() => parseGovAgentResult({ outcome: 'EVIDENCE', program, evidence: invalid }, program)).toThrow()
    expect(govAgentEvidenceSchema.safeParse({ program: { ...program, title: '공고' }, answer: invalid }).success).toBe(false)
  })
  it('rejects mixed operations or unknown actions', () => {
    expect(() => parseGovAgentResult({ outcome: 'DELETE', message: '완료' }, program)).toThrow()
    expect(() => parseGovAgentResult({ outcome: 'UNSUPPORTED', message: '미지원', evidence: answer }, program)).toThrow()
  })
})

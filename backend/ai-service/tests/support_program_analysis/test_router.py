import json
import logging

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.support_program_analysis.agent import SupportProgramAnalysisAgent
from app.support_program_analysis.errors import SupportProgramAnalysisError, SupportProgramAnalysisTimeoutError
from app.support_program_analysis.models import (
    ANALYSIS_VERSION,
    SupportProgramAnalysisRequest,
    SupportProgramAnalysisResponse,
)
from tests.langchain_stub import ResponsesChatStub, response_message
from tests.support_program_analysis.test_agent import evidence, expected_response, output_data, request_data


PATH = "/internal/v1/support-program-analyses/analyze"
TEST_SETTINGS = Settings(
    openai_api_key="test-key",
    openai_model="unused-model",
    llm_model_timeout_seconds=2.0,
    llm_run_timeout_seconds=2.5,
)


class RecordingAgent(SupportProgramAnalysisAgent):
    def __init__(self, error: Exception | None = None) -> None:
        self.requests: list[SupportProgramAnalysisRequest] = []
        self._error = error

    async def analyze(self, request: SupportProgramAnalysisRequest) -> SupportProgramAnalysisResponse:
        self.requests.append(request)
        if self._error is not None:
            raise self._error
        return SupportProgramAnalysisResponse.model_validate({
            "analysisVersion": ANALYSIS_VERSION, "model": "fixed-model", "discardedItemCount": 0,
            "summaryLine": None, "supportTypes": [], "supportAmount": None, "selectionScale": None,
            "conditions": [], "contact": None, "requiredDocuments": [], "selectionSteps": [],
            "evaluationCriteria": [], "schedule": [],
        })


def client_for(agent: SupportProgramAnalysisAgent) -> TestClient:
    return TestClient(create_app(settings=TEST_SETTINGS, support_program_analysis_agent=agent))


def test_router_is_registered_on_the_internal_path():
    application = create_app(settings=TEST_SETTINGS, support_program_analysis_agent=RecordingAgent())
    operation = application.openapi()["paths"][PATH]["post"]
    assert operation["tags"] == ["internal"]
    assert set(operation["responses"]) == {"200", "422"}


def test_returns_verified_extraction_through_http_contract():
    output = output_data()
    output["supportTypes"] = ["GRANT", "GRANT"]
    output["conditions"].append({**output["conditions"][0], "evidence": evidence("DETAIL_TEXT", "없는 문장")})
    output["schedule"].append({**output["schedule"][0], "evidence": evidence("ATTACHMENT", "접수기간", 7)})
    stub = ResponsesChatStub([[response_message(json.dumps(output, ensure_ascii=False))]])
    agent = SupportProgramAnalysisAgent(model=stub.model, model_timeout_seconds=2, run_timeout_seconds=2.5)

    response = client_for(agent).post(PATH, json=request_data())

    assert response.status_code == 200
    body = response.json()
    assert body == expected_response(output_data(), discarded=2)
    assert list(body) == [
        "summaryLine", "supportTypes", "supportAmount", "selectionScale", "conditions", "contact",
        "requiredDocuments", "selectionSteps", "evaluationCriteria", "schedule",
        "analysisVersion", "model", "discardedItemCount",
    ]
    assert body["analysisVersion"] == "govbiz-support-program-analysis-v2"
    assert body["conditions"][1]["values"] == {
        "regions": None, "minYears": None, "maxYears": 7, "minAge": None, "maxAge": None,
    }
    assert body["requiredDocuments"][1] == {
        "name": "법인등기부등본", "requirement": "CONDITIONAL", "note": "법인만 해당",
        "evidence": {"field": "ATTACHMENT", "quote": "법인등기부등본(법인만 해당)", "attachmentName": "공고문.hwp"},
    }
    assert body["schedule"][0]["date"] == "2026-10-01"
    assert body["evaluationCriteria"][0]["points"] == 40
    assert body["supportAmount"]["evidence"] == {"field": "SUMMARY", "quote": "최대 5천만원", "attachmentName": None}
    stub.assert_complete()


@pytest.mark.parametrize("overrides", [
    {"sourceCode": "B" * 64, "sourceProgramId": "P" * 255, "title": "T" * 500, "organization": "O" * 255,
     "summary": "S" * 20_000, "targetDescription": "D" * 8_000, "applicationPeriod": "A" * 1_000,
     "applicationMethod": "M" * 8_000, "detailText": "X" * 30_000,
     "attachments": [{"name": "N" * 255, "text": "Y" * 5_000} for _ in range(8)]},
    {"attachments": [{"name": "공고문.hwp", "text": "Y" * 40_000}]},
    {"attachments": [{"name": "a.hwp", "text": "Y" * 20_000}, {"name": "b.pdf", "text": "Z" * 20_000}]},
    {"attachments": []},
    {"organization": "", "summary": "", "targetDescription": "", "applicationPeriod": "",
     "applicationMethod": None, "detailText": None},
])
def test_accepts_boundary_lengths_and_nullable_fields(overrides):
    agent = RecordingAgent()

    response = client_for(agent).post(PATH, json=request_data(**overrides))

    assert response.status_code == 200
    assert len(agent.requests) == 1


def test_nullable_fields_may_be_omitted():
    agent = RecordingAgent()
    body = request_data()
    del body["applicationMethod"], body["detailText"], body["attachments"]

    assert client_for(agent).post(PATH, json=body).status_code == 200
    assert agent.requests[0].application_method is None and agent.requests[0].detail_text is None
    assert agent.requests[0].attachments == []


@pytest.mark.parametrize("overrides", [
    {"sourceCode": ""}, {"sourceCode": "B" * 65},
    {"sourceProgramId": ""}, {"sourceProgramId": "P" * 256},
    {"title": ""}, {"title": "T" * 501},
    {"organization": "O" * 256},
    {"summary": "S" * 20_001},
    {"targetDescription": "D" * 8_001},
    {"applicationPeriod": "A" * 1_001},
    {"applicationMethod": "M" * 8_001},
    {"detailText": "X" * 30_001},
    {"attachments": None},
    {"attachments": [{"name": "a.hwp", "text": "Y"}] * 9},
    {"attachments": [{"name": "", "text": "Y"}]},
    {"attachments": [{"name": "N" * 256, "text": "Y"}]},
    {"attachments": [{"name": "a.hwp", "text": ""}]},
    {"attachments": [{"name": "a.hwp", "text": "Y" * 40_001}]},
    {"attachments": [{"name": "a.hwp", "text": "Y" * 20_001}, {"name": "b.pdf", "text": "Z" * 20_000}]},
    {"attachments": [{"name": "a.hwp", "text": "Y" * 5_001} for _ in range(8)]},
    {"attachments": [{"name": "a.hwp", "text": "Y", "extra": "field"}]},
    {"attachments": [{"name": "a.hwp"}]},
    {"summary": None},
    {"unexpected": "field"},
])
def test_rejects_invalid_requests_with_422_without_calling_the_model(overrides):
    agent = RecordingAgent()

    response = client_for(agent).post(PATH, json=request_data(**overrides))

    assert response.status_code == 422
    assert agent.requests == []


@pytest.mark.parametrize("field", ["sourceCode", "sourceProgramId", "title", "summary", "targetDescription"])
def test_rejects_missing_required_fields(field):
    agent = RecordingAgent()
    body = request_data()
    del body[field]

    assert client_for(agent).post(PATH, json=body).status_code == 422
    assert agent.requests == []


@pytest.mark.parametrize("error,status,detail,kind", [
    (SupportProgramAnalysisError(), 503, "Support program analysis is temporarily unavailable.", "execution"),
    (SupportProgramAnalysisTimeoutError(), 504, "Support program analysis timed out.", "timeout"),
])
def test_agent_failures_map_to_503_and_504_with_allowlisted_logs(caplog, error, status, detail, kind):
    try:
        raise error from ValueError("private model output 서울 소재")
    except SupportProgramAnalysisError as raised:
        error = raised
    agent = RecordingAgent(error)

    with caplog.at_level(logging.WARNING, logger="app.support_program_analysis.router"):
        response = client_for(agent).post(PATH, json=request_data())

    assert response.status_code == status
    assert response.json() == {"detail": detail}
    log = "\n".join(record.getMessage() for record in caplog.records)
    assert f"failure_kind={kind}" in log and "error_type=ValueError" in log
    for private in ("private model output", "서울", "PBLN_000000000118979"):
        assert private not in log


def test_real_agent_timeout_returns_504():
    import asyncio

    async def slow(_):
        await asyncio.sleep(1)
        return [response_message(json.dumps(output_data(), ensure_ascii=False))]

    stub = ResponsesChatStub([slow])
    agent = SupportProgramAnalysisAgent(model=stub.model, model_timeout_seconds=0.05, run_timeout_seconds=0.5)

    assert client_for(agent).post(PATH, json=request_data()).status_code == 504


def test_invalid_model_output_returns_503():
    stub = ResponsesChatStub([[response_message('{"summaryLine": "잘린 출력"')]])
    agent = SupportProgramAnalysisAgent(model=stub.model, model_timeout_seconds=2, run_timeout_seconds=2.5)

    assert client_for(agent).post(PATH, json=request_data()).status_code == 503

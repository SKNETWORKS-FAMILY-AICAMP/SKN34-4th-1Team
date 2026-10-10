import json
from unittest.mock import AsyncMock

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from pydantic import ValidationError
from tests.langchain_stub import ResponsesChatStub, response_message

from app.gov_agent.agent import GovAgentSupervisor
from app.gov_agent.models import GovAgentRequest
from app.gov_agent.router import get_supervisor, router


@pytest.mark.anyio
@pytest.mark.parametrize("action", ["SEARCH", "EVIDENCE", "UNSUPPORTED"])
async def test_one_bounded_decision_without_executing_tools(action):
    model = ResponsesChatStub([[response_message(json.dumps({"action": action}))]])
    supervisor = GovAgentSupervisor(model=model.model, timeout_seconds=3)
    request = GovAgentRequest(message="이 공고 신청 방법은?", hasSelectedProgram=True)
    assert (await supervisor.decide(request)).action == action
    call = model.first_call
    assert call.body["store"] is False
    assert call.timeout == 3
    assert len(model.calls) == 1
    assert json.loads(call.input[0]["content"])["hasSelectedProgram"] is True
    model.assert_complete()


@pytest.mark.anyio
async def test_unknown_action_is_rejected_instead_of_falling_back_to_search():
    model = ResponsesChatStub([[response_message('{"action":"SHELL"}')]])
    with pytest.raises(ValueError):
        await GovAgentSupervisor(model=model.model, timeout_seconds=3).decide(
            GovAgentRequest(message="서버 초기화", hasSelectedProgram=False),
        )


@pytest.mark.parametrize("error,status", [(TimeoutError(), 504), (ValueError("private upstream text"), 503)])
def test_failure_is_explicit_without_exposing_upstream_text(error, status):
    app = FastAPI()
    app.include_router(router)
    supervisor = AsyncMock(spec=GovAgentSupervisor)
    supervisor.decide.side_effect = error
    app.dependency_overrides[get_supervisor] = lambda: supervisor
    with TestClient(app) as client:
        response = client.post("/internal/v1/gov-agent/decide", json={"message": "사업 검색", "hasSelectedProgram": False})
    assert response.status_code == status
    assert "private upstream text" not in response.text


def test_extra_authority_or_tool_fields_are_rejected():
    with pytest.raises(ValidationError):
        GovAgentRequest(message="사업 검색", hasSelectedProgram=False, role="ADMIN", toolUrl="http://invalid")

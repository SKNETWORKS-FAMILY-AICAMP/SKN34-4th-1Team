"""서버 없이 실행 가능한 실패 판정과 실제 SDK→HTTP 모델 대역 계약 검증."""

import asyncio
import importlib.util
import json
import subprocess
import sys
from http.server import ThreadingHTTPServer
from pathlib import Path
from threading import Event, Thread
from unittest.mock import Mock
from uuid import uuid4

import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import cancellation_probe as probe  # noqa: E402 - standalone tools live beside this test.
import cancellation_smoke as smoke  # noqa: E402
from cancellation_ingress import Relay  # noqa: E402


def load_runner():
    spec = importlib.util.spec_from_file_location(
        "cancellation_runner_fixture", HERE / "cancellation_runner.py"
    )
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


@pytest.fixture
def server():
    state = probe.Probe()
    instance = ThreadingHTTPServer(("127.0.0.1", 0), probe.handler(state))
    thread = Thread(target=instance.serve_forever, daemon=True)
    thread.start()
    yield state, f"http://127.0.0.1:{instance.server_port}"
    instance.shutdown()
    instance.server_close()
    thread.join(timeout=2)


def test_barrier_waits_for_explicit_release(server):
    state, base = server
    run_id = str(uuid4())
    state.configure(run_id, {"hold": "model_sent"})
    finished = Event()
    errors = []

    def send():
        try:
            code, body = smoke.request(base + "/model/" + run_id, {"model": "offline"})
            assert code == 200 and body["usage"]["output_tokens"] == 50
        except Exception as error:
            errors.append(error)
        finally:
            finished.set()

    thread = Thread(target=send, daemon=True)
    thread.start()
    try:
        smoke.wait_for(
            lambda: state.snapshot(run_id)["events"],
            bool,
            label="model received",
            timeout=2,
        )
        assert not finished.is_set()
        state.release(run_id)
        assert finished.wait(2)
        assert not errors
    finally:
        state.release(run_id)
        thread.join(timeout=2)


def test_lost_reply_forwards_once_but_never_returns_success(monkeypatch):
    state = probe.Probe()
    run_id = str(uuid4())
    state.configure(run_id, {"fault": "authorize_lost"})
    forwarded = Mock(return_value=(200, b'{"accepted":true}'))
    monkeypatch.setattr(probe, "exchange", forwarded)
    status, _ = state.forward(
        run_id, "authorize", "http://ops-service:8000/internal", "POST", b"{}", {}
    )
    assert status is None and forwarded.call_count == 1
    assert state.snapshot(run_id)["events"] == [
        {"stage": "authorize", "status": 200, "forwarded": True}
    ]


def test_http_failure_does_not_forward_or_claim_commit(monkeypatch):
    state = probe.Probe()
    run_id = str(uuid4())
    state.configure(run_id, {"fault": "settle_error"})
    forwarded = Mock()
    monkeypatch.setattr(probe, "exchange", forwarded)
    status, _ = state.forward(
        run_id, "settle", "http://ops-service:8000/internal", "POST", b"{}", {}
    )
    assert status == 503 and not forwarded.called
    assert state.snapshot(run_id)["events"][0]["forwarded"] is False


def test_timeout_is_failure(monkeypatch):
    monkeypatch.setattr(smoke.time, "monotonic", Mock(side_effect=[0, 2]))
    with pytest.raises(TimeoutError, match="still running"):
        smoke.wait_for(
            lambda: "CANCELLING",
            lambda value: value == "CANCELLED",
            label="still running",
            timeout=1,
        )


def test_barrier_timeout_is_recorded_as_failure():
    state = probe.Probe()
    run_id = str(uuid4())
    state.configure(run_id, {"hold": "model_sent"})
    state.runs[run_id]["release"] = Mock(wait=Mock(return_value=False))
    with pytest.raises(TimeoutError):
        state.gate(run_id, "model_sent")
    assert state.snapshot(run_id)["events"][-1]["stage"] == "barrier_timeout"


def accounting():
    before = {"allocated": [3, 100]}
    after = {
        "allocated": [4, 2100],
        "closed": True,
        "calls": [{"sequence": 0, "output_tokens": None}],
    }
    events = [{"stage": "authorize", "status": 200}, {"stage": "model_sent"}]
    return before, after, events


def test_unknown_usage_keeps_full_cap():
    before, after, events = accounting()
    assert smoke.verify_budget(
        before, after, calls=1, output=2000, closed=True, sent=1, events=events
    )["retained_delta"] == [1, 2000]


@pytest.mark.parametrize(
    "defect",
    [
        "refund_unknown",
        "double_send",
        "false_settle",
        "duplicate_sequence",
        "timeout",
        "still_open",
    ],
)
def test_invalid_accounting_cannot_pass(defect):
    before, after, events = accounting()
    if defect == "refund_unknown":
        after["allocated"][1] = 100
    elif defect == "double_send":
        events.append({"stage": "model_sent"})
    elif defect == "false_settle":
        events.append({"stage": "settle", "status": 200})
    elif defect == "duplicate_sequence":
        after["calls"].append(dict(after["calls"][0]))
    elif defect == "timeout":
        events.append({"stage": "barrier_timeout"})
    else:
        after["closed"] = False
    with pytest.raises(AssertionError):
        smoke.verify_budget(before, after, calls=1, output=2000, closed=True, sent=1, events=events)


def test_pid_reuse_is_not_a_live_evaluation(monkeypatch, tmp_path):
    runner = load_runner()
    marker = tmp_path / "process.json"
    marker.write_text(json.dumps({"pid": 123, "birth": "100"}))
    monkeypatch.setattr(
        runner, "process_info", lambda pid: {"pid": pid, "birth": "200", "alive": True}
    )
    assert runner.alive(marker) == {"started": True, "pid": 123, "alive": False}


def test_real_sdk_and_evaluator_accept_http_double(monkeypatch, tmp_path, server):
    import httpx2

    evaluation_dir = HERE.parents[1] / "evaluation/support-program-evidence"
    monkeypatch.syspath_prepend(str(evaluation_dir))
    import evaluate
    from budget_client import BudgetClient

    runner = load_runner()
    state, base = server
    run_id = str(uuid4())
    state.configure(run_id, {})
    monkeypatch.setattr(runner, "PROBE", base)
    # install_http_double의 국소 교체를 반드시 원복한다.
    monkeypatch.setattr(httpx2, "AsyncClient", httpx2.AsyncClient)
    monkeypatch.setattr(BudgetClient, "authorize", BudgetClient.authorize)
    monkeypatch.setattr(BudgetClient, "settle", BudgetClient.settle)
    monkeypatch.setenv("OPENAI_API_KEY", "offline-model-double-key")
    monkeypatch.setenv("LANGFUSE_ENABLED", "false")
    monkeypatch.setenv("LLMOPS_OPS_API_URL", base)
    monkeypatch.setenv("LLMOPS_BUDGET_TOKEN", probe.TOKEN)
    budget_actions = []

    def budget_http(url, method, body, headers):
        assert url.startswith(
            f"http://ops-service:8000/internal/llmops/evaluations/{run_id}/budget/"
        )
        assert method == "POST" and headers["Authorization"] == "Bearer " + probe.TOKEN
        budget_actions.append((url.rsplit("/", 1)[-1], json.loads(body)))
        return 200, b'{"accepted":true}'

    monkeypatch.setattr(probe, "exchange", budget_http)
    runner.install_http_double(run_id)
    budget = BudgetClient(run_id, str(uuid4()), "f" * 64)
    budget.claim()
    _, prepared, fixture_hash = evaluate.load_fixture(
        evaluation_dir / "target-coverage-fixture.json"
    )

    async def run():
        capture = await evaluate.execute(
            prepared[:1],
            fixture_hash,
            tmp_path / "capture",
            max_model_calls=1,
            budget=budget,
        )
        assert capture["completed"], capture["cases"]
        assert capture["modelApiCalls"] == 1
        assert capture["apiResponses"][0]["usage"] == probe.USAGE
        async with httpx2.AsyncClient() as client:
            with pytest.raises(RuntimeError, match="non-allowlisted"):
                await client.get("https://example.com/")

    asyncio.run(run())
    budget.close()
    assert [action for action, _ in budget_actions] == [
        "claim",
        "authorize",
        "settle",
        "close",
    ]
    assert budget_actions[1][1]["sequence"] == 0
    assert budget_actions[2][1]["usage"] == probe.USAGE
    assert [event["stage"] for event in state.snapshot(run_id)["events"]] == [
        "claim",
        "before_authorize_0",
        "authorize",
        "model_sent",
        "settle",
        "after_settle_0",
        "close",
    ]


def test_readiness_checks_real_langfuse_endpoint_without_model_calls(server, monkeypatch):
    state, base = server
    forwarded = Mock(return_value=(200, b'{"status":"OK"}'))
    monkeypatch.setattr(probe, "exchange", forwarded)
    assert smoke.request(base + "/health") == (200, {"ready": True})
    assert not forwarded.called
    assert smoke.request(base + "/langfuse-health") == (200, {"status": "OK"})
    forwarded.assert_called_once_with("http://langfuse-web:3000/api/public/health", "GET")
    assert not state.runs


@pytest.mark.parametrize(
    "address", ["", "invalid IP:0", "0.0.0.0:8099", "127.0.0.1:0", "127.0.0.1:65536"]
)
def test_missing_or_unsafe_published_port_fails_explicitly(address):
    compose = Mock(return_value=Mock(stdout=address))
    with pytest.raises(smoke.PortUnavailable) as raised:
        smoke.Smoke(compose).url("cancellation-ingress", 8099)
    assert raised.value.details["reason"] == "missing_loopback_binding"


def test_port_lookup_failure_does_not_leak_stderr():
    compose = Mock(side_effect=subprocess.CalledProcessError(1, ["docker"], stderr="secret"))
    with pytest.raises(smoke.PortUnavailable) as raised:
        smoke.Smoke(compose).url("cancellation-ingress", 8099)
    assert raised.value.details == {
        "service": "cancellation-ingress",
        "port": 8099,
        "reason": "lookup_failed",
        "returncode": 1,
    }
    assert "secret" not in str(raised.value)


def test_published_port_resolves_loopback():
    compose = Mock(return_value=Mock(stdout="127.0.0.1:32780\n"))
    assert smoke.Smoke(compose).url("cancellation-ingress", 8099) == "http://127.0.0.1:32780"


@pytest.mark.parametrize("array", [True, False])
def test_startup_diagnostics_exclude_commands_and_unexpected_fields(array):
    row = {
        "Service": "cancellation-ingress",
        "State": "running",
        "Health": "",
        "ExitCode": 0,
        "Command": "secret",
        "Labels": {"secret": "secret"},
        "Publishers": [
            {
                "URL": "127.0.0.1",
                "TargetPort": 8099,
                "PublishedPort": 32001,
                "Protocol": "tcp",
                "unexpected": "secret",
            }
        ],
    }
    compose = Mock(return_value=Mock(stdout=json.dumps([row] if array else row)))
    result = smoke.service_diagnostics(compose)
    assert result[0]["Service"] == "cancellation-ingress"
    assert result[0]["Publishers"][0]["PublishedPort"] == 32001
    assert "secret" not in json.dumps(result)


def isolated_config():
    return {
        "networks": {"default": {"internal": True}, "test-ingress": {}},
        "services": {
            "evaluation-runner": {"networks": {"default": None}},
            "cancellation-ingress": {
                "networks": {"default": None, "test-ingress": None},
                "ports": [{"host_ip": "127.0.0.1", "target": port} for port in (8099, 8000, 4200)],
            },
        },
    }


@pytest.mark.parametrize(
    "defect", ["external_runner", "public_port", "relay_secret", "non_internal"]
)
def test_compose_isolation_regressions_fail_before_startup(defect):
    config = isolated_config()
    smoke.verify_network_isolation(config)
    if defect == "external_runner":
        config["services"]["evaluation-runner"]["networks"]["test-ingress"] = None
    elif defect == "public_port":
        config["services"]["cancellation-ingress"]["ports"][0]["host_ip"] = "0.0.0.0"
    elif defect == "relay_secret":
        config["services"]["cancellation-ingress"]["environment"] = {"SECRET": "secret"}
    else:
        config["networks"]["default"]["internal"] = False
    with pytest.raises(AssertionError):
        smoke.verify_network_isolation(config)


def test_readiness_failure_preserves_evidence_before_cleanup(monkeypatch, tmp_path):
    output = tmp_path / "report.json"
    monkeypatch.setattr(sys, "argv", ["cancellation_smoke.py", "--output", str(output)])
    observed = []

    def command(args, **kwargs):
        # Compose command suffix starts after --profile evaluation.
        parts = args[args.index("--profile") + 2 :]
        observed.append(parts)
        if parts[0] == "config":
            return Mock(stdout=json.dumps(isolated_config()))
        if parts[0] == "port":
            raise subprocess.CalledProcessError(1, args, stderr="secret")
        if parts[0] == "ps":
            return Mock(stdout='{"Service":"cancellation-ingress","State":"exited","ExitCode":1}')
        if parts[0] == "down":
            assert output.exists(), "Evidence must be written before deleting the test project"
        return Mock(stdout="")

    monkeypatch.setattr(smoke.subprocess, "run", command)
    with pytest.raises(smoke.PortUnavailable):
        smoke.main()
    report = json.loads(output.read_text())
    assert report["passed"] is False and report["scenarios"] == []
    assert report["failure_details"]["phase"] == "ready"
    assert report["failure_details"]["returncode"] == 1
    assert report["services"][0]["State"] == "exited"
    assert observed[-1][0] == "down"
    assert "secret" not in output.read_text()


def test_wrong_python_version_fails_before_docker(monkeypatch, tmp_path):
    monkeypatch.setattr(
        sys, "argv", ["cancellation_smoke.py", "--output", str(tmp_path / "x.json")]
    )
    monkeypatch.setattr(smoke.sys, "version_info", (3, 9, 6))
    docker = Mock()
    monkeypatch.setattr(smoke.subprocess, "run", docker)
    with pytest.raises(SystemExit) as raised:
        smoke.main()
    assert raised.value.code == 2 and not docker.called


@pytest.mark.parametrize("recovers", [True, False])
def test_readiness_retries_non_json_startup_response_but_still_times_out(monkeypatch, recovers):
    instance = smoke.Smoke(Mock())
    monkeypatch.setattr(instance, "url", lambda service, port: f"http://127.0.0.1:{port}")
    monkeypatch.setattr(smoke, "request", Mock(return_value=(200, {})))
    monkeypatch.setattr(smoke.time, "sleep", Mock())
    monkeypatch.setattr(
        smoke.time, "monotonic", Mock(side_effect=[0, 0, 1, 1] if recovers else [0, 0, 181])
    )
    unavailable = json.JSONDecodeError("Not JSON", "<html>Upstream not ready</html>", 0)
    ready = (
        200,
        {
            "live_enabled": True,
            "user": {"id": 1},
            "csrf_token": "test-csrf",
            "datasets": [{"id": "target-coverage-20260907-v1"}],
        },
    )
    api = Mock(side_effect=[unavailable, ready] if recovers else unavailable)
    monkeypatch.setattr(instance, "api", api)
    if recovers:
        instance.ready()
        assert instance.csrf == "test-csrf" and api.call_count == 2
    else:
        with pytest.raises(TimeoutError, match="Ops readiness"):
            instance.ready()
        assert instance.csrf is None


@pytest.fixture
def relay(server):
    _, base = server
    instance = ThreadingHTTPServer(("127.0.0.1", 0), Relay)
    instance.upstream = ("127.0.0.1", int(base.rsplit(":", 1)[-1]))
    thread = Thread(target=instance.serve_forever, daemon=True)
    thread.start()
    yield f"http://127.0.0.1:{instance.server_port}"
    instance.shutdown()
    instance.server_close()
    thread.join(timeout=2)


def test_ingress_preserves_post_body_status_and_cookie_auth(server, relay):
    state, _ = server
    run_id = str(uuid4())
    assert smoke.request(relay + f"/control/{run_id}/configure", {"hold": "model_sent"})[0] == 200
    assert state.runs[run_id]["config"] == {"hold": "model_sent"}
    assert smoke.request(relay + "/api/v1/admin/session")[0] == 401
    status, body = smoke.request(
        relay + "/api/v1/admin/session", headers={"Cookie": "govbiz_session=offline-admin-session"}
    )
    assert status == 200 and body["accountId"] == 1


def test_ingress_preserves_redirect_without_following_it(relay, monkeypatch):
    monkeypatch.setattr(probe, "exchange", Mock(return_value=(302, b"{}")))
    assert smoke.request(relay + "/langfuse-health")[0] == 302
    assert probe.exchange.call_count == 1


@pytest.mark.parametrize("target", ["http://example.com/", "//example.com/"])
def test_ingress_rejects_caller_selected_destination(relay, target):
    from http.client import HTTPConnection

    connection = HTTPConnection("127.0.0.1", int(relay.rsplit(":", 1)[-1]))
    try:
        connection.request("GET", target)
        assert connection.getresponse().status == 400
    finally:
        connection.close()

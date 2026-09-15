#!/usr/bin/env python3
"""Preview or create Jira issues from a compact YAML plan."""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import sys
import tempfile
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen

import yaml


ROOT = Path(__file__).resolve().parents[2]
REQUIRED_ENV = (
    "JIRA_BASE_URL", "JIRA_PROJECT_KEY", "JIRA_EMAIL", "JIRA_API_TOKEN", "JIRA_BOARD_ID"
)
ALLOWED_TYPES = {"Epic", "Task"}
TYPE_ALIASES = {"Epic": {"epic", "에픽"}, "Task": {"task", "작업"}}
JIRA_KEY_PATTERN = re.compile(r"^[A-Z][A-Z0-9_]*-[1-9][0-9]*$")
ALLOWED_STORY_POINTS = {1, 2, 3, 5, 8, 13, 21}
AUTOMATION_ID_PATTERN = re.compile(r"^jira-auto-[0-9a-f]{32}$")
TEMP_PLAN_ROOT = ROOT / ".jira" / "tmp"


class PlanError(ValueError):
    """Raised when the plan cannot be used safely."""


@dataclass(frozen=True)
class JiraConfig:
    base_url: str
    project_key: str
    email: str
    api_token: str
    board_id: str | None = None


def load_dotenv(path: Path) -> None:
    """Load simple KEY=VALUE entries without overriding the process environment."""
    if not path.exists():
        return
    for number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise PlanError(f"{path}:{number}: KEY=VALUE 형식이 아닙니다.")
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip("'\""))


def load_config() -> JiraConfig:
    missing = [key for key in REQUIRED_ENV if not os.environ.get(key)]
    if missing:
        raise PlanError(f"필수 환경 변수가 없습니다: {', '.join(missing)}")
    base_url = os.environ["JIRA_BASE_URL"].rstrip("/")
    if not base_url.startswith("https://"):
        raise PlanError("JIRA_BASE_URL은 https:// 주소여야 합니다.")
    board_id = os.environ["JIRA_BOARD_ID"]
    if not board_id.isdigit():
        raise PlanError("JIRA_BOARD_ID는 숫자여야 합니다.")
    return JiraConfig(
        base_url=base_url,
        project_key=os.environ["JIRA_PROJECT_KEY"],
        email=os.environ["JIRA_EMAIL"],
        api_token=os.environ["JIRA_API_TOKEN"],
        board_id=board_id,
    )


def load_plan(path: Path) -> dict[str, Any]:
    if not path.exists():
        raise PlanError(f"계획 파일을 찾을 수 없습니다: {path}")
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    if not isinstance(data, dict) or not isinstance(data.get("tasks"), list):
        raise PlanError("최상위 tasks 목록이 필요합니다.")
    validate_tasks(data["tasks"])
    return data


def validate_tasks(tasks: list[Any]) -> None:
    ids: set[str] = set()
    tasks_by_id: dict[str, dict[str, Any]] = {}
    for index, task in enumerate(tasks, 1):
        if not isinstance(task, dict):
            raise PlanError(f"tasks[{index}]는 객체여야 합니다.")
        task_id = task.get("id")
        if not isinstance(task_id, str) or not task_id or task_id in ids:
            raise PlanError(f"tasks[{index}].id는 고유한 문자열이어야 합니다.")
        ids.add(task_id)
        tasks_by_id[task_id] = task

    known_ids: set[str] = set()
    for index, task in enumerate(tasks, 1):
        label = f"tasks[{index}]"
        for field in ("summary", "description"):
            if not isinstance(task.get(field), str) or not task[field].strip():
                raise PlanError(f"{label}.{field}은 비어 있지 않은 문자열이어야 합니다.")
        if not task.get("done_when"):
            raise PlanError(f"{label}.done_when 값이 필요합니다.")
        task_id = task["id"]
        issue_type = task.get("issue_type", "Task")
        if issue_type not in ALLOWED_TYPES:
            raise PlanError(f"{label}.issue_type은 Epic 또는 Task여야 합니다.")
        for field in ("epic", "priority", "area", "jira_key", "automation_id"):
            if task.get(field) is not None and not isinstance(task[field], str):
                raise PlanError(f"{label}.{field}은 문자열이어야 합니다.")
        story_points = task.get("story_points")
        if story_points is not None and (
            isinstance(story_points, bool)
            or not isinstance(story_points, (int, float))
            or story_points < 0
        ):
            raise PlanError(f"{label}.story_points는 0 이상의 숫자여야 합니다.")
        if (
            issue_type == "Task"
            and not task.get("jira_key")
            and story_points not in ALLOWED_STORY_POINTS
        ):
            raise PlanError(
                f"{label}.story_points는 작업 복잡도에 따라 "
                f"{sorted(ALLOWED_STORY_POINTS)} 중 하나여야 합니다."
            )
        if "assignee" in task:
            raise PlanError(f"{label}.assignee은 자동 지정되므로 계획에 입력하지 않습니다.")
        sprint = task.get("sprint")
        if sprint is not None:
            if issue_type != "Task":
                raise PlanError(f"{label}.sprint는 Task에만 사용할 수 있습니다.")
            if sprint not in ("active", "backlog"):
                raise PlanError(f"{label}.sprint는 active 또는 backlog여야 합니다.")
        if not isinstance(task["done_when"], list) or not all(
            isinstance(item, str) and item.strip() for item in task["done_when"]
        ):
            raise PlanError(f"{label}.done_when은 비어 있지 않은 문자열 목록이어야 합니다.")
        epic = task.get("epic")
        if epic in ids and epic not in known_ids:
            raise PlanError(f"{label}.epic의 로컬 항목은 먼저 선언해야 합니다: {epic}")
        if epic in tasks_by_id and tasks_by_id[epic].get("issue_type", "Task") != "Epic":
            raise PlanError(f"{label}.epic이 가리키는 로컬 항목은 Epic이어야 합니다: {epic}")
        if epic and epic not in tasks_by_id and not JIRA_KEY_PATTERN.fullmatch(epic):
            raise PlanError(f"{label}.epic은 로컬 Epic id 또는 Jira Key여야 합니다.")
        jira_key = task.get("jira_key")
        if jira_key and not JIRA_KEY_PATTERN.fullmatch(jira_key):
            raise PlanError(f"{label}.jira_key 형식이 올바르지 않습니다: {jira_key}")
        automation_id = task.get("automation_id")
        if automation_id and not AUTOMATION_ID_PATTERN.fullmatch(automation_id):
            raise PlanError(
                f"{label}.automation_id는 jira-auto- 뒤에 32자리 소문자 16진수가 와야 합니다."
            )
        known_ids.add(task_id)

    automation_ids = [task.get("automation_id") for task in tasks if task.get("automation_id")]
    if len(automation_ids) != len(set(automation_ids)):
        raise PlanError("계획 안에서 automation_id는 고유해야 합니다.")


def adf_description(task: dict[str, Any]) -> dict[str, Any]:
    content: list[dict[str, Any]] = [
        {"type": "heading", "attrs": {"level": 2}, "content": [{"type": "text", "text": "목적"}]},
        {"type": "paragraph", "content": [{"type": "text", "text": str(task["description"])}]},
        {"type": "heading", "attrs": {"level": 2}, "content": [{"type": "text", "text": "완료 조건"}]},
    ]
    content.append(
        {
            "type": "bulletList",
            "content": [
                {
                    "type": "listItem",
                    "content": [{"type": "paragraph", "content": [{"type": "text", "text": item}]}],
                }
                for item in task["done_when"]
            ],
        }
    )
    return {"type": "doc", "version": 1, "content": content}


def task_area(task: dict[str, Any]) -> str | None:
    if task.get("area"):
        return str(task["area"]).strip()
    match = re.match(r"^\[([^]]+)]", str(task.get("summary", "")))
    return match.group(1).strip() if match else None


def resolve_epic(epic: str | None, tasks_by_id: dict[str, dict[str, Any]]) -> str | None:
    if not epic:
        return None
    if epic in tasks_by_id:
        jira_key = tasks_by_id[epic].get("jira_key")
        if not jira_key:
            raise PlanError(f"상위 Epic이 아직 생성되지 않았습니다: {epic}")
        return str(jira_key)
    return epic


def issue_fields(
    task: dict[str, Any],
    config: JiraConfig,
    tasks_by_id: dict[str, dict[str, Any]],
    issue_type_id: str | None = None,
    extra_fields: dict[str, Any] | None = None,
) -> dict[str, Any]:
    fields: dict[str, Any] = {
        "project": {"key": config.project_key},
        "summary": task["summary"],
        "issuetype": ({"id": issue_type_id} if issue_type_id else {"name": task.get("issue_type", "Task")}),
        "description": adf_description(task),
    }
    if task.get("priority"):
        fields["priority"] = {"name": task["priority"]}
    parent_key = resolve_epic(task.get("epic"), tasks_by_id)
    if parent_key:
        fields["parent"] = {"key": parent_key}
    if task.get("automation_id"):
        fields["labels"] = [str(task["automation_id"])]
    if extra_fields:
        fields.update(extra_fields)
    return fields


class JiraClient:
    def __init__(self, config: JiraConfig) -> None:
        self.config = config
        self._issue_types: list[dict[str, Any]] | None = None
        self._fields: list[dict[str, Any]] | None = None

    def request(self, path: str, method: str = "GET", payload: dict[str, Any] | None = None) -> Any:
        credentials = base64.b64encode(
            f"{self.config.email}:{self.config.api_token}".encode("utf-8")
        ).decode("ascii")
        request = Request(
            f"{self.config.base_url}{path}",
            data=(json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload else None),
            headers={
                "Accept": "application/json",
                "Authorization": f"Basic {credentials}",
                "Content-Type": "application/json",
            },
            method=method,
        )
        try:
            with urlopen(request, timeout=30) as response:
                body = response.read()
                payload = json.loads(body) if body else None
        except HTTPError as error:
            detail = error.read().decode("utf-8", errors="replace")
            raise PlanError(f"Jira API 오류 ({error.code}): {detail[:500]}") from error
        except URLError as error:
            raise PlanError(f"Jira 연결 오류: {error.reason}") from error
        return payload

    def create_issue(self, fields: dict[str, Any]) -> str:
        payload = self.request("/rest/api/3/issue", "POST", {"fields": fields})
        if not payload.get("key"):
            raise PlanError("Jira 응답에 issue key가 없습니다.")
        return str(payload["key"])

    def check_connection(self) -> list[dict[str, Any]]:
        self.get_current_account_id()
        return self.get_issue_types()

    def get_current_account_id(self) -> str:
        payload = self.request("/rest/api/3/myself")
        account_id = payload.get("accountId") if isinstance(payload, dict) else None
        if not account_id:
            raise PlanError("JIRA_EMAIL 인증 계정의 accountId를 확인하지 못했습니다.")
        return str(account_id)

    def get_issue_types(self) -> list[dict[str, Any]]:
        if self._issue_types is not None:
            return self._issue_types
        project = quote(self.config.project_key, safe="")
        payload = self.request(f"/rest/api/3/issue/createmeta/{project}/issuetypes")
        issue_types = payload.get("issueTypes", [])
        if not issue_types:
            raise PlanError("프로젝트에서 생성 가능한 업무 유형을 찾지 못했습니다.")
        self._issue_types = issue_types
        return self._issue_types

    def resolve_issue_type_id(self, logical_name: str) -> str:
        aliases = TYPE_ALIASES[logical_name]
        for issue_type in self.get_issue_types():
            if str(issue_type.get("name", "")).casefold() in aliases:
                return str(issue_type["id"])
        available = ", ".join(str(item.get("name")) for item in self.get_issue_types())
        raise PlanError(f"{logical_name} 업무 유형을 찾지 못했습니다. 생성 가능 유형: {available}")

    def get_fields(self) -> list[dict[str, Any]]:
        if self._fields is None:
            payload = self.request("/rest/api/3/field")
            if not isinstance(payload, list):
                raise PlanError("Jira 필드 목록 응답이 올바르지 않습니다.")
            self._fields = payload
        return self._fields

    def resolve_field_id(self, *names: str) -> str:
        aliases = {name.casefold() for name in names}
        matches = [
            field for field in self.get_fields()
            if str(field.get("name", "")).casefold() in aliases
        ]
        if len(matches) != 1:
            found = ", ".join(str(field.get("name")) for field in matches) or "없음"
            raise PlanError(f"Jira 필드를 하나로 결정하지 못했습니다 ({'/'.join(names)}): {found}")
        return str(matches[0]["id"])


    def get_project_epics(self, epic_type_id: str) -> list[dict[str, Any]]:
        payload = self.request(
            "/rest/api/3/search/jql",
            "POST",
            {
                "jql": (
                    f'project = "{self.config.project_key}" AND issuetype = {epic_type_id}'
                ),
                "fields": ["summary", "description"],
                "maxResults": 100,
            },
        )
        issues = payload.get("issues", []) if isinstance(payload, dict) else []
        return issues if isinstance(issues, list) else []

    def search_automation_id(self, automation_id: str) -> list[dict[str, Any]]:
        payload = self.request(
            "/rest/api/3/search/jql",
            "POST",
            {
                "jql": (
                    f'project = "{self.config.project_key}" '
                    f'AND labels = "{automation_id}"'
                ),
                "fields": ["summary", "issuetype"],
                "maxResults": 3,
            },
        )
        issues = payload.get("issues", []) if isinstance(payload, dict) else []
        return issues if isinstance(issues, list) else []

    def search_exact_summary(self, summary: str) -> list[dict[str, Any]]:
        phrase = summary.replace("\\", "\\\\").replace('"', '\\"')
        payload = self.request(
            "/rest/api/3/search/jql",
            "POST",
            {
                "jql": (
                    f'project = "{self.config.project_key}" '
                    f'AND summary ~ "\\"{phrase}\\""'
                ),
                "fields": ["summary", "issuetype", "status"],
                "maxResults": 20,
            },
        )
        issues = payload.get("issues", []) if isinstance(payload, dict) else []
        if not isinstance(issues, list):
            return []
        return [issue for issue in issues if issue.get("fields", {}).get("summary") == summary]

    def issue_exists(self, jira_key: str) -> bool:
        payload = self.request(f"/rest/api/3/issue/{quote(jira_key, safe='')}?fields=key")
        return isinstance(payload, dict) and payload.get("key") == jira_key

    def get_active_sprint_id(self) -> int:
        if not self.config.board_id:
            raise PlanError("활성 스프린트를 사용하려면 JIRA_BOARD_ID가 필요합니다.")
        board_id = quote(self.config.board_id, safe="")
        payload = self.request(f"/rest/agile/1.0/board/{board_id}/sprint?state=active")
        sprints = payload.get("values", []) if isinstance(payload, dict) else []
        if len(sprints) != 1 or "id" not in sprints[0]:
            raise PlanError(
                f"활성 스프린트를 하나로 결정하지 못했습니다: board={self.config.board_id} "
                f"(검색 결과 {len(sprints)}개)"
            )
        return int(sprints[0]["id"])


def save_plan(path: Path, plan: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        "w", encoding="utf-8", newline="\n", dir=path.parent, delete=False
    ) as handle:
        yaml.safe_dump(plan, handle, allow_unicode=True, sort_keys=False, width=100)
        temporary = Path(handle.name)
    temporary.replace(path)


def ensure_automation_ids(path: Path, plan: dict[str, Any]) -> bool:
    changed = False
    for task in pending_tasks(plan):
        if not task.get("automation_id"):
            task["automation_id"] = f"jira-auto-{uuid.uuid4().hex}"
            changed = True
    if changed:
        validate_tasks(plan["tasks"])
        save_plan(path, plan)
    return changed


def pending_tasks(plan: dict[str, Any]) -> list[dict[str, Any]]:
    return [task for task in plan["tasks"] if not task.get("jira_key")]


def print_preview(tasks: list[dict[str, Any]]) -> None:
    if not tasks:
        print("생성할 Jira 업무 항목이 없습니다.")
        return
    print(f"생성 예정: {len(tasks)}개")
    for task in tasks:
        issue_type = task.get("issue_type", "Task")
        parent = f", epic={task['epic']}" if task.get("epic") else ""
        priority = f", priority={task['priority']}" if task.get("priority") else ""
        story_points = (
            f", story_points={task['story_points']}" if issue_type == "Task" else ""
        )
        assignee = ", assignee=.env 인증 계정"
        sprint = f", sprint={task.get('sprint', 'active')}" if issue_type == "Task" else ""
        area = f", area={task_area(task)}" if not task.get("epic") else ""
        automation = f", automation_id={task['automation_id']}"
        print(
            f"- [{issue_type}] {task['id']}: {task['summary']}"
            f"{parent}{priority}{area}{story_points}{assignee}{sprint}{automation}"
        )


def select_related_epic(task: dict[str, Any], epics: list[dict[str, Any]]) -> str | None:
    area = task_area(task)
    if not area:
        raise PlanError(f"Epic 자동 선택을 위해 area 또는 [영역] 제목이 필요합니다: {task['id']}")
    area_text = area.casefold()
    exact: list[dict[str, Any]] = []
    for epic in epics:
        summary = str(epic.get("fields", {}).get("summary", ""))
        prefix = re.match(r"^\[([^]]+)]", summary)
        if prefix and prefix.group(1).strip().casefold() == area_text:
            exact.append(epic)
    if len(exact) == 1:
        return str(exact[0]["key"])
    candidates = exact or epics
    task_words = set(re.findall(r"[0-9a-zA-Z가-힣]{2,}", (
        f"{task['summary']} {task['description']} {' '.join(task['done_when'])}"
    ).casefold()))
    scored: list[tuple[int, str]] = []
    for epic in candidates:
        fields = epic.get("fields", {})
        epic_text = f"{fields.get('summary', '')} {json.dumps(fields.get('description', ''), ensure_ascii=False)}"
        epic_words = set(re.findall(r"[0-9a-zA-Z가-힣]{2,}", epic_text.casefold()))
        score = len(task_words & epic_words) + (5 if area_text in epic_text.casefold() else 0)
        scored.append((score, str(epic.get("key", ""))))
    scored.sort(reverse=True)
    if scored and scored[0][0] > 0 and (len(scored) == 1 or scored[0][0] > scored[1][0]):
        return scored[0][1]
    return None


def single_automation_match(
    task: dict[str, Any],
    client: JiraClient,
    automation_id: str | None = None,
    expected_type: str | None = None,
) -> str | None:
    value = automation_id or str(task["automation_id"])
    matches = client.search_automation_id(value)
    if len(matches) > 1:
        keys = ", ".join(str(issue.get("key")) for issue in matches)
        raise PlanError(f"automation_id가 중복된 Jira 업무를 가리킵니다: {value} ({keys})")
    if not matches:
        return None
    logical_type = expected_type or str(task.get("issue_type", "Task"))
    actual_type = str(matches[0].get("fields", {}).get("issuetype", {}).get("name", ""))
    if actual_type and actual_type.casefold() not in TYPE_ALIASES[logical_type]:
        raise PlanError(
            f"automation_id가 다른 업무 유형을 가리킵니다: {value} "
            f"(예상 {logical_type}, 실제 {actual_type})"
        )
    return str(matches[0]["key"])


def create_automatic_epic(task: dict[str, Any], client: JiraClient, account_id: str) -> str:
    area = task_area(task)
    if not area:
        raise PlanError(f"새 Epic 생성을 위해 area 또는 [영역] 제목이 필요합니다: {task['id']}")
    core_summary = re.sub(r"^\[[^]]+]\s*", "", task["summary"]).strip()
    epic_task = {
        "summary": f"[{area}] {core_summary}",
        "description": f"{area} 영역의 관련 작업을 묶기 위해 자동 생성한 Epic입니다.",
        "done_when": ["관련 하위 업무가 Epic에 연결된다."],
        "issue_type": "Epic",
        "automation_id": f"{task['automation_id']}-epic",
    }
    existing_key = single_automation_match(
        task,
        client,
        automation_id=str(epic_task["automation_id"]),
        expected_type="Epic",
    )
    if existing_key:
        print(f"Epic 기존 항목 확인: {existing_key} ({epic_task['summary']})")
        return existing_key
    fields = issue_fields(
        epic_task,
        client.config,
        {},
        client.resolve_issue_type_id("Epic"),
        {"assignee": {"accountId": account_id}},
    )
    key = client.create_issue(fields)
    print(f"Epic 자동 생성: {key} ({epic_task['summary']})")
    return key


def apply_plan(
    path: Path,
    plan: dict[str, Any],
    client: JiraClient,
    allow_existing_summary: bool = False,
) -> int:
    ensure_automation_ids(path, plan)
    created = 0
    tasks_by_id = {task["id"]: task for task in plan["tasks"]}
    pending = pending_tasks(plan)
    for task in pending:
        existing_key = single_automation_match(task, client)
        if existing_key:
            task["jira_key"] = existing_key
            save_plan(path, plan)
            print(f"기존 항목 확인: {task['id']} -> {existing_key}")

    pending = pending_tasks(plan)
    if not allow_existing_summary:
        for task in pending:
            similar = client.search_exact_summary(str(task["summary"]))
            if similar:
                keys = ", ".join(str(issue.get("key")) for issue in similar)
                raise PlanError(
                    f"같은 제목의 Jira 업무가 이미 있습니다: {task['summary']} ({keys}). "
                    "기존 업무를 사용하려면 jira_key를 기록하고, 별도 업무가 맞으면 "
                    "--allow-existing-summary를 명시하세요."
                )

    if not pending:
        return created
    has_tasks = any(task.get("issue_type", "Task") == "Task" for task in pending)
    needs_active_sprint = any(
        task.get("issue_type", "Task") == "Task" and task.get("sprint", "active") == "active"
        for task in pending
    )
    story_points_field = (
        client.resolve_field_id("Story Points", "스토리 포인트") if has_tasks else None
    )
    sprint_field = (
        client.resolve_field_id("Sprint", "스프린트") if needs_active_sprint else None
    )
    active_sprint_id = client.get_active_sprint_id() if needs_active_sprint else None
    account_id = client.get_current_account_id()
    epic_type_id = client.resolve_issue_type_id("Epic")
    epics = client.get_project_epics(epic_type_id) if has_tasks else []
    for task in pending:
        issue_type = task.get("issue_type", "Task")
        type_id = client.resolve_issue_type_id(issue_type)
        extra_fields: dict[str, Any] = {"assignee": {"accountId": account_id}}
        if issue_type == "Task":
            assert story_points_field
            extra_fields[story_points_field] = task["story_points"]
            if task.get("sprint", "active") == "active":
                assert sprint_field and active_sprint_id is not None
                extra_fields[sprint_field] = active_sprint_id
            if not task.get("epic"):
                epic_key = select_related_epic(task, epics)
                if epic_key:
                    print(f"Epic 자동 선택: {task['id']} -> {epic_key}")
                else:
                    epic_key = create_automatic_epic(task, client, account_id)
                    epics.append({"key": epic_key, "fields": {"summary": task["summary"]}})
                task["epic"] = epic_key
                save_plan(path, plan)
        key = client.create_issue(
            issue_fields(task, client.config, tasks_by_id, type_id, extra_fields)
        )
        task["jira_key"] = key
        save_plan(path, plan)
        created += 1
        print(f"생성 완료: {task['id']} -> {key}")
    return created


def verify_plan_issues(plan: dict[str, Any], client: JiraClient) -> None:
    missing = [
        str(task.get("jira_key"))
        for task in plan["tasks"]
        if not task.get("jira_key") or not client.issue_exists(str(task["jira_key"]))
    ]
    if missing:
        raise PlanError(f"Jira에서 생성 결과를 확인하지 못했습니다: {', '.join(missing)}")


def delete_temporary_plan(path: Path) -> None:
    resolved = path.resolve()
    try:
        resolved.relative_to(TEMP_PLAN_ROOT.resolve())
    except ValueError as error:
        raise PlanError(
            f"--delete-on-success는 {TEMP_PLAN_ROOT} 아래의 계획 파일에만 사용할 수 있습니다."
        ) from error
    resolved.unlink()
    print(f"임시 계획 삭제: {resolved}")


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--file", type=Path, help="Jira 계획 YAML 경로")
    parser.add_argument("--env-file", type=Path, default=ROOT / ".env", help="환경 변수 파일")
    parser.add_argument("--apply", action="store_true", help="미리보기 후 실제 Jira 업무 생성")
    parser.add_argument("--check", action="store_true", help="Jira 연결과 생성 가능한 업무 유형 확인")
    parser.add_argument(
        "--allow-existing-summary",
        action="store_true",
        help="같은 제목의 기존 업무가 있어도 별도 Jira 업무로 생성",
    )
    parser.add_argument(
        "--delete-on-success",
        action="store_true",
        help="모든 Jira 업무 확인 후 .jira/tmp 아래의 계획 파일 삭제",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        if args.check:
            if args.apply or args.file or args.allow_existing_summary or args.delete_on_success:
                raise PlanError("--check는 다른 실행 옵션과 함께 사용할 수 없습니다.")
            load_dotenv(args.env_file)
            config = load_config()
            issue_types = JiraClient(config).check_connection()
            names = ", ".join(f"{item['name']} (id={item['id']})" for item in issue_types)
            print(f"Jira 연결 성공: project={config.project_key}")
            print(f"생성 가능한 업무 유형: {names}")
            return 0
        if not args.file:
            raise PlanError("계획을 확인하거나 생성하려면 --file이 필요합니다.")
        if (args.allow_existing_summary or args.delete_on_success) and not args.apply:
            raise PlanError(
                "--allow-existing-summary와 --delete-on-success는 --apply와 함께 사용해야 합니다."
            )
        plan = load_plan(args.file)
        ensure_automation_ids(args.file, plan)
        pending = pending_tasks(plan)
        print_preview(pending)
        if not args.apply:
            return 0
        load_dotenv(args.env_file)
        config = load_config()
        client = JiraClient(config)
        if pending:
            apply_plan(args.file, plan, client, args.allow_existing_summary)
        if args.delete_on_success:
            verify_plan_issues(plan, client)
            delete_temporary_plan(args.file)
        return 0
    except PlanError as error:
        print(f"오류: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

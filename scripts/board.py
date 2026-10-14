#!/usr/bin/env python3
"""Board helper for the GitHub Project "HaYi Shop" (Decision J1/J2).

Used by the /ticket skill so board operations are exact instead of hand-built gh commands.
Requires `gh` logged in with the `project` scope. Standard library only.

  python3 scripts/board.py wip                       # In Progress items across the whole project
  python3 scripts/board.py wip --check               # exit 3 if the WIP limit is reached
  python3 scripts/board.py ready                     # Ready items of this repo, with open blockers
  python3 scripts/board.py show <issue>              # status, labels, milestone, open blockers
  python3 scripts/board.py status <issue> <Status>   # Backlog | Ready | In Progress | In Review | Done
"""
import json
import subprocess
import sys

OWNER = "HaYiiii"
PROJECT = "1"
WIP_LIMIT = 3
STATUSES = ["Backlog", "Ready", "In Progress", "In Review", "Done"]


def gh(*args):
    result = subprocess.run(["gh", *args], capture_output=True, text=True)
    if result.returncode != 0:
        sys.exit(f"gh {' '.join(args[:3])} failed: {result.stderr.strip()}")
    return result.stdout


def gh_json(*args):
    return json.loads(gh(*args))


def current_repo():
    return gh_json("repo", "view", "--json", "name")["name"]


def project_items():
    return gh_json("project", "item-list", PROJECT, "--owner", OWNER, "--limit", "500", "--format", "json")["items"]


def item_repo(item):
    return (item.get("content", {}).get("repository") or "").split("/")[-1]


def ref(item):
    c = item["content"]
    return f"{item_repo(item)}#{c['number']} {c['title']}"


def open_blockers(repo, number):
    deps = gh_json("api", f"repos/{OWNER}/{repo}/issues/{number}/dependencies/blocked_by")
    return [f"{d['repository_url'].split('/')[-1]}#{d['number']}" for d in deps if d["state"] == "open"]


def cmd_wip(args):
    in_progress = [i for i in project_items() if i.get("status") == "In Progress"]
    print(f"WIP {len(in_progress)}/{WIP_LIMIT} In Progress")
    for item in in_progress:
        print(f"  - {ref(item)}")
    if "--check" in args and len(in_progress) >= WIP_LIMIT:
        sys.exit(3)


def cmd_ready(_args):
    repo = current_repo()
    rows = [i for i in project_items() if i.get("status") == "Ready" and item_repo(i) == repo]
    if not rows:
        print(f"No Ready items in {repo}.")
    for item in sorted(rows, key=lambda i: i["content"]["number"]):
        blockers = open_blockers(repo, item["content"]["number"])
        labels = ",".join(item.get("labels") or [])
        flag = "BLOCKED by " + ", ".join(blockers) if blockers else "unblocked"
        print(f"#{item['content']['number']:<4} {item['content']['title']}  [{labels}]  -> {flag}")


def find_item(repo, number):
    for item in project_items():
        if item_repo(item) == repo and item["content"].get("number") == number:
            return item
    return None


def cmd_show(args):
    repo, number = current_repo(), int(args[0])
    issue = gh_json("issue", "view", str(number), "--json", "title,state,labels,milestone,url")
    item = find_item(repo, number)
    print(f"{repo}#{number} {issue['title']} ({issue['state']})")
    print(f"  url:       {issue['url']}")
    print(f"  status:    {item.get('status') if item else 'not on board'}")
    print(f"  milestone: {(issue.get('milestone') or {}).get('title')}")
    print(f"  labels:    {', '.join(l['name'] for l in issue['labels'])}")
    blockers = open_blockers(repo, number)
    print(f"  blockers:  {', '.join(blockers) if blockers else 'none open'}")


def cmd_status(args):
    repo, number, target = current_repo(), int(args[0]), " ".join(args[1:])
    if target not in STATUSES:
        sys.exit(f"Status must be one of {STATUSES}")
    project = gh_json("project", "view", PROJECT, "--owner", OWNER, "--format", "json")
    fields = gh_json("project", "field-list", PROJECT, "--owner", OWNER, "--format", "json")["fields"]
    status = next(f for f in fields if f["name"] == "Status")
    option = next(o["id"] for o in status["options"] if o["name"] == target)
    item = find_item(repo, number)
    if item is None:
        url = gh_json("issue", "view", str(number), "--json", "url")["url"]
        item = gh_json("project", "item-add", PROJECT, "--owner", OWNER, "--url", url, "--format", "json")
    if target == "In Progress" and (item.get("status") != "In Progress"):
        count = sum(1 for i in project_items() if i.get("status") == "In Progress")
        if count >= WIP_LIMIT:
            sys.exit(f"WIP limit reached ({count}/{WIP_LIMIT}). Finish or hand back an item first.")
    gh("project", "item-edit", "--id", item["id"], "--project-id", project["id"],
       "--field-id", status["id"], "--single-select-option-id", option)
    print(f"{repo}#{number} -> {target}")


COMMANDS = {"wip": cmd_wip, "ready": cmd_ready, "show": cmd_show, "status": cmd_status}

if __name__ == "__main__":
    if len(sys.argv) < 2 or sys.argv[1] not in COMMANDS:
        sys.exit(__doc__)
    COMMANDS[sys.argv[1]](sys.argv[2:])

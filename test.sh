#!/usr/bin/env bash
# Runs mill test targets against the local Postgres and prints only what needs reading: a count per module, each
# failure, stray stack traces, and compile errors. The full log goes to out/test-logs/, whose path is printed last.
#
#   ./test.sh matchmaker.test engines.boxing.test
#   ./test.sh matchmaker.test.testOnly com.vivi.matchmaker.service.TournamentServiceSpec
#
# Each argument whose first word's dotted parts all start with a lower-case letter is a target, with any words after
# it; anything else (a class name, a selector, a flag) belongs to the target before it. The targets are joined with "+", and FLYWAY_URL points at the
# local database, so neither trap in CLAUDE.md's "Build and test" can catch a run. Exits with mill's status, or 1 if
# mill passed but a target ran no tests or the log holds a stray trace.
cd "$(dirname "$0")" || exit 1

targets=()
for arg in "$@"; do
    if [[ "$arg" == "+" ]]; then
        continue
    elif [[ "${arg%% *}" =~ ^[a-z][A-Za-z0-9]*(\.[a-z][A-Za-z0-9]*)*$ ]]; then
        targets+=("$arg")
    elif ((${#targets[@]} == 0)); then
        echo "test.sh: '$arg' comes before any target" >&2
        exit 2
    else
        targets[-1]+=" $arg"
    fi
done
if ((${#targets[@]} == 0)); then
    echo "usage: ./test.sh <target> [args…] [<target> [args…]]…" >&2
    exit 2
fi

mill_args=()
for t in "${targets[@]}"; do
    ((${#mill_args[@]} > 0)) && mill_args+=("+")
    # A target written with spaces in one argument is split here, as mill would see it typed.
    read -r -a words <<<"$t"
    mill_args+=("${words[@]}")
done

mkdir -p out/test-logs
log="out/test-logs/$(date +%Y%m%d-%H%M%S).log"
stamp="$(mktemp)"
trap 'rm -f "$stamp"' EXIT

FLYWAY_URL=jdbc:postgresql://localhost:5432/matchmaker \
    mill --no-daemon -j 4 --ticker false "${mill_args[@]}" >"$log" 2>&1
status=$?

python3 -I - "$log" "$stamp" "$status" "${targets[@]}" <<'EOF'
import glob, os, re, sys
import xml.etree.ElementTree as ET

log, stamp, status, targets = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4:]
since = os.path.getmtime(stamp)
ansi = re.compile(r"\x1b\[[0-9;?]*[A-Za-z]")
lines = [ansi.sub("", l.rstrip("\n")) for l in open(log, errors="replace")]
problems = False

# A count per module, from the report mill writes for each test task it ran.
def report_for(target):
    path = "out/" + target.split()[0].replace(".", "/")
    head, last = os.path.split(path)
    found = glob.glob(path + "/*.dest/test-report.xml") + glob.glob(f"{head}/{last}.dest/test-report.xml")
    fresh = [f for f in found if os.path.getmtime(f) >= since]
    return max(fresh, key=os.path.getmtime) if fresh else None

ran_nothing = []
for target in targets:
    report = report_for(target)
    if report is None:
        if status != 0:
            print(f"{target}: no test report")
        ran_nothing.append(target)
        continue
    root = ET.parse(report).getroot()
    total, failed = int(root.get("tests", 0)), int(root.get("failures", 0)) + int(root.get("errors", 0))
    skipped = int(root.get("skipped", 0))
    extra = f", {skipped} skipped" if skipped else ""
    print(f"{target}: {total - failed - skipped} passed, {failed} failed{extra}")
    if total == 0:
        ran_nothing.append(target)

# Each failure: munit's "==> X" line, its message, and the first three frames.
def is_frame(l): return l.lstrip().startswith("at ")
def ends_block(l):
    return (l.startswith("Running Test Class") or l.startswith("  + ") or l.startswith("==> ")
            or l.startswith("[error]") or re.match(r"^[\w.$]+:$", l) is not None)

in_failure, frames, message, stray = False, 0, 0, []
previous = ""
for l in lines:
    before, previous = previous, l
    if l.startswith("==> X"):
        in_failure, frames, message = True, 0, 0
        print("\n" + l)
        continue
    if in_failure and ends_block(l):
        in_failure = False
    if in_failure:
        if is_frame(l):
            frames += 1
            if frames <= 3:
                print(l)
        elif frames == 0:
            message += 1
            if message <= 25:
                print(l)
            elif message == 26:
                print("    … (message cut; see the log)")
        continue
    # A stray trace is kept as the line before its first frame, which names the exception, and its frames.
    if is_frame(l):
        if not stray or not is_frame(before):
            stray.append([before])
        stray[-1].append(l)
    elif "ERROR handling" in l or "rds.amazonaws" in l:
        stray.append([l])

if stray:
    problems = True
    print(f"\nSTRAY TRACES: {len(stray)} outside any failure")
    for trace in stray[:5]:
        print("\n".join(trace[:4]))
        if len(trace) > 4:
            print(f"    … {len(trace) - 4} more frames")
    if len(stray) > 5:
        print(f"… and {len(stray) - 5} more")

# Anything else mill reported, such as a compile error: each "[error]" line with the message lines that follow it.
blocks, block = [], None
for l in lines:
    if l.startswith("[error]"):
        block = [l]
        blocks.append(block)
    elif not l.strip():
        block = None
    elif block is not None and len(block) < 8:
        block.append(l)
blocks = [b for b in blocks if "tests failed" not in b[0]]
if blocks and not any(l.startswith("==> X") for l in lines):
    print("\nERRORS:")
    for b in blocks[:15]:
        print("\n".join(b))
    if len(blocks) > 15:
        print(f"… and {len(blocks) - 15} more [error] lines")

if ran_nothing and status == 0:
    problems = True
    for target in ran_nothing:
        print(f"\nWARNING: no tests ran for {target}")

print(f"\nlog: {log}")
sys.exit(status if status != 0 else (1 if problems else 0))
EOF

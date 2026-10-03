"""End-to-end check of archiving completed matches (archiving-matches-plan.md).

Matchmaker and the tic-tac-toe engine run as separate processes from their assembly jars, with moto
standing in for S3 under the real dotted bucket names. Matches are played over HTTP, and the check is
what lands in the buckets, what the engine still holds, and what a player is shown: archiving and
reading back, an expired friendly archive, a cancel, and a match that is not friendly.

    python3 -m venv /tmp/moto && /tmp/moto/bin/pip install "moto[server]"
    mill -j 4 --ticker false matchmaker.api.assembly + engines.tictactoe.assembly
    PATH=/tmp/moto/bin:$PATH /tmp/moto/bin/python e2e/archive.py

Needs the local Postgres the tests use, and writes to it: a game and a few matches per run, under
names of their own. Ports 18080, 18090, 18190 and 15055 must be free. Logs go to e2e/out/.
"""
import json, os, subprocess, sys, time, uuid, urllib.request, urllib.error
import boto3
from botocore.config import Config

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOGS = os.path.join(REPO, "e2e", "out")
MM = "http://localhost:18080"
ENGINE = "http://localhost:18090"
S3 = "http://127.0.0.1:15055"
PERMANENT = "e2e-archive.vivi.com"
FRIENDLY = "e2e-friendly-archive.vivi.com"
RUN = uuid.uuid4().hex[:8]
EXTERNAL_ID = f"e2e-{RUN}"

creds = dict(AWS_ACCESS_KEY_ID="test", AWS_SECRET_ACCESS_KEY="test", AWS_REGION="us-east-1")
procs = []


def start(name, cmd, env):
    os.makedirs(LOGS, exist_ok=True)
    log = open(os.path.join(LOGS, f"{name}.log"), "w")
    p = subprocess.Popen(cmd, env={**os.environ, **env}, stdout=log, stderr=subprocess.STDOUT)
    procs.append(p)
    return p


def wait_for(url, what):
    for _ in range(120):
        try:
            urllib.request.urlopen(url, timeout=1)
            return
        except urllib.error.HTTPError:
            return
        except Exception:
            time.sleep(0.5)
    sys.exit(f"{what} did not come up")


def call(method, url, body=None, headers=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method, headers={"content-type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            text = r.read().decode()
            return r.status, (json.loads(text) if text and text.lstrip()[:1] in "[{" else text)
    except urllib.error.HTTPError as e:
        text = e.read().decode()
        try:
            return e.code, json.loads(text)
        except Exception:
            return e.code, text


def as_player(sub):
    return {"x-external-id": sub}


def psql(sql):
    out = subprocess.run(["psql", "-X", "-h", "localhost", "-U", "matchmaker", "matchmaker", "-At", "-c", sql],
                         env={**os.environ, "PGPASSWORD": "matchmaker"}, capture_output=True, text=True, check=True)
    return out.stdout.strip()


failures = []


def check(cond, what):
    print(("  ok   " if cond else "  FAIL ") + what)
    if not cond:
        failures.append(what)


try:
    start("moto", ["moto_server", "-H", "127.0.0.1", "-p", "15055"], {})
    wait_for(S3, "moto")
    s3 = boto3.client("s3", endpoint_url=S3, region_name="us-east-1", aws_access_key_id="test",
                      aws_secret_access_key="test", config=Config(s3={"addressing_style": "path"}))
    for b in (PERMANENT, FRIENDLY):
        s3.create_bucket(Bucket=b)
        s3.put_bucket_versioning(Bucket=b, VersioningConfiguration={"Status": "Enabled"})

    start("matchmaker", ["java", "-cp", f"{REPO}/out/matchmaker/api/assembly.dest/out.jar",
                         "com.vivi.matchmaker.api.LocalServer"],
          {**creds, "PORT": "18080", "ARCHIVE_BUCKET": PERMANENT, "FRIENDLY_ARCHIVE_BUCKET": FRIENDLY,
           "ARCHIVE_ENDPOINT": S3, "MATCHMAKER_BASE_URL": MM})
    start("engine", ["java", "-cp", f"{REPO}/out/engines/tictactoe/assembly.dest/out.jar",
                     "com.vivi.tictactoe.LocalServer"],
          {"PORT": "18090", "LIVE_PORT": "18190", "GAME_EXTERNAL_ID": EXTERNAL_ID, "MATCHMAKER_URL": MM})
    wait_for(MM + "/games", "matchmaker")
    wait_for(ENGINE + "/health", "engine")

    # The game, as register-game.sql makes it, under a name of its own for this run.
    game_name = f"e2e-tictactoe-{RUN}"
    game_id = psql(f"""WITH game AS (
        INSERT INTO game (game_type, name, display_name, description, url, active, external_id)
        VALUES ('P', '{game_name}', 'E2E', 'e2e', '{ENGINE}/games', true, '{EXTERNAL_ID}') RETURNING game_id)
      INSERT INTO game_role (game_id, name, optional, display_name)
      SELECT game_id, role, false, role FROM game, (VALUES ('X'), ('O')) AS roles(role) RETURNING game_id""").splitlines()[0]
    roles = dict(line.split("|") for line in psql(f"SELECT name, game_role_id FROM game_role WHERE game_id = {game_id}").splitlines())

    alice, bob = f"sub-a-{RUN}", f"sub-b-{RUN}"
    for sub, nick in ((alice, f"alice{RUN}"), (bob, f"bob{RUN}")):
        status, _ = call("POST", MM + "/register", {"nickname": nick}, as_player(sub))
        assert status in (200, 201), status
    bob_id = call("GET", MM + "/me", headers=as_player(bob))[1]["playerId"]
    alice_id = call("GET", MM + "/me", headers=as_player(alice))[1]["playerId"]

    def new_match():
        challenge = {"$type": "com.vivi.matchmaker.model.PlainChallenge", "challengeId": 0, "challenger": alice_id,
                     "message": "e2e", "start": None, "timeLimit": None, "settings": "{}", "gameId": int(game_id),
                     "gameRoleId": int(roles["X"]), "autoStart": True}
        status, made = call("POST", MM + "/challenges",
                            {"challenge": challenge, "invitations": [{"playerId": bob_id, "gameRoleId": int(roles["O"])}]},
                            as_player(alice))
        assert status in (200, 201), (status, made)
        status, accepted = call("POST", f"{MM}/challenges/{game_id}/{made['challengeId']}/acceptances",
                                {"characterId": None, "gameRoleId": int(roles["O"])}, as_player(bob))
        assert status in (200, 201), (status, accepted)
        matches = call("GET", MM + "/me/matches", headers=as_player(alice))[1]
        mine = [m for m in matches if m["gameId"] == int(game_id)]
        return sorted(mine, key=lambda m: m["start"])[-1]["matchId"]

    def play_to_the_end(match_id):
        for sub, cell in ((alice, 0), (bob, 3), (alice, 1), (bob, 4), (alice, 2)):
            status, answer = call("POST", f"{ENGINE}/matches/{match_id}/moves?as={sub}", {"cell": cell})
            assert status == 200, (status, answer)

    def archive_row(match_id):
        return psql(f"SELECT coalesce(archive_key,''), archived_at IS NOT NULL, archive_expired_at IS NOT NULL, "
                    f"completed IS NOT NULL FROM match WHERE match_id = '{match_id}'").split("|")

    print("1. a finished friendly match is archived, and the engine drops its live copy")
    m1 = new_match()
    play_to_the_end(m1)
    key, archived, expired, completed = archive_row(m1)
    check(completed == "t", "matchmaker recorded the result")
    check(archived == "t", "matchmaker recorded the archive as confirmed")
    check(key == f"{game_name}/{time.strftime('%Y-%m-%d', time.gmtime())}/{m1}.json", f"key is game/day/match: {key}")
    objects = [o["Key"] for o in s3.list_objects_v2(Bucket=FRIENDLY).get("Contents", [])]
    check(key in objects, "the archive is in the friendly bucket")
    check(not s3.list_objects_v2(Bucket=PERMANENT).get("Contents"), "and nothing is in the permanent one")
    stored = s3.get_object(Bucket=FRIENDLY, Key=key)
    body = json.loads(stored["Body"].read())
    check(body.get("matchId") == m1, "the archive is the engine's stored match")
    check(stored["Metadata"].get("format-version") == "stored-match-1", f"format version kept: {stored['Metadata']}")
    check(stored.get("VersionId") not in (None, "null"), "the bucket is versioned")

    print("2. the finished match is still shown, from the archive")
    status, detail = call("GET", f"{MM}/games/{game_id}/matches/{m1}", headers=as_player(alice))
    check(status == 200 and detail["playUrl"].endswith("/play?archived=1"), f"Review url is marked: {detail.get('playUrl')}")
    status, page = call("GET", detail["playUrl"] + f"&as={alice}")
    check(status == 200 and "<html" in page.lower(), "the play page is served from the archive")
    status, state = call("GET", f"{ENGINE}/matches/{m1}/state?as={bob}")
    check(status == 200 and state.get("winner") == "X", f"the state is served from the archive: {status}")
    status, _ = call("POST", f"{ENGINE}/matches/{m1}/moves?as={bob}", {"cell": 8})
    check(status == 404, f"a move on the archived match is refused: {status}")

    print("3. a friendly archive that has expired is recorded, and its links withdrawn")
    m2 = new_match()
    play_to_the_end(m2)
    key2 = archive_row(m2)[0]
    psql(f"UPDATE match SET archived_at = archived_at - interval '31 days' WHERE match_id = '{m2}'")
    # What the friendly bucket's lifecycle rule does at 30 days: a delete marker over the object.
    s3.delete_object(Bucket=FRIENDLY, Key=key2)
    completed_list = call("GET", MM + "/me/matches/completed", headers=as_player(alice))[1]
    row = next(m for m in completed_list if m["matchId"] == m2)
    check(row["archiveExpired"] is True and row.get("publicUrl") is None, "the finished list says it has expired")
    check(archive_row(m2)[2] == "t", "matchmaker recorded the expiry")
    still = next(m for m in completed_list if m["matchId"] == m1)
    check(still.get("archiveExpired", False) is False, "the younger archive is untouched")
    status, page = call("GET", f"{ENGINE}/matches/{m2}/play?archived=1&as={alice}")
    check(status == 410 and "kept for 30 days" in page, f"the engine says so: {status}")

    print("4. an expired archive read by the engine before any list noticed")
    m3 = new_match()
    play_to_the_end(m3)
    key3 = archive_row(m3)[0]
    psql(f"UPDATE match SET archived_at = archived_at - interval '31 days' WHERE match_id = '{m3}'")
    s3.delete_object(Bucket=FRIENDLY, Key=key3)
    status, _ = call("GET", f"{ENGINE}/matches/{m3}/state?as={alice}")
    check(status == 410, f"the state route answers 410: {status}")
    check(archive_row(m3)[2] == "t", "and matchmaker recorded it")

    print("5. a cancelled match is dropped by the engine")
    m4 = new_match()
    status, _ = call("GET", f"{ENGINE}/matches/{m4}/status")
    check(status == 200, "the engine has the running match")
    status, cancelled = call("POST", f"{MM}/games/{game_id}/matches/{m4}/cancel", {}, as_player(alice))
    check(status == 200 and cancelled["cancelled"], "matchmaker cancelled it")
    status, _ = call("GET", f"{ENGINE}/matches/{m4}/status")
    check(status == 404, f"the engine no longer has it: {status}")
    check(psql(f"SELECT engine_released IS NOT NULL FROM match WHERE match_id = '{m4}'") == "t",
          "matchmaker recorded that the engine heard")

    print("6. a match that is not friendly goes to the permanent bucket")
    m5 = new_match()
    psql(f"UPDATE match SET friendly = false WHERE match_id = '{m5}'")
    play_to_the_end(m5)
    key5 = archive_row(m5)[0]
    check(key5 in [o["Key"] for o in s3.list_objects_v2(Bucket=PERMANENT).get("Contents", [])],
          "the archive is in the permanent bucket")
finally:
    for p in procs:
        p.terminate()
    for p in procs:
        try:
            p.wait(timeout=10)
        except Exception:
            p.kill()

print()
print("all checks passed" if not failures else f"{len(failures)} check(s) failed: {failures}")
sys.exit(1 if failures else 0)

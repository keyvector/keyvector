"""Keyvector end-to-end test driver.

Writes rows to the Keyspaces table, then checks the vector index against the table.

    python e2e.py seed                    # 300 rows, before CDC is on
    python e2e.py snapshot INDEX FILE     # save the vectors of rows 1-30
    python e2e.py mutate                  # the live changes (step 4)
    python e2e.py mutate-offline          # the changes made while the connector is stopped (step 5)
    python e2e.py mutate-retry            # 5 inserts while the embedder is down (step 7)
    python e2e.py verify INDEX [--snapshot FILE]

Uses the AWS profile in KEYVECTOR_PROFILE (default "keyvector").
"""
import json
import os
import random
import ssl
import sys
import time
import urllib.parse
from datetime import datetime, timedelta, timezone

import boto3
import certifi
from cassandra import ConsistencyLevel
from cassandra.cluster import EXEC_PROFILE_DEFAULT, Cluster, ExecutionProfile
from cassandra_sigv4.auth import SigV4AuthProvider

REGION = "us-east-1"
KEYSPACE, TABLE = "keyvector_e2e", "movies"
PROFILE = os.environ.get("KEYVECTOR_PROFILE", "keyvector")
STUDIOS = ["Aurora Pictures", "Blue Harbor Films", "Northlight & Co", "Paper Moon Studio", "Redwood=Ridge"]

PLOTS = {
    "sci-fi": [
        "A crew of astronauts drifts toward Jupiter after their ship's reactor fails",
        "An android discovers it was built from the memories of a dead scientist",
        "Colonists on a frozen moon find signals buried beneath the ice",
        "A time-travel experiment traps a physicist in a loop of the same Tuesday",
        "Rogue AI takes control of a city's power grid during a heat wave",
    ],
    "romance": [
        "Two rival bakers fall in love while competing for the same village fair prize",
        "A wedding photographer keeps running into the same stranger at every ceremony",
        "Pen pals separated by an ocean finally meet in a rainy Lisbon cafe",
        "A widowed florist rediscovers love with a shy botanist",
        "An actor and his understudy fall for each other during a chaotic theatre season",
    ],
    "horror": [
        "A family moves into a farmhouse where the scarecrows move at night",
        "Campers in a pine forest are hunted by something that mimics their voices",
        "A night-shift nurse realises the patients in ward nine never age",
        "An antique mirror shows its owners how they will die",
        "Divers exploring a sunken liner hear knocking from inside sealed cabins",
    ],
    "comedy": [
        "A clumsy substitute teacher accidentally becomes a chess champion",
        "Three retirees start a heist crew to win back their bingo hall",
        "A food critic loses his sense of taste the week before the city's biggest review",
        "Twin brothers swap jobs as a zookeeper and a stockbroker",
        "A wedding planner's own wedding goes wrong in every possible way",
    ],
    "documentary": [
        "The story of the volunteers who rebuilt a coral reef by hand",
        "Inside the world's largest seed vault in the Arctic",
        "How a small town's orchestra survived on borrowed instruments",
        "Following honeybee keepers across one year of drought",
        "The engineers racing to save a crumbling medieval bridge",
    ],
}

# Hand-written rows the query checks look for.
ANCHORS = [
    (1001, "Aurora Pictures", "Orbit's Edge", "Astronauts are stranded on a failing space station far from Earth and must improvise a way home", "sci-fi"),
    (1002, "Blue Harbor Films", "The Last Recipe", "A grandmother teaches her grandson the family recipes before the restaurant closes forever", "documentary"),
    (1003, "Paper Moon Studio", "Hollow Pines", "Teenagers at a summer camp are stalked by a creature living in the woods", "horror"),
]


def session():
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.load_verify_locations(certifi.where())
    ctx.verify_mode = ssl.CERT_REQUIRED
    ctx.check_hostname = False
    auth = SigV4AuthProvider(boto3.Session(profile_name=PROFILE, region_name=REGION))
    cluster = Cluster([f"cassandra.{REGION}.amazonaws.com"], port=9142, ssl_context=ctx, auth_provider=auth,
                      protocol_version=4,
                      execution_profiles={EXEC_PROFILE_DEFAULT: ExecutionProfile(consistency_level=ConsistencyLevel.LOCAL_QUORUM)})
    return cluster.connect(KEYSPACE)


def movie(i, rng):
    genre = list(PLOTS)[i % len(PLOTS)]
    plot = rng.choice(PLOTS[genre])
    studio = STUDIOS[i % len(STUDIOS)]
    released = datetime(2000, 1, 1, tzinfo=timezone.utc) + timedelta(days=rng.randint(0, 9000))
    return (studio, i, f"{genre.title()} Story {i}", f"{plot}. Episode {i}.", genre, released, rng.randint(0, 100000))


INSERT = f"INSERT INTO {TABLE} (studio, movie_id, title, overview, genre, release_date, views) VALUES (?, ?, ?, ?, ?, ?, ?)"


def seed(s):
    rng = random.Random(42)
    ins = s.prepare(INSERT)
    for i in range(1, 301):
        s.execute(ins, movie(i, rng))
    for mid, studio, title, overview, genre in ANCHORS[:2]:
        s.execute(ins, (studio, mid, title, overview, genre, datetime(2020, 5, 1, tzinfo=timezone.utc), 5))
    print("seeded 302 rows (300 generated + 2 anchors; the third anchor arrives on the stream)")


def mutate(s):
    rng = random.Random(7)
    ins = s.prepare(INSERT)
    for i in range(301, 321):
        s.execute(ins, movie(i, rng))
    mid, studio, title, overview, genre = ANCHORS[2]
    s.execute(ins, (studio, mid, title, overview, genre, datetime(2021, 10, 31, tzinfo=timezone.utc), 1))
    upd_overview = s.prepare(f"UPDATE {TABLE} SET overview = ? WHERE studio = ? AND movie_id = ?")
    upd_genre = s.prepare(f"UPDATE {TABLE} SET genre = ? WHERE studio = ? AND movie_id = ?")
    upd_views = s.prepare(f"UPDATE {TABLE} SET views = ? WHERE studio = ? AND movie_id = ?")
    delete = s.prepare(f"DELETE FROM {TABLE} WHERE studio = ? AND movie_id = ?")
    for i in range(1, 11):
        s.execute(upd_overview, (f"Rewritten plot {i}: a lighthouse keeper guards a secret map of sunken islands", STUDIOS[i % 5], i))
    for i in range(11, 21):
        s.execute(upd_genre, ("musical", STUDIOS[i % 5], i))
    for i in range(21, 31):
        s.execute(upd_views, (999999, STUDIOS[i % 5], i))
    for i in range(31, 41):
        s.execute(delete, (STUDIOS[i % 5], i))
    print("mutated: 21 inserts, 10 overview edits, 10 genre edits, 10 views edits, 10 deletes")


def mutate_offline(s):
    rng = random.Random(9)
    ins = s.prepare(INSERT)
    for i in range(321, 324):
        s.execute(ins, movie(i, rng))
    s.execute(s.prepare(f"UPDATE {TABLE} SET overview = ? WHERE studio = ? AND movie_id = ?"),
              ("Changed while the connector was stopped: a cartographer maps a city that rearranges itself", STUDIOS[50 % 5], 50))
    s.execute(s.prepare(f"DELETE FROM {TABLE} WHERE studio = ? AND movie_id = ?"), (STUDIOS[60 % 5], 60))
    print("offline changes: 3 inserts, 1 overview edit, 1 delete")


def mutate_retry(s):
    rng = random.Random(11)
    ins = s.prepare(INSERT)
    for i in range(330, 335):
        s.execute(ins, movie(i, rng))
    print("inserted rows 330-334")


def key(studio, movie_id):
    enc = lambda v: urllib.parse.quote(str(v), safe="")
    return f"movie_id={enc(movie_id)}&studio={enc(studio)}"


def s3v():
    return boto3.Session(profile_name=PROFILE, region_name=REGION).client("s3vectors")


def bucket():
    account = boto3.Session(profile_name=PROFILE, region_name=REGION).client("sts").get_caller_identity()["Account"]
    return f"keyvector-e2e-{account}"


def all_vectors(index):
    c, out, token = s3v(), {}, None
    while True:
        args = dict(vectorBucketName=bucket(), indexName=index, returnMetadata=True, maxResults=1000)
        if token:
            args["nextToken"] = token
        r = c.list_vectors(**args)
        for v in r.get("vectors", []):
            out[v["key"]] = v.get("metadata", {})
        token = r.get("nextToken")
        if not token:
            return out


def get_data(index, keys):
    c, out = s3v(), {}
    for i in range(0, len(keys), 100):
        r = c.get_vectors(vectorBucketName=bucket(), indexName=index, keys=keys[i:i + 100], returnData=True)
        for v in r.get("vectors", []):
            out[v["key"]] = v["data"]["float32"]
    return out


def snapshot(index, path):
    keys = [key(STUDIOS[i % 5], i) for i in range(1, 31)]
    data = get_data(index, keys)
    json.dump(data, open(path, "w"))
    print(f"saved {len(data)} vectors from {index} to {path}")


def verify(s, index, snapshot_path=None):
    rows = {key(r.studio, r.movie_id): r for r in s.execute(f"SELECT studio, movie_id, overview, genre FROM {TABLE}")}
    expected = {k for k, r in rows.items() if r.overview and r.overview.strip()}
    vectors = all_vectors(index)
    failures = []
    missing, extra = expected - set(vectors), set(vectors) - expected
    if missing:
        failures.append(f"{len(missing)} rows have no vector, e.g. {sorted(missing)[:3]}")
    if extra:
        failures.append(f"{len(extra)} vectors have no row, e.g. {sorted(extra)[:3]}")
    wrong_genre = [k for k in expected & set(vectors) if vectors[k].get("genre") != rows[k].genre]
    if wrong_genre:
        failures.append(f"{len(wrong_genre)} vectors carry a stale genre, e.g. {wrong_genre[:3]}")
    if snapshot_path:
        before = json.load(open(snapshot_path))
        after = get_data(index, list(before))
        same = lambda k: k in after and max(abs(a - b) for a, b in zip(before[k], after[k])) < 1e-6
        for i in range(1, 31):
            k = key(STUDIOS[i % 5], i)
            if k not in before:
                failures.append(f"snapshot has no vector for {k}")
            elif 1 <= i <= 10 and same(k):
                failures.append(f"overview of {k} changed but its vector did not")
            elif 11 <= i <= 30 and not same(k):
                failures.append(f"{k} was re-embedded although its text did not change")
    print(json.dumps({"index": index, "rows": len(rows), "rows_with_text": len(expected), "vectors": len(vectors),
                      "missing": len(missing), "extra": len(extra), "stale_genre": len(wrong_genre),
                      "ok": not failures}, indent=2))
    for f in failures:
        print("FAIL", f)
    return not failures


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "snapshot":
        snapshot(sys.argv[2], sys.argv[3])
        sys.exit(0)
    s = session()
    if cmd == "seed":
        seed(s)
    elif cmd == "mutate":
        mutate(s)
    elif cmd == "mutate-offline":
        mutate_offline(s)
    elif cmd == "mutate-retry":
        mutate_retry(s)
    elif cmd == "verify":
        snap = sys.argv[sys.argv.index("--snapshot") + 1] if "--snapshot" in sys.argv else None
        sys.exit(0 if verify(s, sys.argv[2], snap) else 1)
    else:
        print(__doc__)
        sys.exit(2)

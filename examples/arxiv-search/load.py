"""Load the first 1,000 arXiv abstracts into Amazon Keyspaces.

There is no embedding code here: the application writes ordinary rows, and Keyvector,
following the table's change stream, embeds them and keeps the vector index in step.

    python load.py            # load 1,000 papers
    python load.py update     # rewrite one abstract   (Keyvector re-embeds it)
    python load.py delete     # delete one paper       (Keyvector deletes its vector)
"""
import gzip
import json
import os
import ssl
import sys
import urllib.request

import boto3
import certifi
from cassandra import ConsistencyLevel
from cassandra.cluster import EXEC_PROFILE_DEFAULT, Cluster, ExecutionProfile
from cassandra_sigv4.auth import SigV4AuthProvider

DATASET_URL = "https://huggingface.co/datasets/gfissore/arxiv-abstracts-2021/resolve/main/arxiv-abstracts.jsonl.gz"
SAMPLE_SIZE = 1000
REGION = os.environ.get("AWS_REGION", "us-east-1")


def clean(text):
    return " ".join((text or "").split())


def session():
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.load_verify_locations(certifi.where())
    ctx.verify_mode = ssl.CERT_REQUIRED
    ctx.check_hostname = False
    auth = SigV4AuthProvider(boto3.Session(region_name=REGION))
    cluster = Cluster([f"cassandra.{REGION}.amazonaws.com"], port=9142, ssl_context=ctx, auth_provider=auth,
                      protocol_version=4,
                      execution_profiles={EXEC_PROFILE_DEFAULT: ExecutionProfile(consistency_level=ConsistencyLevel.LOCAL_QUORUM)})
    return cluster.connect("research")


def load_papers():
    """Stream the gzip'd JSON Lines corpus (~940 MB) and read only the first SAMPLE_SIZE records."""
    req = urllib.request.Request(DATASET_URL, headers={"User-Agent": "keyvector-example"})
    papers = []
    with urllib.request.urlopen(req) as resp, gzip.GzipFile(fileobj=resp) as gz:
        for _, line in zip(range(SAMPLE_SIZE), gz):
            papers.append(json.loads(line))
    return papers


def load(s):
    insert = s.prepare("INSERT INTO papers (paper_id, title, abstract, authors, category) VALUES (?, ?, ?, ?, ?)")
    papers = load_papers()
    for p in papers:
        cats = p.get("categories") or []
        # The corpus stores categories as a list of space-separated strings, e.g. ["hep-ph"].
        category = (cats[0] if isinstance(cats, list) and cats else str(cats)).split(" ")[0]
        s.execute(insert, (p["id"], clean(p.get("title")), clean(p.get("abstract")), clean(p.get("authors")), category))
    print(f"Wrote {len(papers)} papers to research.papers")


if __name__ == "__main__":
    s = session()
    cmd = sys.argv[1] if len(sys.argv) > 1 else "load"
    if cmd == "load":
        load(s)
    elif cmd == "update":
        s.execute("UPDATE papers SET abstract = %s WHERE paper_id = %s",
                  ("We report the first detection of gravitational waves from the merger of two black holes, "
                   "observed by a pair of laser interferometers.", "0704.0002"))
        print("Rewrote the abstract of paper 0704.0002")
    elif cmd == "delete":
        s.execute("DELETE FROM papers WHERE paper_id = %s", ("0704.0001",))
        print("Deleted paper 0704.0001")
    else:
        sys.exit(__doc__)

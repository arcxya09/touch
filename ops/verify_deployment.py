"""Reject a healthy but stale backend before publishing a compatible client."""
import argparse
import json
import urllib.request

RECALL_PATH = "/api/v1/conversations/{conversation_id}/messages/{message_id}/recall"


def validate(health, schema, version, build):
    if health.get("status") != "ok" or health.get("version") != version or health.get("build") != build:
        raise ValueError("Deployed backend identity or health does not match the release")
    if "post" not in schema.get("paths", {}).get(RECALL_PATH, {}):
        raise ValueError("Deployed backend is missing the recall endpoint")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--build", required=True)
    args = parser.parse_args()

    def read(path):
        request = urllib.request.Request(args.url.rstrip("/") + path,
                                         headers={"Cache-Control": "no-cache"})
        with urllib.request.urlopen(request, timeout=20) as response:
            return json.load(response)

    validate(read("/health"), read("/openapi.json"), args.version, args.build)
    print("Deployed backend version, build, health and recall endpoint verified")


if __name__ == "__main__":
    main()

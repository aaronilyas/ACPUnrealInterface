#!/usr/bin/env python3
"""Minimal ACP agent used to prove the client can open a session over stdio."""

import json
import os
import sys


def send(message):
    sys.stdout.write(json.dumps(message, separators=(",", ":")) + "\n")
    sys.stdout.flush()


def read_message():
    while True:
        line = sys.stdin.readline()
        if line == "":
            return None
        line = line.strip()
        if line:
            return json.loads(line)


def fail(request_id, message):
    send({
        "jsonrpc": "2.0",
        "id": request_id,
        "error": {"code": -32603, "message": message},
    })


def wait_for(request_id):
    while True:
        message = read_message()
        if message is None:
            return None
        if message.get("id") == request_id and "method" not in message:
            return message
        if "id" in message and "method" in message:
            fail(message["id"], "Busy waiting for fs/read_text_file")


def handle_prompt(message):
    session_id = message.get("params", {}).get("sessionId", "sess-1")
    read_path = os.environ.get("FAKE_AGENT_READ", "")
    body = "ready"
    if read_path:
        send({
            "jsonrpc": "2.0",
            "id": 9001,
            "method": "fs/read_text_file",
            "params": {"sessionId": session_id, "path": read_path},
        })
        reply = wait_for(9001)
        if reply is None:
            return
        if "error" in reply:
            body = "ERROR " + json.dumps(reply["error"])
        else:
            body = "read:" + reply.get("result", {}).get("content", "")
    send({
        "jsonrpc": "2.0",
        "method": "session/update",
        "params": {
            "sessionId": session_id,
            "update": {
                "sessionUpdate": "agent_message_chunk",
                "content": {"type": "text", "text": body},
            },
        },
    })
    send({
        "jsonrpc": "2.0",
        "method": "session/update",
        "params": {
            "sessionId": session_id,
            "update": {
                "sessionUpdate": "tool_call",
                "toolCallId": "call-1",
                "title": "Echo the file",
                "name": "echo",
                "kind": "read",
                "status": "completed",
            },
        },
    })
    send({
        "jsonrpc": "2.0",
        "id": message["id"],
        "result": {"stopReason": "end_turn"},
    })


def main():
    print("fake-agent ready", file=sys.stderr, flush=True)
    while True:
        message = read_message()
        if message is None:
            return
        method = message.get("method")
        if method is None:
            continue
        request_id = message.get("id")
        if method == "initialize":
            send({
                "jsonrpc": "2.0",
                "id": request_id,
                "result": {
                    "protocolVersion": 1,
                    "agentCapabilities": {},
                    "agentInfo": {"name": "fake-agent", "version": "0.0.1"},
                },
            })
        elif method == "session/new":
            send({
                "jsonrpc": "2.0",
                "id": request_id,
                "result": {"sessionId": "sess-1"},
            })
        elif method == "session/prompt":
            handle_prompt(message)
        elif method == "session/cancel":
            continue
        elif request_id is not None:
            fail(request_id, "Method not found: " + method)


if __name__ == "__main__":
    try:
        main()
    except BrokenPipeError:
        pass

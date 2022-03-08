# This file is part of BenchExec, a framework for reliable benchmarking:
# https://github.com/sosy-lab/benchexec
#
# SPDX-FileCopyrightText: 2007-2020 Dirk Beyer <https://www.sosy-lab.org>
#
# SPDX-License-Identifier: Apache-2.0

import argparse
import json
import sys
import webbrowser
import networkx as nx
import pydot

from airium import Airium
from pathlib import Path

block_analysis_file_name = "block_analysis.json"
summary_file_name = "blocks.json"


def create_arg_parser():
    parser = argparse.ArgumentParser(description="Transforms Worker logs to HTML.")
    parser.add_argument("-d", "--directory",
                        type=str,
                        help="set the path to the logs of worker (adjustable block "
                             "analysis) usually found here: output/block_analysis",
                        default="../../output/block_analysis")
    return parser


def parse_jsons(file):
    with open(file, "r") as f:
        return json.loads(f.read())


def html_for_message(message, block_log):
    div = Airium()

    if not message:
        with div.div():
            div("")
        return str(div), ""

    infos = block_log[message["from"]]

    predecessors = ["none"] if "predecessors" not in infos else infos["predecessors"]
    successors = ["none"] if "successors" not in infos else infos["successors"]
    result = message["payload"] if message["payload"] else "no contents available"
    direction = message["type"]
    arrow = "-"
    senders = ["all"]
    receivers = ["all"]
    if direction == "BLOCK_POSTCONDITION":
        receivers = successors
        senders = predecessors
        arrow = "&darr;"
    elif direction == "ERROR_CONDITION":
        receivers = predecessors
        senders = successors
        arrow = "&uarr;"
    elif direction == "ERROR_CONDITION_UNREACHABLE":
        receivers = ["all"]
        senders = successors
        arrow = "&uarr;"
    elif direction == "FOUND_RESULT":
        senders = [message["from"]]

    code = "\n".join([x for x in infos["code"] if x])

    with div.div(title=code):
        with div.p():
            with div.span():
                div(arrow)
            with div.span():
                sender = "self"
                if senders:
                    sender = ", ".join(senders)
                div(f"React to message from <strong>{sender}</strong>:")
        with div.p():
            receiver = ", ".join(receivers)
            div(f"Calculated new {direction} message for <strong>{receiver}</strong>")
        div.textarea(_t=result)

    return str(div)


def html_dict_to_html_table(all_messages, block_logs: dict):
    first_timestamp = int(all_messages[0]["timestamp"])
    timestamp_to_message = {}
    sorted_keys = sorted(block_logs.keys(), key=lambda x: int(x[1::]))
    index_dict = {}
    for index in enumerate(sorted_keys):
        index_dict[index[1]] = index[0]
    for message in all_messages:
        timestamp_to_message.setdefault(message["timestamp"] - first_timestamp, [""] * len(block_logs))[
            index_dict[message["from"]]] = message
    headers = ["time"] + sorted_keys
    table = Airium()
    with table.table(klass="worker"):
        # header
        with table.tr(klass='header_row'):
            for key in headers:
                table.th(_t=f'{key}')

        # row values
        type_to_klass = {
            "BLOCK_POSTCONDITION": "precondition",
            "ERROR_CONDITION": "postcondition",
            "ERROR_CONDITION_UNREACHABLE": "postcondition"
        }
        for timestamp in timestamp_to_message:
            with table.tr():
                table.td(_t=str(timestamp))
                messages = timestamp_to_message[timestamp]
                for msg in messages:
                    if not msg:
                        table.td()
                    else:
                        klass = type_to_klass[msg["type"]] if msg["type"] in type_to_klass else "normal"
                        table.td(klass=klass, _t=html_for_message(msg, block_logs))

    return str(table)


def visualize(output_path):
    g = nx.DiGraph()
    block_logs = parse_jsons(str(Path(output_path) / Path(summary_file_name)))
    for key in block_logs:
        code = "\n".join(c for c in block_logs[key]["code"] if c)
        label = key + ":\n" + code if code else key
        g.add_node(key, shape="box", label=label)
    for key in block_logs:
        if "successors" in block_logs[key]:
            for successor in block_logs[key]["successors"]:
                g.add_edge(key, successor)

    graph_dot = Path(output_path) / Path("graph.dot")
    nx.drawing.nx_pydot.write_dot(g, str(graph_dot))
    (graph,) = pydot.graph_from_dot_file(str(graph_dot))
    graph.write_png(str(Path(output_path) / Path("graph.png")))


def main(argv=None):
    parser = create_arg_parser()
    args = parser.parse_args(argv)
    output_path = args.directory
    block_logs = parse_jsons(str(Path(output_path) / Path(block_analysis_file_name)))
    all_messages = []
    for key in block_logs:
        if "messages" in block_logs[key]:
            all_messages += block_logs[key]["messages"]
    if not all_messages:
        return
    all_messages = sorted(all_messages, key=lambda entry: (entry["timestamp"], entry["from"][1::]))
    with open("table.html") as html:
        with open("table.css") as css:
            text = html.read().replace(
                "<!--<<<TABLE>>><!-->", html_dict_to_html_table(all_messages, block_logs)
            ).replace("/*CSS*/", css.read())
            with open(Path(output_path) / Path("report.html"), "w+") as new_html:
                new_html.write(text)
    visualize(output_path)
    webbrowser.open(str(Path(output_path) / Path("report.html")))


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Mutation-based regression check.

Applies one deliberate production-code mutation at a time, recompiles and runs the
new backtracking/error-merge property tests. Every mutation MUST be killed (suite
fails, errors, or times out). Sources are restored afterwards no matter what.
"""
import os
import subprocess
import sys
import re

MODULE = "jparsec"
TEST = "BacktrackingPropertyTest"
REPORT = os.path.join("target", "mutation-check")

# (path, description, find_regex, replacement). Every regex must match exactly once.
MUTATIONS = [
    (
        "jparsec/src/main/java/org/jparsec/Parsers.java",
        "or-position-rollback-removed",
        r"\n[ \t]*ctxt\.set\(step, at, result\);\n([ \t]*}\n[ \t]*return false;)",
        r"\n\1",
    ),
    (
        "jparsec/src/main/java/org/jparsec/ParseContext.java",
        "furthest-error-preference-removed",
        r"\n[ \t]*if \(at < currentErrorAt\) return;\n",
        "\n",
    ),
    (
        "jparsec/src/main/java/org/jparsec/RepeatAtLeastParser.java",
        "empty-parser-guard-removed",
        r"\n[ \t]*if \(physical == at2\) return true;\n",
        "\n",
    ),
]


def run(cmd, log):
    with open(log, "w") as out:
        proc = subprocess.run(cmd, stdout=out, stderr=subprocess.STDOUT)
    return proc.returncode


def main():
    os.makedirs(REPORT, exist_ok=True)
    overall = 0
    try:
        for path, tag, pattern, repl in MUTATIONS:
            print("== mutation [%s] ==" % tag)
            original = open(path).read()
            mutated, n = re.subn(pattern, repl, original, count=1)
            if n != 1:
                print("  SETUP ERROR: pattern matched %d times in %s" % (n, path))
                overall = 1
                continue
            compile_log = os.path.join(REPORT, tag + ".compile.log")
            run_log = os.path.join(REPORT, tag + ".log")
            try:
                open(path, "w").write(mutated)
                if run(["mvn", "-q", "-pl", MODULE, "test-compile"], compile_log) != 0:
                    print("  KILLED (compile error); see %s" % compile_log)
                    continue
                rc = run(
                    ["mvn", "-q", "-pl", MODULE, "surefire:test",
                     "-Dtest=" + TEST, "-DfailIfNoTests=false"],
                    run_log)
                text = open(run_log).read()
                if rc != 0 or ("Failures: 0, Errors: 0" not in text):
                    print("  KILLED (suite failed/errored as expected); see %s" % run_log)
                else:
                    print("  SURVIVED: suite passed for %s" % tag)
                    overall = 1
            finally:
                open(path, "w").write(original)
                # rebuild original classes so later steps start from clean state
                run(["mvn", "-q", "-pl", MODULE, "test-compile"],
                    os.path.join(REPORT, "restore.log"))
    finally:
        pass

    print()
    if overall == 0:
        print("All mutations were killed by %s." % TEST)
    else:
        print("Mutation check found a surviving mutation or setup error.")
    return overall


if __name__ == "__main__":
    sys.exit(main())

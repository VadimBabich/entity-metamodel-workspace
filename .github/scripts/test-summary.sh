#!/usr/bin/env bash
#
# Writes the CI job summary of a build as Markdown on stdout: tests per phase, the slowest test classes, the Spring
# contexts the integration tests loaded, and the merged coverage. It reports and never judges: a slow phase or low coverage
# fails nothing. It fails only when the reports it needs are missing or unreadable, so an empty summary cannot pass
# for a good one.
#
# Usage:
#   test-summary.sh                 # workspace-app
#   test-summary.sh <module-dir>

set -euo pipefail

readonly DEFAULT_MODULE='workspace-app'
readonly SLOWEST_CLASS_COUNT=10

# argv: module directory, slowest-class count. Prints the summary; exits 1 with ERROR lines on stderr when a report
# is missing or does not parse.
readonly RENDER_SUMMARY='
import csv
import glob
import os
import sys
import xml.etree.ElementTree as ElementTree

module_dir = sys.argv[1]
slowest_class_count = int(sys.argv[2])
target = os.path.join(module_dir, "target")
cache_file = os.path.join(target, "context-cache.txt")
phases = [("Unit (Surefire)", "Unit", "surefire-reports"), ("Integration (Failsafe)", "Integration", "failsafe-reports")]


def fail(message):
    print("ERROR %s" % message, file=sys.stderr)
    sys.exit(1)


def read_suites(report_dir):
    suites = []
    for report_path in sorted(glob.glob(os.path.join(report_dir, "TEST-*.xml"))):
        try:
            suite = ElementTree.parse(report_path).getroot()
        except ElementTree.ParseError as error:
            fail("%s does not parse: %s" % (report_path, error))
        suites.append(suite)
    return suites


def seconds(suite):
    return float(suite.get("time", "0").replace(",", ""))


def count(suites, attribute):
    total = 0
    for suite in suites:
        total += int(suite.get(attribute, "0"))
    return total


def phase_rows():
    rows = []
    timed_classes = []
    for title, short_name, report_folder in phases:
        suites = read_suites(os.path.join(target, report_folder))
        phase_seconds = 0.0
        for suite in suites:
            phase_seconds += seconds(suite)
            timed_classes.append((seconds(suite), suite.get("name"), short_name))
        rows.append((title, suites, phase_seconds))
    return rows, timed_classes


def cache_statistics():
    statistics = {}
    with open(cache_file) as statistics_file:
        for pair in statistics_file.read().split():
            key, _, number = pair.partition("=")
            statistics[key] = number
    return statistics


def coverage(csv_path):
    totals = {"LINE_COVERED": 0, "LINE_MISSED": 0, "BRANCH_COVERED": 0, "BRANCH_MISSED": 0}
    with open(csv_path, newline="") as csv_file:
        try:
            for row in csv.DictReader(csv_file):
                for column in totals:
                    totals[column] += int(row[column])
        except (KeyError, ValueError) as error:
            fail("%s is not a JaCoCo CSV report: %r" % (csv_path, error))
    return totals


def ratio(covered, missed):
    total = covered + missed
    if total == 0:
        return "none measured"
    return "%d of %d (%.1f %%)" % (covered, total, 100.0 * covered / total)


rows, timed_classes = phase_rows()
if not timed_classes:
    fail("no test reports under %s" % target)

integration_suites = rows[1][1]
coverage_csv = os.path.join(target, "site", "jacoco-aggregate", "jacoco.csv")
coverage_written = os.path.isfile(coverage_csv)
if integration_suites and not coverage_written:
    fail("no coverage report at %s" % coverage_csv)

print("## Tests")
print()
print("| Phase | Tests | Failures | Errors | Skipped | Time |")
print("|---|---|---|---|---|---|")
for title, suites, phase_seconds in rows:
    print("| %s | %d | %d | %d | %d | %.1f s |" % (
        title, count(suites, "tests"), count(suites, "failures"), count(suites, "errors"),
        count(suites, "skipped"), phase_seconds))
print()

print("### Time report")
print()
print("Reported only; nothing fails on time.")
print()
print("| Slowest test classes | Phase | Time |")
print("|---|---|---|")
timed_classes.sort(reverse=True)
for class_seconds, class_name, phase in timed_classes[:slowest_class_count]:
    print("| %s | %s | %.1f s |" % (class_name, phase, class_seconds))
print()

print("### Spring contexts")
print()
if not os.path.isfile(cache_file):
    print("No statistics: ContextBudgetIT did not run.")
else:
    statistics = cache_statistics()
    print("| Contexts loaded | Budget | Cache size | Cache maximum |")
    print("|---|---|---|---|")
    print("| %s | %s | %s | %s |" % (
        statistics.get("missCount", "?"), statistics.get("budget", "?"),
        statistics.get("size", "?"), statistics.get("maxSize", "?")))
print()

print("### Coverage")
print()
if not coverage_written:
    print("No coverage: the build stopped before the integration phase.")
else:
    coverage_totals = coverage(coverage_csv)
    print("Merged unit and integration coverage of hand-written code; reported only.")
    print()
    print("| Lines | Branches |")
    print("|---|---|")
    print("| %s | %s |" % (
        ratio(coverage_totals["LINE_COVERED"], coverage_totals["LINE_MISSED"]),
        ratio(coverage_totals["BRANCH_COVERED"], coverage_totals["BRANCH_MISSED"])))
'

log_error() {
  printf 'ERROR %s\n' "$1" >&2
}

main() {
  local module_dir="${1:-${DEFAULT_MODULE}}"
  local summary

  if [ ! -d "${module_dir}" ]; then
    log_error "no module directory ${module_dir}"
    exit 1
  fi

  if ! summary="$(python3 -c "${RENDER_SUMMARY}" "${module_dir}" "${SLOWEST_CLASS_COUNT}")"; then
    exit 1
  fi

  printf '%s\n' "${summary}"
}

main "$@"

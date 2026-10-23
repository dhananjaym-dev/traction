#!/usr/bin/env bash
# Scenario 4: a charter (bulk run) on the second airport, mail:acme (300 slots/min). Open it with
# an expected count, watch its progress, abort it mid-run: the rest of its flights settle SKIPPED
# (the skip window), and the charter completes once those rows make the books add up.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
COUNT="${COUNT:-600}"
ABORT_AT="${ABORT_AT:-340}"
AIRPORT=mail:acme

reset
since=$(now_ts)

charter() { curl -fsS "$API/charters?airportId=$AIRPORT" | jq -c --arg r "$ref" '.[] | select(.charterRef == $r)'; }
accounted() { jq -r '.landed + .rejected + .skipped + .parked + .discarded' <<<"$1"; }

banner "4. a bulk welcome-email run on $AIRPORT"
runs "curl -fsS -X POST '$APP/seed/mailCharter?count=$COUNT'"
ref=$(jq -r .charterRef <<<"$OUT")
charter_id=${ref##*/}

banner "one run at a time per gate (PER_KEY admission)"
sleep 1.1 # a new run id (run-<epoch second>)
runs "curl -sS -w '\nHTTP %{http_code}\n' -X POST '$APP/seed/mailCharter?count=5'"
assert_eq "$(sed -n 's/^HTTP //p' <<<"$OUT")" 409 "second open while the first is open"

banner "progress"
say "polling GET $API/charters?airportId=$AIRPORT"
progress() {
  local c; c=$(charter)
  jq -r '"  \(.status) · landed \(.landed)/\(.expected) · skipped \(.skipped) · parked \(.parked)"' <<<"$c"
  [[ "$(jq -r .landed <<<"$c")" -ge "$ABORT_AT" ]]
}
wait_until 300 "the run to pass $ABORT_AT landed" progress

banner "abort mid-run"
abort_ts=$(now_ts)
runs "curl -fsS -X POST -H 'X-Actor: dana' '$API/charters/$AIRPORT/welcome/$charter_id/abort'"

banner "the skip window: the rest settle SKIPPED, then the charter completes"
settled() {
  local c b; c=$(charter); b=$(backlog "$AIRPORT")
  jq -r '"  \(.status) (aborted by \(.abortedBy)) · landed \(.landed) · skipped \(.skipped) · accounted \(.landed + .rejected + .skipped + .parked + .discarded)/\(.expected)"' <<<"$c"
  [[ "$b" == 0 && "$(jq -r .status <<<"$c")" != OPEN && "$(accounted "$c")" -ge "$COUNT" ]]
}
wait_until 120 "the aborted run to settle and complete" settled
runs "curl -fsS '$API/charters?airportId=$AIRPORT' | jq -c '.[] | {charterRef, status, source, expected, landed, rejected, skipped, parked, discarded, abortedBy}'"
sleep 1 # let the last notify line reach the container log
say "notifications:"
notify_lines CHARTER_ "$since"

c=$(charter)
assert_eq "$(accounted "$c")" "$COUNT" "landed + rejected + skipped + parked + discarded"
assert_eq "$(jq -r .status <<<"$c")" ABORTED "status"
assert_eq "$(jq -r .source <<<"$c")" ABORT "completion source"
assert_eq "$(jq -r .abortedBy <<<"$c")" dana "aborted by"
assert_gt "$(jq -r .skipped <<<"$c")" 0 "skipped by the abort"
assert_eq "$(jq -r .landed <<<"$c")" "$(vendor_stat emails ok)" "landed == vendor emails delivered"
assert_eq "$(notify_count CHARTER_ABORTED "$since")" 1 "CHARTER_ABORTED notifications"
assert_eq "$(notify_count CHARTER_COMPLETED "$since")" 1 "CHARTER_COMPLETED notifications"
assert_eq "$(notify_count CHARTER_COMPLETED "$abort_ts")" 1 "CHARTER_COMPLETED after the abort"

# The completion line itself must carry books that add up.
line=$(notify_lines CHARTER_COMPLETED "$abort_ts")
kv() { sed -n "s/.* $1=\([^ ]*\).*/\1/p" <<<"$line"; }
assert_eq "$(kv source)" ABORT "CHARTER_COMPLETED source"
assert_eq "$(( $(kv landed) + $(kv rejected) + $(kv skipped) + $(kv parked) + $(kv discarded) ))" "$COUNT" \
  "CHARTER_COMPLETED landed + rejected + skipped + parked + discarded"
assert_eq "$(kv landed)/$(kv skipped)" "$(jq -r '"\(.landed)/\(.skipped)"' <<<"$c")" "CHARTER_COMPLETED landed/skipped == final snapshot"
ok "scenario-charter"

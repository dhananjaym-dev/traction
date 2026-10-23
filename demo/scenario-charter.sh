#!/usr/bin/env bash
# Scenario 4: a charter (bulk run) on the second airport, mail:acme (300 slots/min). Open it with
# an expected count, watch its progress, abort it mid-run: the rest of its flights settle SKIPPED
# (the skip window) and the books still add up to the expected count.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
COUNT="${COUNT:-600}"
ABORT_AT="${ABORT_AT:-340}"
AIRPORT=mail:acme

reset
since=$(now_ts)

charter() { curl -fsS "$API/charters?airportId=$AIRPORT" | jq -c --arg r "$ref" '.[] | select(.charterRef == $r)'; }
field()   { charter | jq -r ".$1"; }

banner "4. a bulk welcome-email run on $AIRPORT"
out=$(curl -fsS -X POST "$APP/seed/mailCharter?count=$COUNT")
printf '  %s$ curl -X POST %s/seed/mailCharter?count=%s%s\n' "$DIM" "$APP" "$COUNT" "$OFF"
printf '%s' "$out" | indent
ref=$(jq -r .charterRef <<<"$out")
charter_id=${ref##*/}

banner "one run at a time per gate (PER_KEY admission)"
sleep 1.1 # a new run id (run-<epoch second>)
printf '  %s$ curl -X POST %s/seed/mailCharter?count=5%s\n' "$DIM" "$APP" "$OFF"
resp=$(curl -sS -w '\n%{http_code}' -X POST "$APP/seed/mailCharter?count=5")
second_status=${resp##*$'\n'}
printf '%s' "${resp%$'\n'*}" | indent
say "HTTP $second_status: the first run is still open"
assert_eq "$second_status" 409 "second open while the first is open"

banner "progress"
say "$ curl $API/charters?airportId=$AIRPORT"
progress() {
  local c; c=$(charter)
  jq -r '"  \(.status) · landed \(.landed)/\(.expected) · skipped \(.skipped) · parked \(.parked)"' <<<"$c"
  [[ "$(jq -r .landed <<<"$c")" -ge "$ABORT_AT" ]]
}
wait_until 300 "the run to pass $ABORT_AT landed" progress

banner "abort mid-run"
runs "curl -fsS -X POST -H 'X-Actor: dana' $API/charters/$AIRPORT/welcome/$charter_id/abort"
landed_at_abort=$(field landed)

banner "the skip window: the rest settle SKIPPED"
settled() {
  local c b; c=$(charter); b=$(backlog "$AIRPORT")
  jq -r '"  \(.status) · landed \(.landed) · skipped \(.skipped) · accounted \(.landed + .rejected + .skipped + .parked + .discarded)/\(.expected)"' <<<"$c"
  [[ "$b" == 0 && "$(jq -r '.landed + .rejected + .skipped + .parked + .discarded' <<<"$c")" -ge "$COUNT" ]]
}
wait_until 120 "the aborted run to settle" settled
runs "curl -fsS '$API/charters?airportId=$AIRPORT' | jq -c '.[] | {charterRef, status, source, expected, landed, rejected, skipped, parked, discarded, abortedBy}'"
say "notifications:"
notify_lines CHARTER_ "$since"

c=$(charter)
accounted=$(jq -r '.landed + .rejected + .skipped + .parked + .discarded' <<<"$c")
assert_eq "$accounted" "$COUNT" "landed + rejected + skipped + parked + discarded"
assert_eq "$(jq -r .source <<<"$c")" ABORT "completion source"
assert_eq "$(jq -r .abortedBy <<<"$c")" dana "aborted by"
assert_gt "$(jq -r .skipped <<<"$c")" 0 "skipped by the abort"
assert_eq "$(jq -r .landed <<<"$c")" "$(vendor_stat emails ok)" "landed == vendor emails delivered"
assert_eq "$(notify_count CHARTER_COMPLETED "$since")" 1 "CHARTER_COMPLETED notifications"
assert_eq "$(notify_count CHARTER_ABORTED "$since")" 1 "CHARTER_ABORTED notifications"
ok "scenario-charter"

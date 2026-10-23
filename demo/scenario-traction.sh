#!/usr/bin/env bash
# Scenario 2: the same expired token, the same 1,000 contacts, through the governed consumer.
# The first AIRPORT_FAULT ground-stops the airport, one notification goes out, and everything
# waits on the runway until one reopen curl lets it all land.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
COUNT="${COUNT:-1000}"
AIRPORT=crm:acme

reset
since=$(now_ts)

banner "2. the governed consumer (Traction airport $AIRPORT)"
say "Acme's API token has just expired..."
flip contacts token_expired
say "...and the same $COUNT contact syncs arrive."
run curl -fsS -X POST "$APP/seed/contacts?count=$COUNT"

banner "the first AIRPORT_FAULT ground-stops the airport"
grounded() { [[ "$(airport_status "$AIRPORT")" == GROUND_STOP ]]; }
wait_until 30 "$AIRPORT to ground-stop" grounded
runs "curl -fsS $API/airports/$AIRPORT | jq -c '{id, status, reason, detail}'"

waiting() {
  local b; b=$(backlog "$AIRPORT")
  say "waiting on the runway: $b"
  [[ "$b" == "$COUNT" ]]
}
wait_until 30 "all $COUNT flights to wait on the runway" waiting
sleep 3
waiting || fail "the runway did not hold still"
failed=$(vendor_stat contacts failed)
say "vendor calls burned against the dead token: $failed"

notifications=$(notify_count GROUND_STOP "$since")
say "notifications so far:"
notify_lines "" "$since"
assert_eq "$notifications" 1 "GROUND_STOP notifications"
assert_eq "$(log_count 'traction.notify type=' "$since")" 1 "notifications of any type"

banner "fix the token, then one curl"
flip contacts healthy
run curl -fsS -X POST "$API/airports/$AIRPORT/reopen"

banner "everything lands (paced at the airport's 20/s slot limit)"
landed_all() {
  local ok b; ok=$(vendor_stat contacts ok); b=$(backlog "$AIRPORT")
  say "landed $ok/$COUNT · runway+holding+parked $b"
  [[ "$b" == 0 && "$ok" -ge "$COUNT" ]]
}
wait_until 300 "all $COUNT flights to land" landed_all

landed=$(vendor_stat contacts ok)
lost=$((COUNT - landed))
assert_eq "$landed" "$COUNT" "contacts landed"
assert_eq "$(depth "tr.$AIRPORT.parked")" 0 "parked"
assert_eq "$(depth tr.dlq)" 0 "dead-lettered"
assert_eq "$(notify_count GROUND_STOP "$since")" 1 "GROUND_STOP notifications"

banner "the damage"
printf '  %slost: %s · notifications: %s · recovery: one curl%s\n\n' "$BOLD" "$lost" "$notifications" "$OFF"
ok "scenario-traction"

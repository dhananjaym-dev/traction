#!/usr/bin/env bash
# Scenario 3: a gate-level failure. Acme breaks its company endpoint's contract (422) while
# contacts keep working. Three distinct GATE_FAULTs in 5 minutes ground-stop the company-sync
# gate and its flights park; the contact-sync gate keeps landing. Fix, reopen the gate, replay.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
COMPANIES="${COMPANIES:-50}"
CONTACTS="${CONTACTS:-200}"
AIRPORT=crm:acme

reset
since=$(now_ts)

banner "3. one gate breaks, the airport keeps flying"
say "Acme changes its company API contract (contacts are fine)..."
flip companies contract_break
say "...while $COMPANIES company syncs and $CONTACTS contact syncs arrive back to back."
runs "curl -fsS -X POST '$APP/seed/companies?count=$COMPANIES'"
runs "curl -fsS -X POST '$APP/seed/contacts?count=$CONTACTS'"

banner "the company-sync gate ground-stops after the GATE_FAULT streak"
gate_grounded() { [[ "$(gate_status "$AIRPORT" company-sync)" == GROUND_STOP ]]; }
wait_until 60 "gate company-sync to ground-stop" gate_grounded
contacts_at_stop=$(vendor_stat contacts ok)
runs "curl -fsS '$API/airports/$AIRPORT' | jq -c '{status, gates: [.gates[] | {id, status, reason, detail}]}'"
assert_eq "$(gate_status "$AIRPORT" company-sync)" GROUND_STOP "gate company-sync"
assert_eq "$(gate_status "$AIRPORT" contact-sync)" OPEN "gate contact-sync"
assert_eq "$(airport_status "$AIRPORT")" OPEN "airport $AIRPORT"

contacts_landed() {
  local c p; c=$(vendor_stat contacts ok); p=$(depth "tr.$AIRPORT.parked")
  say "contacts landed $c/$CONTACTS · companies parked $p/$COMPANIES"
  [[ "$c" -ge "$CONTACTS" && "$p" == "$COMPANIES" ]]
}
wait_until 120 "contacts to land and companies to park" contacts_landed
assert_eq "$(vendor_stat contacts ok)" "$CONTACTS" "contacts landed"
assert_gt "$((CONTACTS - contacts_at_stop))" 0 "contacts that landed after company-sync ground-stopped"
assert_eq "$(gate_status "$AIRPORT" contact-sync)" OPEN "gate contact-sync"
parked=$(depth "tr.$AIRPORT.parked")
assert_eq "$parked" "$COMPANIES" "company flights parked (none lost)"
say "vendor company calls that failed: $(vendor_stat companies failed) (the streak, not $COMPANIES)"
say "notifications:"
notify_lines GROUND_STOP "$since"

banner "fix the contract, reopen the gate, replay what parked"
flip companies healthy
runs "curl -fsS -X POST '$API/airports/$AIRPORT/gates/company-sync/reopen'"
runs "curl -fsS -X POST '$API/airports/$AIRPORT/replayParked'"

companies_landed() {
  local c p b; c=$(vendor_stat companies ok); p=$(depth "tr.$AIRPORT.parked"); b=$(backlog "$AIRPORT")
  say "companies landed $c/$COMPANIES · parked $p · runway+holding+parked $b"
  [[ "$c" -ge "$COMPANIES" && "$b" == 0 ]]
}
wait_until 120 "the replayed companies to land" companies_landed
assert_eq "$(vendor_stat companies ok)" "$COMPANIES" "companies landed after replay"
assert_eq "$(depth "tr.$AIRPORT.parked")" 0 "parked"
assert_eq "$(depth tr.dlq)" 0 "dead-lettered"
assert_eq "$(gate_status "$AIRPORT" company-sync)" OPEN "gate company-sync"
ok "scenario-gate"

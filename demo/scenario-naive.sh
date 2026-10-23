#!/usr/bin/env bash
# Scenario 1: the hand-rolled baseline. A plain @RabbitListener with the usual retry limit and a
# dead-letter queue meets an expired vendor token: every message is retried into the DLQ.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
COUNT="${COUNT:-1000}"

reset
since=$(now_ts)

banner "1. the naive consumer (plain @RabbitListener, retry limit 3, DLQ)"
say "Acme's API token has just expired..."
flip contacts token_expired
say "...and $COUNT contact syncs arrive."
run curl -fsS -X POST "$APP/seed/naiveContacts?count=$COUNT"

banner "the retry storm"
prev=-1
drained() { # the queue is empty and the DLQ has stopped growing
  local q dlq settled=1
  q=$(depth naive.contact-sync); dlq=$(depth naive.contact-sync.dlq)
  say "queue $q · dead-lettered $dlq · error lines $(log_count 'naive.contact-sync FAILED' "$since")"
  [[ "$q" == 0 && "$dlq" -gt 0 && "$dlq" == "$prev" ]] || settled=0
  prev=$dlq
  (( settled ))
}
wait_until 180 "the naive queue to drain" drained
sleep 1

lost=$(depth naive.contact-sync.dlq)
errors=$(log_count 'naive.contact-sync FAILED' "$since")
calls=$(vendor_stat contacts failed)
say "vendor calls that failed: $calls (every message tried $((calls / (lost > 0 ? lost : 1))) times)"
say "every message is now in naive.contact-sync.dlq; getting them back is a manual job."

banner "the damage"
printf '  %slost: %s · error lines: ~%s · recovery: manual%s\n\n' "$BOLD" "$lost" "$errors" "$OFF"
assert_gt "$lost" 0 "messages dead-lettered"
assert_gt "$errors" 0 "error lines"
ok "scenario-naive"

-- Atomic compare-and-set on one status hash (tr:status:<scope>). Exactly one caller wins each
-- change, however many control-plane consumers race on the same scope, and only the winner
-- broadcasts and notifies.
--
-- KEYS[1] = status hash
-- ARGV[1] = 'stop' | 'reopen'
-- ARGV[2] = now (epoch ms, the caller's clock)
-- stop only:
--   ARGV[3] = stop reason (StopReason name)
--   ARGV[4] = detail text ('' for none)
--   ARGV[5] = when the triggering report was emitted (epoch ms)
--
-- Returns the previous status when this call changed it, nil otherwise.
--
-- A stored value that is neither GROUND_STOP nor CLOSED reads as OPEN, matching StatusReader.
-- 'stop' moves only OPEN -> GROUND_STOP: never re-stops (idempotent) and never downgrades a
-- CLOSED scope. It is also refused for a report emitted before the scope's last reopen
-- (field openedAt), so a backlog of pre-reopen fault reports cannot re-ground a scope that
-- an operator just reopened. 'reopen' moves any non-OPEN status to OPEN.

local key = KEYS[1]
local current = redis.call('HGET', key, 'status')
if current ~= 'GROUND_STOP' and current ~= 'CLOSED' then
  current = 'OPEN'
end

if ARGV[1] == 'reopen' then
  if current == 'OPEN' then
    return nil
  end
  redis.call('HSET', key, 'status', 'OPEN', 'since', ARGV[2], 'openedAt', ARGV[2])
  redis.call('HDEL', key, 'reason', 'detail')
  return current
end

if current ~= 'OPEN' then
  return nil
end
local openedAt = tonumber(redis.call('HGET', key, 'openedAt') or '0') or 0
if (tonumber(ARGV[5]) or 0) < openedAt then
  return nil
end
redis.call('HSET', key, 'status', 'GROUND_STOP', 'reason', ARGV[3], 'detail', ARGV[4],
  'since', ARGV[2])
return current

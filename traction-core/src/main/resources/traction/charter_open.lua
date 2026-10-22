-- Opens a charter: admission and the projection in one atomic step (CharterOpener, data plane).
--
-- KEYS[1] = charter hash      tr:charter:<ref>
-- KEYS[2] = open set          tr:charters-open
-- KEYS[3] = admission key     tr:charter-admission:<airportId>/<gateId>
-- KEYS[4] = airport index     tr:charters:<airportId>
-- KEYS[5] = abort marker      tr:charter:<ref>:aborted
-- ARGV[1] = charterRef
-- ARGV[2] = now (epoch ms)
-- ARGV[3] = airportId
-- ARGV[4] = gateId
-- ARGV[5] = charterId
-- ARGV[6] = expected
-- ARGV[7] = '1' under CharterConcurrency.PER_KEY, '0' otherwise
--
-- Returns 'OPENED' | 'EXISTS' (still open: an idempotent re-open) | 'CLOSED' (this ref already
-- ran and is still retained, or its abort marker lives) | 'DENIED:<holder ref>' (PER_KEY: the
-- gate's key is held by another open charter). The admission key has no TTL: the charter's
-- completion releases it (charter_tally.lua), so it is held exactly while the charter is open.

local status = redis.call('HGET', KEYS[1], 'status')
if status then
  if status == 'OPEN' then
    return 'EXISTS'
  end
  return 'CLOSED'
end
if redis.call('EXISTS', KEYS[5]) == 1 then
  return 'CLOSED'
end

if ARGV[7] == '1' then
  local holder = redis.call('GET', KEYS[3])
  if holder and holder ~= ARGV[1] then
    return 'DENIED:' .. holder
  end
  redis.call('SET', KEYS[3], ARGV[1])
end

redis.call('HSET', KEYS[1],
  'airportId', ARGV[3], 'gateId', ARGV[4], 'charterId', ARGV[5], 'expected', ARGV[6],
  'status', 'OPEN', 'openedAt', ARGV[2], 'lastProgressAt', ARGV[2],
  'landed', 0, 'rejected', 0, 'skipped', 0, 'parked', 0, 'discarded', 0)
redis.call('SADD', KEYS[2], ARGV[1])
redis.call('ZADD', KEYS[4], ARGV[2], ARGV[1])
return 'OPENED'

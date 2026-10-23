-- Charter accounting (CharterBoard, control plane). Every count change and the completion latch
-- happen inside one script, so a redelivered report, a duplicate row, a concurrent discard or
-- many tower consumers can never double count or complete twice.
--
-- KEYS[1] = charter hash   tr:charter:<ref>         counts + meta
-- KEYS[2] = seen hash      tr:charter:<ref>:seen    flightId -> the bucket it is counted in
-- KEYS[3] = open set       tr:charters-open
-- KEYS[4] = admission key  tr:charter-admission:<airportId>/<gateId>
-- ARGV[1] = op: opened | move | abort | complete | abandon
-- ARGV[2] = charterRef
-- ARGV[3] = now (epoch ms)
-- ARGV[4] = retention after completion, seconds (hash + seen TTL)
-- move:     ARGV[5] = flightId, ARGV[6] = from bucket ('' = not counted), ARGV[7] = to bucket ('' = un-count)
-- abort:    ARGV[5] = actor
-- complete: ARGV[5] = source (SWEEP)
--
-- Returns a bitmask: 1 = this call changed the charter, 2 = this call completed it; 0 = no-op;
-- -1 = no such charter (never opened, or its retention ran out: nothing is counted or created).
--
-- Buckets: landed, rejected, skipped, parked, discarded. Completion (the latch):
--   status OPEN and landed + rejected + skipped + parked + discarded >= expected
-- then status COMPLETED, completedAt, source LATCH; for an aborted charter (abortedAt set) status
-- ABORTED and source ABORT, so its completion carries books that already add up. The charter
-- leaves the open set, releases its admission key, and the hash and seen hash expire after the
-- retention. 'abort' only records abortedAt/abortedBy (the skip window's SKIPPED rows then reach
-- the latch); 'complete' is the sweep's forced close (source SWEEP, status ABORTED if aborted).
-- A completed charter keeps counting (late rows, replays) but never completes again.

local h, seen, openSet, admission = KEYS[1], KEYS[2], KEYS[3], KEYS[4]
local op, ref, now, ttl = ARGV[1], ARGV[2], ARGV[3], tonumber(ARGV[4])

if redis.call('EXISTS', h) == 0 then
  if op == 'abandon' then
    redis.call('SREM', openSet, ref)
    if redis.call('GET', admission) == ref then
      redis.call('DEL', admission)
    end
    return 1
  end
  return -1
end

local function aborted()
  return redis.call('HEXISTS', h, 'abortedAt') == 1
end

local function complete(source)
  local status = 'COMPLETED'
  if aborted() then
    status = 'ABORTED'
  end
  redis.call('HSET', h, 'status', status, 'completedAt', now, 'source', source)
  redis.call('SREM', openSet, ref)
  if redis.call('GET', admission) == ref then
    redis.call('DEL', admission)
  end
  redis.call('EXPIRE', h, ttl)
  redis.call('EXPIRE', seen, ttl)
  return 2
end

local function maybeComplete()
  if redis.call('HGET', h, 'status') ~= 'OPEN' then
    return 0
  end
  local expected = tonumber(redis.call('HGET', h, 'expected'))
  if not expected then
    return 0
  end
  local accounted = 0
  for _, bucket in ipairs({'landed', 'rejected', 'skipped', 'parked', 'discarded'}) do
    accounted = accounted + (tonumber(redis.call('HGET', h, bucket)) or 0)
  end
  if accounted >= expected then
    if aborted() then
      return complete('ABORT')
    end
    return complete('LATCH')
  end
  return 0
end

if op == 'opened' then
  -- The control plane applied the OPENED event: notify once; expected 0 completes here.
  return redis.call('HSETNX', h, 'openNotifiedAt', now) + maybeComplete()
end

if op == 'move' then
  local flight, from, to = ARGV[5], ARGV[6], ARGV[7]
  local current = redis.call('HGET', seen, flight) or ''
  if current ~= from then
    return 0
  end
  if to == '' then
    redis.call('HDEL', seen, flight)
  else
    redis.call('HSET', seen, flight, to)
  end
  if from ~= '' then
    redis.call('HINCRBY', h, from, -1)
  end
  if to ~= '' then
    redis.call('HINCRBY', h, to, 1)
  end
  redis.call('HSET', h, 'lastProgressAt', now)
  local remaining = redis.call('TTL', h)
  if remaining > 0 then
    redis.call('EXPIRE', seen, remaining) -- a seen hash created after completion expires with it
  end
  return 1 + maybeComplete()
end

if op == 'abort' then
  -- Records the abort once; completion stays with the latch (the skipped rows fill the books).
  local changed = 0
  if redis.call('HSETNX', h, 'abortedAt', now) == 1 then
    redis.call('HSET', h, 'abortedBy', ARGV[5])
    changed = 1
  end
  return changed + maybeComplete()
end

if op == 'complete' then
  if redis.call('HGET', h, 'status') ~= 'OPEN' then
    return 0
  end
  return 1 + complete(ARGV[5])
end

return 0 -- abandon of a charter whose hash still exists

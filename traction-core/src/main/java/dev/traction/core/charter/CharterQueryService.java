package dev.traction.core.charter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Charter reads for the admin API and dashboard (control plane): an airport's charters, open
 * and recently completed, newest first, from the airport index {@code tr:charters:<airportId>}.
 * An index entry whose charter hash has expired (retention over) is pruned as it is read.
 */
public class CharterQueryService {

  private final StringRedisTemplate redis;
  private final CharterBoard board;

  public CharterQueryService(StringRedisTemplate redis, CharterBoard board) {
    this.redis = redis;
    this.board = board;
  }

  /** The airport's retained charters, newest opened first; empty for an unknown airport. */
  public List<CharterBoard.CharterSnapshot> list(String airportId) {
    String index = CharterKeys.airportIndexKey(airportId);
    Set<String> refs = redis.opsForZSet().reverseRange(index, 0, -1);
    if (refs == null || refs.isEmpty()) {
      return List.of();
    }
    List<CharterBoard.CharterSnapshot> charters = new ArrayList<>(refs.size());
    for (String ref : refs) {
      Optional<CharterBoard.CharterSnapshot> snapshot = board.snapshot(ref);
      if (snapshot.isPresent()) {
        charters.add(snapshot.get());
      } else {
        redis.opsForZSet().remove(index, ref);
      }
    }
    return charters;
  }
}

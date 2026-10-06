/*
 *
 *  Copyright [ 2026 ] Ralf Himmelein and Claude
 *
 *  Licensed under the Apache License, Version 2.0 with the Commons Clause
 *  (the "License"); you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *      https://commonsclause.com/
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.mapsmessaging.state.drone.core;

import io.mapsmessaging.state.drone.model.GeoPosition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PositionOutputRegistryTest {

  private final GeoPosition truePosition = new GeoPosition(38.4, -9.1, 125.0, null, null);

  @AfterEach
  void clearFilter() {
    PositionOutputRegistry.setFilter(null);
  }

  @Test
  void apply_withNoFilter_returnsSamePosition() {
    assertSame(truePosition, PositionOutputRegistry.apply("UAS-1", truePosition));
  }

  @Test
  void apply_withFilter_returnsFilteredPosition() {
    GeoPosition moved = new GeoPosition(38.5, -9.2, 125.0, null, null);
    PositionOutputRegistry.setFilter((twinId, position) -> "UAS-1".equals(twinId) ? moved : null);

    assertSame(moved, PositionOutputRegistry.apply("UAS-1", truePosition));
    assertSame(truePosition, PositionOutputRegistry.apply("UAS-2", truePosition));
  }

  @Test
  void apply_whenFilterThrows_returnsSamePosition() {
    PositionOutputRegistry.setFilter((twinId, position) -> {
      throw new IllegalStateException("broken filter");
    });

    assertSame(truePosition, PositionOutputRegistry.apply("UAS-1", truePosition));
  }

  @Test
  void apply_withNullTwinIdOrPosition_neverCallsFilter() {
    PositionOutputRegistry.setFilter((twinId, position) -> fail("filter must not be called"));

    assertSame(truePosition, PositionOutputRegistry.apply(null, truePosition));
    assertNull(PositionOutputRegistry.apply("UAS-1", null));
  }
}

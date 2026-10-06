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

package io.mapsmessaging.state.drone.tak;

import io.mapsmessaging.state.drone.core.PositionOutputRegistry;
import io.mapsmessaging.state.drone.core.TwinLifecycleStatus;
import io.mapsmessaging.state.drone.drone.DroneTwin;
import io.mapsmessaging.state.drone.model.GeoPosition;
import io.mapsmessaging.state.drone.tak.model.TakEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class TakEventMapperPositionOutputTest {

  @AfterEach
  void clearFilter() {
    PositionOutputRegistry.setFilter(null);
  }

  @Test
  void map_withFilter_reportsFilteredPointAndKeepsTwinPosition() {
    DroneTwin twin = positionedTwin();
    PositionOutputRegistry.setFilter((twinId, position) -> new GeoPosition(38.5, -9.2, 130.0, null, null));

    TakEvent event = new TakEventMapper().map(twin, null);

    assertEquals(38.5, event.getPoint().getLat(), 0.0);
    assertEquals(-9.2, event.getPoint().getLon(), 0.0);
    assertEquals(130.0, event.getPoint().getHae(), 0.0);
    assertEquals(38.4, twin.getGeoPosition().getLatitude(), 0.0);
    assertEquals(-9.1, twin.getGeoPosition().getLongitude(), 0.0);
  }

  @Test
  void map_afterFilterCleared_reportsTwinPositionAgain() {
    DroneTwin twin = positionedTwin();
    PositionOutputRegistry.setFilter((twinId, position) -> new GeoPosition(38.5, -9.2, 130.0, null, null));
    new TakEventMapper().map(twin, null);
    PositionOutputRegistry.setFilter(null);

    TakEvent event = new TakEventMapper().map(twin, null);

    assertEquals(38.4, event.getPoint().getLat(), 0.0);
    assertEquals(-9.1, event.getPoint().getLon(), 0.0);
  }

  @Test
  void applyRemoval_withFilter_usesFilteredPoint() {
    DroneTwin twin = positionedTwin();
    PositionOutputRegistry.setFilter((twinId, position) -> new GeoPosition(38.5, -9.2, 130.0, null, null));
    TakEvent removal = new TakEventMapper().mapRemoval(twin, null);

    new CotEventPolicy().applyRemoval(removal, twin, null, null);

    assertEquals(38.5, removal.getPoint().getLat(), 0.0);
    assertEquals(-9.2, removal.getPoint().getLon(), 0.0);
  }

  private static DroneTwin positionedTwin() {
    DroneTwin twin = new DroneTwin("UAS-1");
    twin.setGeoPosition(new GeoPosition(38.4, -9.1, 125.0, null, null));
    twin.setLifecycleStatus(TwinLifecycleStatus.ACTIVE);
    twin.setLastSeenAt(Instant.parse("2026-10-06T12:00:00Z"));
    return twin;
  }
}

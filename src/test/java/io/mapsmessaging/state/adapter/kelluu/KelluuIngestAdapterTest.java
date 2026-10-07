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

package io.mapsmessaging.state.adapter.kelluu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.mapsmessaging.state.drone.core.EntityTwin;
import io.mapsmessaging.state.drone.core.TwinManager;
import io.mapsmessaging.state.drone.core.TwinObserver;
import io.mapsmessaging.state.drone.core.TwinUpdateContext;
import io.mapsmessaging.state.drone.drone.DroneTwin;
import io.mapsmessaging.state.drone.tak.CotToTwinMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KelluuIngestAdapterTest {

  // Real message from the Kelluu live feed (2026-10-07).
  private static final String POSITION = """
      {
        "messageType": "position",
        "source": { "system": "kelluu", "platformId": "asu16-Raptor", "feed": "asu16-Raptor" },
        "time": "2026-10-07T12:40:11.336Z",
        "position": {
          "latitude": 44.65620067187408,
          "longitude": -63.55662027539528,
          "altitudeMetres": 118.45731288624404,
          "altitudeReference": "MSL",
          "datum": "WGS84"
        },
        "headingDegreesTrue": 206.9251544975967,
        "courseDegreesTrue": 206.9,
        "standard": "MISB ST 0601"
      }
      """;

  private static final String TARGET = """
      {
        "messageType": "target",
        "source": { "system": "kelluu", "platformId": "asu16-Raptor", "feed": "asu16-Raptor" },
        "time": "2026-10-07T09:56:40.000Z",
        "target": {
          "id": "asu16-Raptor:1",
          "type": "ship",
          "classification": "Unknown",
          "position": { "latitude": 44.65309999634191, "longitude": -63.55079996564928, "datum": "WGS84" },
          "confidence": 80
        },
        "standard": "MISB ST 0903 VMTI"
      }
      """;

  @Test
  void position_registersFriendlyCivilianAirshipTwin() {
    TwinManager twinManager = new TwinManager();
    KelluuIngestAdapter adapter = new KelluuIngestAdapter(KelluuIngestAdapterFactory.DEFAULT_TOPIC, twinManager);

    assertTrue(adapter.handle("/feed/kelluu/positions", bytes(POSITION)));

    DroneTwin twin = (DroneTwin) twinManager.getTwin("asu16-Raptor").orElseThrow();
    assertEquals("a-f-A-C-L", twin.getAttributes().get(CotToTwinMapper.ORIGINAL_COT_TYPE_ATTRIBUTE));
    assertEquals("asu16-Raptor", twin.getCallSign());
    assertEquals(44.65620067187408, twin.getGeoPosition().getLatitude(), 1e-12);
    assertEquals(-63.55662027539528, twin.getGeoPosition().getLongitude(), 1e-12);
    assertEquals(118.45731288624404, twin.getGeoPosition().getAltitudeMslMeters(), 1e-9);
    assertEquals(206.9251544975967, twin.getHeadingDegrees(), 1e-9);
    assertEquals(206.9, twin.getCourseOverGroundDegrees(), 1e-9);
    assertEquals(1, adapter.getRoutedCount());
  }

  @Test
  void position_secondMessage_updatesSameTwinWithKelluuSource() {
    TwinManager twinManager = new TwinManager();
    TwinObserver observer = mock(TwinObserver.class);
    twinManager.addObserver(observer);
    KelluuIngestAdapter adapter = new KelluuIngestAdapter(KelluuIngestAdapterFactory.DEFAULT_TOPIC, twinManager);

    adapter.handle("/feed/kelluu/positions", bytes(POSITION));
    adapter.handle("/feed/kelluu/positions", bytes(POSITION.replace("44.65620067187408", "44.7")));

    assertEquals(1, twinManager.getTwinCount());
    assertEquals(44.7, twinManager.getTwin("asu16-Raptor").orElseThrow().getGeoPosition().getLatitude(), 1e-12);
    ArgumentCaptor<TwinUpdateContext> context = ArgumentCaptor.forClass(TwinUpdateContext.class);
    verify(observer).onTwinUpdated(any(), any(EntityTwin.class), context.capture());
    assertEquals("kelluu-ingest", context.getValue().getUpdateSource());
    assertEquals("asu16-Raptor", context.getValue().getSourceInstanceId());
    assertEquals("/feed/kelluu/positions", context.getValue().getSourceNamespace());
  }

  @Test
  void target_registersUnknownSeaSurfaceTrackPerTargetId() {
    TwinManager twinManager = new TwinManager();
    KelluuIngestAdapter adapter = new KelluuIngestAdapter(KelluuIngestAdapterFactory.DEFAULT_TOPIC, twinManager);

    assertTrue(adapter.handle("/feed/kelluu/targets", bytes(TARGET)));

    DroneTwin twin = (DroneTwin) twinManager.getTwin("asu16-Raptor:1").orElseThrow();
    assertEquals("a-u-S", twin.getAttributes().get(CotToTwinMapper.ORIGINAL_COT_TYPE_ATTRIBUTE));
    assertEquals("asu16-Raptor:1 (ship)", twin.getCallSign());
    assertEquals("80", twin.getAttributes().get(KelluuMessageMapper.CONFIDENCE_ATTRIBUTE));
    assertEquals("asu16-Raptor", twin.getAttributes().get(KelluuMessageMapper.PLATFORM_ATTRIBUTE));
    assertNull(twin.getGeoPosition().getAltitudeMslMeters());
  }

  @Test
  void malformedOrUnknownMessages_areDroppedWithoutTwins() {
    TwinManager twinManager = new TwinManager();
    KelluuIngestAdapter adapter = new KelluuIngestAdapter(KelluuIngestAdapterFactory.DEFAULT_TOPIC, twinManager);

    assertFalse(adapter.handle("/feed/kelluu/positions", bytes("not json")));
    assertFalse(adapter.handle("/feed/kelluu/positions", bytes("[]")));
    assertFalse(adapter.handle("/feed/kelluu/positions", new byte[0]));
    assertFalse(adapter.handle("/feed/kelluu/positions", null));
    assertFalse(adapter.handle("/feed/kelluu/status", bytes("{\"messageType\":\"status\"}")));
    assertFalse(adapter.handle("/feed/kelluu/positions", bytes(POSITION.replace("\"platformId\": \"asu16-Raptor\",", ""))));
    assertFalse(adapter.handle("/feed/kelluu/positions", bytes(POSITION.replace("44.65620067187408", "91.0"))));
    assertFalse(adapter.handle("/feed/kelluu/positions", bytes(POSITION.replace("44.65620067187408", "\"44.6\""))));
    assertFalse(adapter.handle("/feed/kelluu/targets", bytes(TARGET.replace("\"id\": \"asu16-Raptor:1\",", ""))));

    assertEquals(0, twinManager.getTwinCount());
    assertEquals(9, adapter.getDroppedCount());
  }

  @Test
  void position_nonMslAltitude_isNotStoredAsMsl() {
    TwinManager twinManager = new TwinManager();
    KelluuIngestAdapter adapter = new KelluuIngestAdapter(KelluuIngestAdapterFactory.DEFAULT_TOPIC, twinManager);

    adapter.handle("/feed/kelluu/positions", bytes(POSITION.replace("\"MSL\"", "\"HAE\"")));

    assertNull(twinManager.getTwin("asu16-Raptor").orElseThrow().getGeoPosition().getAltitudeMslMeters());
  }

  @Test
  void targetCotType_mapsClassificationAndType() {
    assertEquals("a-u-S", KelluuMessageMapper.targetCotType("ship", "Unknown"));
    assertEquals("a-h-S", KelluuMessageMapper.targetCotType("Ship", "Hostile"));
    assertEquals("a-f-A", KelluuMessageMapper.targetCotType("aircraft", "Friendly"));
    assertEquals("a-n-G", KelluuMessageMapper.targetCotType("vehicle", "neutral"));
    assertEquals("a-u-U", KelluuMessageMapper.targetCotType("submarine", null));
    assertEquals("a-u-X", KelluuMessageMapper.targetCotType(null, null));
    assertEquals("a-u-X", KelluuMessageMapper.targetCotType("whale", "Unknown"));
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }
}

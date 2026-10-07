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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.mapsmessaging.state.drone.drone.DroneTwin;
import io.mapsmessaging.state.drone.model.GeoPosition;
import io.mapsmessaging.state.drone.tak.CotToTwinMapper;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * Maps the Kelluu JSON feed to twins.
 *
 * <ul>
 *   <li>{@code messageType: position} (MISB ST 0601) - the airship itself, twin id
 *       {@code source.platformId}, CoT type {@value #AIRSHIP_COT_TYPE} (friendly civilian lighter
 *       than air).</li>
 *   <li>{@code messageType: target} (MISB ST 0903 VMTI) - one twin per detected target, twin id
 *       {@code target.id}, CoT type from {@code target.type} and {@code target.classification}.</li>
 * </ul>
 *
 * <p>The CoT type is stored as the {@code originalCotType} attribute, which the TAK output, the KPI
 * classification and the STANAG node description already use for CoT-ingested twins.
 */
public class KelluuMessageMapper {

  static final String AIRSHIP_COT_TYPE = "a-f-A-C-L";
  static final String PLATFORM_ATTRIBUTE = "kelluuPlatformId";
  static final String CONFIDENCE_ATTRIBUTE = "kelluuConfidence";
  static final String TARGET_TYPE_ATTRIBUTE = "kelluuTargetType";

  /** The twin for one Kelluu message, or empty when it is not a usable position or target. */
  public Optional<DroneTwin> map(byte[] payload) {
    JsonObject root = parseObject(payload);
    if (root == null) {
      return Optional.empty();
    }
    String messageType = text(root, "messageType");
    if ("position".equalsIgnoreCase(messageType)) {
      return mapPosition(root);
    }
    if ("target".equalsIgnoreCase(messageType)) {
      return mapTarget(root);
    }
    return Optional.empty();
  }

  private Optional<DroneTwin> mapPosition(JsonObject root) {
    String platformId = text(object(root, "source"), "platformId");
    GeoPosition geoPosition = geoPosition(object(root, "position"));
    if (platformId == null || geoPosition == null) {
      return Optional.empty();
    }
    DroneTwin twin = new DroneTwin(platformId);
    twin.setCallSign(platformId);
    twin.setDisplayName(platformId);
    twin.setGeoPosition(geoPosition);
    twin.setHeadingDegrees(number(root, "headingDegreesTrue"));
    twin.setCourseOverGroundDegrees(number(root, "courseDegreesTrue"));
    twin.getAttributes().put(CotToTwinMapper.ORIGINAL_COT_TYPE_ATTRIBUTE, AIRSHIP_COT_TYPE);
    twin.getAttributes().put(PLATFORM_ATTRIBUTE, platformId);
    return Optional.of(twin);
  }

  private Optional<DroneTwin> mapTarget(JsonObject root) {
    JsonObject target = object(root, "target");
    String targetId = text(target, "id");
    GeoPosition geoPosition = geoPosition(object(target, "position"));
    if (targetId == null || geoPosition == null) {
      return Optional.empty();
    }
    String targetType = text(target, "type");
    DroneTwin twin = new DroneTwin(targetId);
    String label = targetType == null ? targetId : targetId + " (" + targetType + ")";
    twin.setCallSign(label);
    twin.setDisplayName(label);
    twin.setGeoPosition(geoPosition);
    twin.getAttributes().put(
        CotToTwinMapper.ORIGINAL_COT_TYPE_ATTRIBUTE,
        targetCotType(targetType, text(target, "classification")));
    String platformId = text(object(root, "source"), "platformId");
    if (platformId != null) {
      twin.getAttributes().put(PLATFORM_ATTRIBUTE, platformId);
    }
    if (targetType != null) {
      twin.getAttributes().put(TARGET_TYPE_ATTRIBUTE, targetType);
    }
    Double confidence = number(target, "confidence");
    if (confidence != null) {
      twin.getAttributes().put(CONFIDENCE_ATTRIBUTE, Long.toString(Math.round(confidence)));
    }
    return Optional.of(twin);
  }

  /** {@code a-<affiliation>-<dimension>}: classification gives the affiliation, type the dimension. */
  static String targetCotType(String targetType, String classification) {
    return "a-" + affiliation(classification) + "-" + dimension(targetType);
  }

  private static String affiliation(String classification) {
    if (classification == null) {
      return "u";
    }
    return switch (classification.trim().toLowerCase(Locale.ROOT)) {
      case "friend", "friendly" -> "f";
      case "assumed friend", "assumed friendly" -> "a";
      case "neutral" -> "n";
      case "suspect", "suspicious" -> "s";
      case "hostile", "enemy" -> "h";
      default -> "u";
    };
  }

  private static String dimension(String targetType) {
    if (targetType == null) {
      return "X";
    }
    return switch (targetType.trim().toLowerCase(Locale.ROOT)) {
      case "ship", "boat", "vessel" -> "S";
      case "submarine" -> "U";
      case "aircraft", "plane", "airplane", "helicopter", "drone", "uav" -> "A";
      case "vehicle", "car", "truck", "person", "people" -> "G";
      default -> "X";
    };
  }

  private static GeoPosition geoPosition(JsonObject position) {
    Double latitude = number(position, "latitude");
    Double longitude = number(position, "longitude");
    if (latitude == null || longitude == null
        || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
      return null;
    }
    GeoPosition geoPosition = new GeoPosition();
    geoPosition.setLatitude(latitude);
    geoPosition.setLongitude(longitude);
    String reference = text(position, "altitudeReference");
    if (reference == null || "MSL".equalsIgnoreCase(reference)) {
      geoPosition.setAltitudeMslMeters(number(position, "altitudeMetres"));
    }
    return geoPosition;
  }

  private static JsonObject parseObject(byte[] payload) {
    if (payload == null || payload.length == 0) {
      return null;
    }
    try {
      JsonElement element = JsonParser.parseString(new String(payload, StandardCharsets.UTF_8));
      return element.isJsonObject() ? element.getAsJsonObject() : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static JsonObject object(JsonObject parent, String name) {
    if (parent == null) {
      return null;
    }
    JsonElement element = parent.get(name);
    return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
  }

  private static String text(JsonObject parent, String name) {
    if (parent == null) {
      return null;
    }
    JsonElement element = parent.get(name);
    if (element == null || !element.isJsonPrimitive()) {
      return null;
    }
    String value = element.getAsString().trim();
    return value.isEmpty() ? null : value;
  }

  private static Double number(JsonObject parent, String name) {
    if (parent == null) {
      return null;
    }
    JsonElement element = parent.get(name);
    if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
      return null;
    }
    double value = element.getAsDouble();
    return Double.isFinite(value) ? value : null;
  }
}

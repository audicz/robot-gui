// MAROS Mobile Cockpit UDP bridge for the existing VESC ESP32-S3 display.
// The display remains the ESP-NOW endpoint; this code only forwards validated
// ESP-NOW datagrams to one recently registered phone on the local Wi-Fi LAN.
#pragma once

#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include "lwip/sockets.h"
#include "lwip/inet.h"
#include "lwip/errno.h"

namespace cockpit { namespace mobile {

static constexpr uint16_t UDP_PORT = 4210;
static constexpr uint8_t VERSION = 1;
static constexpr uint8_t MSG_HELLO = 0x01;
static constexpr uint8_t MSG_STOP = 0x11;
static constexpr uint8_t MSG_BRIDGE_CMD = 0x20;
static constexpr uint8_t MSG_FRAME = 0x80;
static constexpr uint8_t MSG_HEARTBEAT = 0x81;

static int udpFd = -1;
static sockaddr_in phoneAddr{};
static bool phoneKnown = false;
static uint32_t phoneLastSeenMs = 0;
static uint16_t txSequence = 0;
static bool stopPending = false;
static bool bridgeCmdPending = false;
static uint8_t bridgeCmd = 0;

inline void put_u16(uint8_t *p, uint16_t v) {
  p[0] = (uint8_t)(v & 0xffU);
  p[1] = (uint8_t)(v >> 8U);
}

inline uint16_t get_u16(const uint8_t *p) {
  return (uint16_t)p[0] | ((uint16_t)p[1] << 8U);
}

inline void send_message(uint8_t type, const uint8_t *payload, uint16_t length) {
  if (udpFd < 0 || !phoneKnown || length > 250U) return;
  uint8_t packet[258];
  packet[0] = 'M'; packet[1] = '7'; packet[2] = VERSION; packet[3] = type;
  put_u16(packet + 4, length);
  put_u16(packet + 6, ++txSequence);
  if (length && payload) memcpy(packet + 8, payload, length);
  (void)sendto(udpFd, packet, (size_t)length + 8U, 0,
               (const sockaddr *)&phoneAddr, sizeof(phoneAddr));
}

inline bool begin() {
  if (udpFd >= 0) return true;
  const int fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
  if (fd < 0) return false;
  int reuse = 1;
  (void)setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
  sockaddr_in local{};
  local.sin_family = AF_INET;
  local.sin_port = htons(UDP_PORT);
  local.sin_addr.s_addr = htonl(INADDR_ANY);
  if (bind(fd, (const sockaddr *)&local, sizeof(local)) < 0) {
    close(fd);
    return false;
  }
  const int flags = fcntl(fd, F_GETFL, 0);
  if (flags >= 0) (void)fcntl(fd, F_SETFL, flags | O_NONBLOCK);
  udpFd = fd;
  return true;
}

// Call from an ESPHome interval (20 ms). Commands are deliberately narrow:
// STOP and the existing non-motion LiDAR bridge calibration/rotation command.
inline void tick(uint32_t now) {
  if (udpFd < 0 && !begin()) return;
  uint8_t packet[64];
  for (uint8_t n = 0; n < 4; ++n) {
    sockaddr_in from{};
    socklen_t fromLen = sizeof(from);
    const int got = recvfrom(udpFd, packet, sizeof(packet), 0,
                             (sockaddr *)&from, &fromLen);
    if (got < 0) {
      if (errno == EWOULDBLOCK || errno == EAGAIN) break;
      break;
    }
    if (got < 8 || packet[0] != 'M' || packet[1] != '7' ||
        packet[2] != VERSION || get_u16(packet + 4) != (uint16_t)(got - 8)) continue;
    const uint8_t type = packet[3];
    const uint16_t length = get_u16(packet + 4);
    if (type == MSG_HELLO && length == 0U) {
      phoneAddr = from;
      phoneKnown = true;
      phoneLastSeenMs = now;
      uint8_t uptime[4] = {
        (uint8_t)(now & 0xffU), (uint8_t)((now >> 8U) & 0xffU),
        (uint8_t)((now >> 16U) & 0xffU), (uint8_t)((now >> 24U) & 0xffU)
      };
      send_message(MSG_HEARTBEAT, uptime, sizeof(uptime));
    } else if (type == MSG_STOP && length == 0U) {
      phoneAddr = from;
      phoneKnown = true;
      phoneLastSeenMs = now;
      stopPending = true;
    } else if (type == MSG_BRIDGE_CMD && length == 1U) {
      phoneAddr = from;
      phoneKnown = true;
      phoneLastSeenMs = now;
      bridgeCmd = packet[8];
      bridgeCmdPending = true;
    }
  }
  if (phoneKnown && (uint32_t)(now - phoneLastSeenMs) > 5000U) phoneKnown = false;
}

inline void forward_frame(const uint8_t *data, size_t size, uint32_t now) {
  (void)now;
  if (!data || size == 0U || size > 250U) return;
  send_message(MSG_FRAME, data, (uint16_t)size);
}

inline bool take_stop() {
  const bool value = stopPending;
  stopPending = false;
  return value;
}

inline bool take_bridge_cmd(uint8_t &cmd) {
  if (!bridgeCmdPending) return false;
  cmd = bridgeCmd;
  bridgeCmdPending = false;
  return true;
}

}}  // namespace cockpit::mobile

#include <jni.h>
#include <android/log.h>

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>

#include <array>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <thread>
#include <vector>

namespace {

constexpr const char* kTag = "MoonlightNat";
constexpr int kMaxPacket = 65535;
constexpr int kMtu = 1500;
constexpr std::array<uint16_t, 3> kSunshineUdpPorts = {47998, 47999, 48000};

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, kTag, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, kTag, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, kTag, __VA_ARGS__)

struct UdpSlot {
    uint16_t virtualPort = 0;
    int socketFd = -1;
    sockaddr_in realAddr{};
    bool hasMapping = false;
    uint32_t clientIp = 0;
    uint16_t clientPort = 0;
    bool hasClient = false;
};

struct State {
    JavaVM* vm = nullptr;
    jobject vpnService = nullptr;
    jmethodID protectMethod = nullptr;

    int tunFd = -1;
    int hevFd = -1;
    int dispatcherFd = -1;
    uint32_t virtualIp = 0;
    uint32_t vpnIp = 0;
    uint16_t ipId = 1;

    std::atomic<bool> running{false};
    std::thread worker;
    std::mutex mutex;
    std::array<UdpSlot, 3> slots{};
};

State gState;

uint16_t read16(const uint8_t* data) {
    return static_cast<uint16_t>((data[0] << 8) | data[1]);
}

uint32_t read32(const uint8_t* data) {
    return (static_cast<uint32_t>(data[0]) << 24) |
           (static_cast<uint32_t>(data[1]) << 16) |
           (static_cast<uint32_t>(data[2]) << 8) |
           static_cast<uint32_t>(data[3]);
}

void write16(uint8_t* data, uint16_t value) {
    data[0] = static_cast<uint8_t>((value >> 8) & 0xff);
    data[1] = static_cast<uint8_t>(value & 0xff);
}

void write32(uint8_t* data, uint32_t value) {
    data[0] = static_cast<uint8_t>((value >> 24) & 0xff);
    data[1] = static_cast<uint8_t>((value >> 16) & 0xff);
    data[2] = static_cast<uint8_t>((value >> 8) & 0xff);
    data[3] = static_cast<uint8_t>(value & 0xff);
}

uint32_t checksumAdd(uint32_t sum, const uint8_t* data, size_t len) {
    while (len >= 2) {
        sum += read16(data);
        data += 2;
        len -= 2;
    }
    if (len == 1) {
        sum += static_cast<uint16_t>(data[0] << 8);
    }
    return sum;
}

uint16_t checksumFinish(uint32_t sum) {
    while (sum >> 16) {
        sum = (sum & 0xffffu) + (sum >> 16);
    }
    return static_cast<uint16_t>(~sum);
}

uint16_t ipv4Checksum(const uint8_t* header, size_t headerLen) {
    return checksumFinish(checksumAdd(0, header, headerLen));
}

uint16_t udpChecksum(const uint8_t* packet, size_t ipHeaderLen, size_t udpLen) {
    uint32_t sum = 0;
    sum = checksumAdd(sum, packet + 12, 8);
    sum += IPPROTO_UDP;
    sum += static_cast<uint16_t>(udpLen);
    sum = checksumAdd(sum, packet + ipHeaderLen, udpLen);
    auto result = checksumFinish(sum);
    return result == 0 ? 0xffff : result;
}

bool setNonBlocking(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0) return false;
    return fcntl(fd, F_SETFL, flags | O_NONBLOCK) == 0;
}

bool parseIpv4(const char* text, uint32_t* outNetworkOrder) {
    in_addr addr{};
    if (inet_pton(AF_INET, text, &addr) != 1) return false;
    *outNetworkOrder = addr.s_addr;
    return true;
}

bool protectSocket(JNIEnv* env, int fd) {
    if (!gState.vpnService || !gState.protectMethod) return false;
    jboolean ok = env->CallBooleanMethod(gState.vpnService, gState.protectMethod, fd);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return ok == JNI_TRUE;
}

UdpSlot* slotForPort(uint16_t port) {
    for (auto& slot : gState.slots) {
        if (slot.virtualPort == port) return &slot;
    }
    return nullptr;
}

bool writePacket(int fd, const uint8_t* data, size_t len) {
    ssize_t written = TEMP_FAILURE_RETRY(write(fd, data, len));
    if (written < 0) {
        return errno == EAGAIN || errno == EWOULDBLOCK;
    }
    return static_cast<size_t>(written) == len;
}

bool sendToHev(const uint8_t* data, size_t len) {
    int fd;
    {
        std::lock_guard<std::mutex> guard(gState.mutex);
        fd = gState.dispatcherFd;
    }
    if (fd < 0) return false;
    ssize_t sent = TEMP_FAILURE_RETRY(send(fd, data, len, MSG_NOSIGNAL));
    if (sent < 0) {
        return errno == EAGAIN || errno == EWOULDBLOCK;
    }
    return static_cast<size_t>(sent) == len;
}

bool sendToTun(const uint8_t* data, size_t len) {
    int fd;
    {
        std::lock_guard<std::mutex> guard(gState.mutex);
        fd = gState.tunFd;
    }
    if (fd < 0) return false;
    return writePacket(fd, data, len);
}

bool isFragmented(const uint8_t* packet) {
    uint16_t flagsOffset = read16(packet + 6);
    return (flagsOffset & 0x3fffu) != 0 || (flagsOffset & 0x2000u) != 0;
}

bool handleOutboundUdp(const uint8_t* packet, size_t len) {
    if (len < 28) return false;
    uint8_t version = packet[0] >> 4;
    if (version != 4) return false;
    size_t ipHeaderLen = (packet[0] & 0x0f) * 4;
    if (ipHeaderLen < 20 || len < ipHeaderLen + 8) return false;
    uint16_t totalLen = read16(packet + 2);
    if (totalLen > len || totalLen < ipHeaderLen + 8) return false;
    if (packet[9] != IPPROTO_UDP) return false;
    if (isFragmented(packet)) return false;

    uint32_t dstIp = 0;
    std::memcpy(&dstIp, packet + 16, sizeof(dstIp));
    if (dstIp != gState.virtualIp) return false;

    const uint8_t* udp = packet + ipHeaderLen;
    uint16_t srcPort = read16(udp);
    uint16_t dstPort = read16(udp + 2);
    uint16_t udpLen = read16(udp + 4);
    if (udpLen < 8 || ipHeaderLen + udpLen > totalLen) return false;

    UdpSlot snapshot{};
    {
        std::lock_guard<std::mutex> guard(gState.mutex);
        UdpSlot* slot = slotForPort(dstPort);
        if (!slot || !slot->hasMapping || slot->socketFd < 0) {
            return false;
        }
        std::memcpy(&slot->clientIp, packet + 12, sizeof(slot->clientIp));
        slot->clientPort = srcPort;
        slot->hasClient = true;
        snapshot = *slot;
    }

    const uint8_t* payload = udp + 8;
    size_t payloadLen = udpLen - 8;
    ssize_t sent = TEMP_FAILURE_RETRY(send(snapshot.socketFd, payload, payloadLen, MSG_NOSIGNAL));
    if (sent < 0 && errno != EAGAIN && errno != EWOULDBLOCK) {
        LOGW("direct UDP send failed for %u: %s", dstPort, strerror(errno));
    }
    return true;
}

void emitInboundUdp(uint16_t virtualPort, int socketFd) {
    std::array<uint8_t, kMaxPacket> payload{};
    for (;;) {
        ssize_t received = TEMP_FAILURE_RETRY(recv(socketFd, payload.data(), payload.size(), 0));
        if (received < 0) {
            if (errno != EAGAIN && errno != EWOULDBLOCK) {
                LOGW("direct UDP recv failed for %u: %s", virtualPort, strerror(errno));
            }
            return;
        }
        if (received == 0) return;

        uint32_t clientIp = 0;
        uint16_t clientPort = 0;
        {
            std::lock_guard<std::mutex> guard(gState.mutex);
            UdpSlot* current = slotForPort(virtualPort);
            if (!current || !current->hasClient) return;
            clientIp = current->clientIp;
            clientPort = current->clientPort;
        }

        size_t ipHeaderLen = 20;
        size_t udpLen = 8 + static_cast<size_t>(received);
        size_t totalLen = ipHeaderLen + udpLen;
        if (totalLen > kMtu) {
            LOGW("dropping oversized UDP packet %zu for %u", totalLen, virtualPort);
            continue;
        }

        std::array<uint8_t, kMtu> packet{};
        packet[0] = 0x45;
        packet[1] = 0;
        write16(packet.data() + 2, static_cast<uint16_t>(totalLen));
        write16(packet.data() + 4, gState.ipId++);
        write16(packet.data() + 6, 0);
        packet[8] = 64;
        packet[9] = IPPROTO_UDP;
        write16(packet.data() + 10, 0);
        std::memcpy(packet.data() + 12, &gState.virtualIp, sizeof(gState.virtualIp));
        std::memcpy(packet.data() + 16, &clientIp, sizeof(clientIp));
        write16(packet.data() + 10, ipv4Checksum(packet.data(), ipHeaderLen));

        uint8_t* udp = packet.data() + ipHeaderLen;
        write16(udp, virtualPort);
        write16(udp + 2, clientPort);
        write16(udp + 4, static_cast<uint16_t>(udpLen));
        write16(udp + 6, 0);
        std::memcpy(udp + 8, payload.data(), static_cast<size_t>(received));
        write16(udp + 6, udpChecksum(packet.data(), ipHeaderLen, udpLen));

        sendToTun(packet.data(), totalLen);
    }
}

void readTunPackets() {
    std::array<uint8_t, kMaxPacket> packet{};
    for (;;) {
        int fd;
        {
            std::lock_guard<std::mutex> guard(gState.mutex);
            fd = gState.tunFd;
        }
        if (fd < 0) return;

        ssize_t readLen = TEMP_FAILURE_RETRY(read(fd, packet.data(), packet.size()));
        if (readLen < 0) {
            if (errno != EAGAIN && errno != EWOULDBLOCK) {
                LOGW("TUN read failed: %s", strerror(errno));
            }
            return;
        }
        if (readLen == 0) return;

        if (!handleOutboundUdp(packet.data(), static_cast<size_t>(readLen))) {
            sendToHev(packet.data(), static_cast<size_t>(readLen));
        }
    }
}

void readHevPackets() {
    std::array<uint8_t, kMaxPacket> packet{};
    for (;;) {
        int fd;
        {
            std::lock_guard<std::mutex> guard(gState.mutex);
            fd = gState.dispatcherFd;
        }
        if (fd < 0) return;

        ssize_t readLen = TEMP_FAILURE_RETRY(recv(fd, packet.data(), packet.size(), 0));
        if (readLen < 0) {
            if (errno != EAGAIN && errno != EWOULDBLOCK) {
                LOGW("fake TUN read failed: %s", strerror(errno));
            }
            return;
        }
        if (readLen == 0) return;
        sendToTun(packet.data(), static_cast<size_t>(readLen));
    }
}

void dispatcherLoop() {
    LOGI("TUN UDP NAT dispatcher started");
    while (gState.running.load()) {
        std::vector<pollfd> fds;
        fds.reserve(5);

        int tunFd = -1;
        int dispatcherFd = -1;
        std::array<int, 3> udpFds = {-1, -1, -1};
        {
            std::lock_guard<std::mutex> guard(gState.mutex);
            tunFd = gState.tunFd;
            dispatcherFd = gState.dispatcherFd;
            for (size_t i = 0; i < gState.slots.size(); i++) {
                if (gState.slots[i].hasMapping) udpFds[i] = gState.slots[i].socketFd;
            }
        }

        if (tunFd >= 0) fds.push_back({tunFd, POLLIN, 0});
        if (dispatcherFd >= 0) fds.push_back({dispatcherFd, POLLIN, 0});
        for (int fd : udpFds) {
            if (fd >= 0) fds.push_back({fd, POLLIN, 0});
        }

        if (fds.empty()) {
            usleep(100000);
            continue;
        }

        int result = poll(fds.data(), fds.size(), 250);
        if (result < 0) {
            if (errno != EINTR) LOGW("poll failed: %s", strerror(errno));
            continue;
        }
        if (result == 0) continue;

        size_t index = 0;
        if (tunFd >= 0) {
            if (fds[index].revents & POLLIN) readTunPackets();
            index++;
        }
        if (dispatcherFd >= 0) {
            if (fds[index].revents & POLLIN) readHevPackets();
            index++;
        }
        for (size_t slotIndex = 0; slotIndex < gState.slots.size(); slotIndex++) {
            if (udpFds[slotIndex] >= 0) {
                if (fds[index].revents & POLLIN) {
                    emitInboundUdp(kSunshineUdpPorts[slotIndex], udpFds[slotIndex]);
                }
                index++;
            }
        }
    }
    LOGI("TUN UDP NAT dispatcher stopped");
}

void closeFd(int* fd) {
    if (*fd >= 0) {
        close(*fd);
        *fd = -1;
    }
}

void stopLocked(JNIEnv* env) {
    gState.running.store(false);
    closeFd(&gState.dispatcherFd);
    closeFd(&gState.hevFd);
    for (auto& slot : gState.slots) {
        closeFd(&slot.socketFd);
        slot.hasMapping = false;
        slot.hasClient = false;
    }
    int tunFd = gState.tunFd;
    gState.tunFd = -1;

    if (gState.worker.joinable()) {
        gState.worker.join();
    }

    gState.tunFd = tunFd;

    if (gState.vpnService) {
        env->DeleteGlobalRef(gState.vpnService);
        gState.vpnService = nullptr;
    }
    gState.protectMethod = nullptr;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_dev_rocky_moonlightnat_NativeTunNat_start(
    JNIEnv* env,
    jobject /* thiz */,
    jint tunFd,
    jobject vpnService,
    jstring vpnAddress,
    jstring virtualIp
) {
    if (gState.running.load()) {
        LOGW("TUN UDP NAT already running");
        return -1;
    }

    const char* vpnAddressChars = env->GetStringUTFChars(vpnAddress, nullptr);
    const char* virtualIpChars = env->GetStringUTFChars(virtualIp, nullptr);

    uint32_t vpnIp = 0;
    uint32_t virtualIpValue = 0;
    bool parsed = parseIpv4(vpnAddressChars, &vpnIp) && parseIpv4(virtualIpChars, &virtualIpValue);

    env->ReleaseStringUTFChars(vpnAddress, vpnAddressChars);
    env->ReleaseStringUTFChars(virtualIp, virtualIpChars);

    if (!parsed) {
        LOGE("invalid IPv4 address for TUN UDP NAT");
        return -1;
    }

    int pairFds[2] = {-1, -1};
    if (socketpair(AF_UNIX, SOCK_DGRAM, 0, pairFds) != 0) {
        LOGE("socketpair failed: %s", strerror(errno));
        return -1;
    }

    setNonBlocking(tunFd);
    setNonBlocking(pairFds[0]);
    setNonBlocking(pairFds[1]);

    env->GetJavaVM(&gState.vm);
    jobject serviceRef = env->NewGlobalRef(vpnService);
    jclass serviceClass = env->GetObjectClass(vpnService);
    jmethodID protectMethod = env->GetMethodID(serviceClass, "protect", "(I)Z");

    {
        std::lock_guard<std::mutex> guard(gState.mutex);
        gState.tunFd = tunFd;
        gState.hevFd = pairFds[0];
        gState.dispatcherFd = pairFds[1];
        gState.virtualIp = virtualIpValue;
        gState.vpnIp = vpnIp;
        gState.vpnService = serviceRef;
        gState.protectMethod = protectMethod;
        for (size_t i = 0; i < gState.slots.size(); i++) {
            gState.slots[i] = UdpSlot{};
            gState.slots[i].virtualPort = kSunshineUdpPorts[i];
        }
    }

    gState.running.store(true);
    gState.worker = std::thread(dispatcherLoop);
    LOGI("TUN UDP NAT started");
    return pairFds[0];
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_rocky_moonlightnat_NativeTunNat_updateMapping(
    JNIEnv* env,
    jobject /* thiz */,
    jstring realHost,
    jint udp47998,
    jint udp47999,
    jint udp48000
) {
    if (!gState.running.load()) return JNI_FALSE;

    const char* realHostChars = env->GetStringUTFChars(realHost, nullptr);
    uint32_t realIp = 0;
    bool parsed = parseIpv4(realHostChars, &realIp);
    env->ReleaseStringUTFChars(realHost, realHostChars);
    if (!parsed) {
        LOGW("TUN UDP NAT only supports IPv4 literal webhook host for now");
        return JNI_FALSE;
    }

    std::array<int, 3> realPorts = {udp47998, udp47999, udp48000};
    std::lock_guard<std::mutex> guard(gState.mutex);
    for (size_t i = 0; i < gState.slots.size(); i++) {
        auto& slot = gState.slots[i];
        if (realPorts[i] <= 0 || realPorts[i] > 65535) {
            slot.hasMapping = false;
            continue;
        }

        if (slot.socketFd < 0) {
            slot.socketFd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
            if (slot.socketFd < 0) {
                LOGW("UDP socket create failed: %s", strerror(errno));
                slot.hasMapping = false;
                continue;
            }
            setNonBlocking(slot.socketFd);
            if (!protectSocket(env, slot.socketFd)) {
                LOGW("VpnService.protect failed for native UDP socket");
                closeFd(&slot.socketFd);
                slot.hasMapping = false;
                continue;
            }
        }

        sockaddr_in addr{};
        addr.sin_family = AF_INET;
        addr.sin_addr.s_addr = realIp;
        addr.sin_port = htons(static_cast<uint16_t>(realPorts[i]));
        slot.realAddr = addr;
        if (connect(slot.socketFd, reinterpret_cast<sockaddr*>(&slot.realAddr), sizeof(slot.realAddr)) != 0 &&
            errno != EINPROGRESS) {
            LOGW("UDP connect failed for %u -> %d: %s", slot.virtualPort, realPorts[i], strerror(errno));
            slot.hasMapping = false;
            continue;
        }
        slot.hasMapping = true;
    }

    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_rocky_moonlightnat_NativeTunNat_stop(JNIEnv* env, jobject /* thiz */) {
    stopLocked(env);
    LOGI("TUN UDP NAT stopped");
}

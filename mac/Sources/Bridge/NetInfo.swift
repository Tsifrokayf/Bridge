import Foundation
import Darwin

/// Addresses the phone needs to find (and wake) this Mac later: IPv4 addresses and hardware (MAC) addresses of the real network
/// interfaces (Wi-Fi, Ethernet, Thunderbolt Ethernet…); loopback, tunnels and virtual interfaces are left out.
enum NetInfo {
    static func addresses() -> (ips: [String], macs: [String]) {
        var ips: [String] = []
        var macs: [String] = []
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let first = head else { return ([], []) }
        defer { freeifaddrs(head) }
        var p: UnsafeMutablePointer<ifaddrs>? = first
        while let cur = p {
            defer { p = cur.pointee.ifa_next }
            let name = String(cString: cur.pointee.ifa_name)
            guard name.hasPrefix("en"), let sa = cur.pointee.ifa_addr else { continue }
            let flags = Int32(cur.pointee.ifa_flags)
            guard flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0 else { continue }
            switch Int32(sa.pointee.sa_family) {
            case AF_INET:
                var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                if getnameinfo(sa, socklen_t(sa.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                    let ip = String(cString: host)
                    if !ip.hasPrefix("169.254.") && !ips.contains(ip) { ips.append(ip) }
                }
            case AF_LINK:
                sa.withMemoryRebound(to: sockaddr_dl.self, capacity: 1) { dl in
                    guard Int(dl.pointee.sdl_alen) == 6 else { return }
                    let nlen = Int(dl.pointee.sdl_nlen)
                    let bytes: [UInt8] = withUnsafePointer(to: &dl.pointee.sdl_data) { tup in
                        tup.withMemoryRebound(to: UInt8.self, capacity: nlen + 6) { b in (0..<6).map { b[nlen + $0] } }
                    }
                    let mac = bytes.map { String(format: "%02x", $0) }.joined(separator: ":")
                    if mac != "00:00:00:00:00:00" && !macs.contains(mac) { macs.append(mac) }
                }
            default: break
            }
        }
        return (ips, macs)
    }
}

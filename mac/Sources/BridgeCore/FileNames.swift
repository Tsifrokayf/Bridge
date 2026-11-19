import Foundation

public enum FileNames {
    /// Mirrors reference/bridge_proto.safe_name: last path segment, no control/reserved characters,
    /// no leading/trailing spaces or dots, at most 200 characters, never empty.
    public static func safe(_ name: String) -> String {
        let normalized = name.replacingOccurrences(of: "\\", with: "/")
        let last = normalized.split(separator: "/", omittingEmptySubsequences: false).last.map(String.init) ?? ""
        let bad: Set<Unicode.Scalar> = ["<", ">", ":", "\"", "|", "?", "*"]
        var scalars = String.UnicodeScalarView()
        for u in last.unicodeScalars {
            if u.value < 0x20 || bad.contains(u) {
                scalars.append("_")
            } else {
                scalars.append(u)
            }
        }
        var out = Array(String(scalars).unicodeScalars)
        while let f = out.first, f == " " || f == "." { out.removeFirst() }
        while let l = out.last, l == " " || l == "." { out.removeLast() }
        if out.count > 200 { out = Array(out.prefix(200)) }
        var view = String.UnicodeScalarView()
        view.append(contentsOf: out)
        let s = String(view)
        return s.isEmpty ? "file" : s
    }

    /// "name.ext" → "name (1).ext" … until the path does not exist.
    public static func uniqueURL(in directory: URL, name: String) -> URL {
        let fm = FileManager.default
        var url = directory.appendingPathComponent(name)
        if !fm.fileExists(atPath: url.path) { return url }
        let ns = name as NSString
        let ext = ns.pathExtension
        let base = ns.deletingPathExtension
        var i = 1
        while true {
            let candidate = ext.isEmpty ? "\(base) (\(i))" : "\(base) (\(i)).\(ext)"
            url = directory.appendingPathComponent(candidate)
            if !fm.fileExists(atPath: url.path) { return url }
            i += 1
        }
    }
}

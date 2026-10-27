using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;

namespace Send.Core;

/// <summary>
/// SEND/1 wire format. Every connection is TLS. A frame is a 4-byte big-endian length followed by
/// UTF-8 JSON. The first frame says what the connection is for ("op"); data connections then carry
/// raw file bytes between frames.
/// </summary>
public static class Proto
{
    public const int Port = 53420;
    public const int MaxFrame = 8 << 20;
    public const int Buf = 1 << 20;
}

public static class Wire
{
    /// <summary>Next frame, or null on a clean close between frames.</summary>
    public static async Task<JsonObject?> ReadFrameAsync(Stream s, CancellationToken ct)
    {
        var h = new byte[4];
        int n = 0;
        while (n < 4)
        {
            int r = await s.ReadAsync(h.AsMemory(n, 4 - n), ct).ConfigureAwait(false);
            if (r == 0)
            {
                if (n == 0) return null;
                throw new EndOfStreamException("frame header");
            }
            n += r;
        }
        int len = BinaryPrimitives.ReadInt32BigEndian(h);
        if (len < 0 || len > Proto.MaxFrame) throw new InvalidDataException("bad frame length");
        var b = new byte[len];
        await s.ReadExactlyAsync(b, ct).ConfigureAwait(false);
        return JsonNode.Parse(b)?.AsObject();
    }

    public static async Task WriteFrameAsync(Stream s, JsonObject o, CancellationToken ct)
    {
        var json = Encoding.UTF8.GetBytes(o.ToJsonString());
        var all = new byte[4 + json.Length];
        BinaryPrimitives.WriteInt32BigEndian(all, json.Length);
        json.CopyTo(all, 4);
        await s.WriteAsync(all, ct).ConfigureAwait(false);
        await s.FlushAsync(ct).ConfigureAwait(false);
    }

    public static string S(this JsonObject o, string k) => o[k] is JsonValue v && v.TryGetValue<string>(out var s) ? s : "";
    public static long L(this JsonObject o, string k, long d = 0) => o[k] is JsonValue v && v.TryGetValue<long>(out var x) ? x : d;
    public static bool B(this JsonObject o, string k, bool d = false) => o[k] is JsonValue v && v.TryGetValue<bool>(out var x) ? x : d;
}

public static class Util
{
    public static string Hex(byte[] b) => Convert.ToHexString(b).ToLowerInvariant();

    public static string RandomHex(int bytes) => Hex(RandomNumberGenerator.GetBytes(bytes));

    /// <summary>
    /// Four digits both screens show while pairing. Derived from the certificate the phone presented,
    /// so a man in the middle (who must present a different certificate) makes the two differ.
    /// </summary>
    public static string PairCode(string certFingerprintHex, string nonce)
    {
        var d = SHA256.HashData(Encoding.UTF8.GetBytes($"{certFingerprintHex}:{nonce}"));
        uint v = BinaryPrimitives.ReadUInt32BigEndian(d);
        return (v % 10000).ToString("D4");
    }

    public static string Size(long b)
    {
        string[] u = { "B", "KB", "MB", "GB", "TB" };
        double v = b;
        int i = 0;
        while (v >= 1000 && i < u.Length - 1) { v /= 1000; i++; }
        return i == 0 ? $"{b} B" : $"{v:0.#} {u[i]}";
    }

    private static readonly char[] Bad = Path.GetInvalidFileNameChars();

    public static string CleanName(string n)
    {
        var sb = new StringBuilder();
        foreach (var ch in n) sb.Append(Array.IndexOf(Bad, ch) >= 0 || ch < 32 ? '_' : ch);
        var s = sb.ToString().Trim().Trim('.').Trim();
        if (s.Length == 0) s = "file";
        if (s.Length > 150) s = s[..150];
        return s;
    }

    /// <summary>Folder part of a received path: relative, no "..", no empty segments.</summary>
    public static string? CleanRel(string? r)
    {
        if (string.IsNullOrWhiteSpace(r)) return null;
        var parts = r.Split('/', '\\').Select(CleanNameOrEmpty).Where(p => p.Length > 0).Take(12).ToArray();
        return parts.Length == 0 ? null : string.Join(Path.DirectorySeparatorChar, parts);
    }

    private static string CleanNameOrEmpty(string p)
    {
        var s = CleanName(p);
        return p.Trim().Trim('.').Length == 0 ? "" : s;
    }

    /// <summary>name.ext, then name (1).ext, name (2).ext ... whichever does not exist yet.</summary>
    public static string UniquePath(string dir, string name)
    {
        var path = Path.Combine(dir, name);
        if (!File.Exists(path) && !Directory.Exists(path)) return path;
        var stem = Path.GetFileNameWithoutExtension(name);
        var ext = Path.GetExtension(name);
        for (int i = 1; ; i++)
        {
            path = Path.Combine(dir, $"{stem} ({i}){ext}");
            if (!File.Exists(path) && !Directory.Exists(path)) return path;
        }
    }
}

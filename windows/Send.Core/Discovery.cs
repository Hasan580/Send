using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Send.Core;

public sealed record Found(IPAddress Ip, string Id, string Name, string Model);

/// <summary>
/// Finds phones running Send. The PC only ever connects out, so nothing here needs a firewall rule:
/// it probes port 53420 on every host of the local /24 (physical adapters only) and asks "hello".
/// </summary>
public static class Discovery
{
    private static readonly Regex Virtual = new(
        "virtual|vmware|hyper-v|vpn|tap-|tunnel|zerotier|tailscale|wireguard|bluetooth|loopback|vethernet|npcap|pseudo|wan miniport|openvpn|nordlynx",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);

    public static List<IPAddress> LocalAddresses()
    {
        var list = new List<IPAddress>();
        foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (nic.OperationalStatus != OperationalStatus.Up) continue;
            if (nic.NetworkInterfaceType is not (NetworkInterfaceType.Ethernet or NetworkInterfaceType.Wireless80211
                or NetworkInterfaceType.GigabitEthernet or NetworkInterfaceType.FastEthernetT)) continue;
            if (Virtual.IsMatch(nic.Description) || Virtual.IsMatch(nic.Name)) continue;
            foreach (var ua in nic.GetIPProperties().UnicastAddresses)
            {
                var a = ua.Address;
                if (a.AddressFamily != AddressFamily.InterNetwork) continue;
                var b = a.GetAddressBytes();
                bool priv = b[0] == 10 || (b[0] == 172 && b[1] >= 16 && b[1] <= 31) || (b[0] == 192 && b[1] == 168);
                if (priv) list.Add(a);
            }
        }
        return list;
    }

    /// <summary>Asks one host "who are you". Null if nothing answers or it is not a phone running Send.</summary>
    public static async Task<Found?> HelloAsync(IPAddress ip, CancellationToken ct, int timeoutMs = 1500)
    {
        try
        {
            using var c = await Link.ConnectAsync(ip, null, ct, timeoutMs).ConfigureAwait(false);
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(timeoutMs + 1500);
            await Wire.WriteFrameAsync(c.Ssl, new JsonObject { ["op"] = "hello", ["v"] = 1 }, cts.Token).ConfigureAwait(false);
            var r = await Wire.ReadFrameAsync(c.Ssl, cts.Token).ConfigureAwait(false);
            if (r == null || !r.B("ok")) return null;
            return new Found(ip, r.S("id"), r.S("name"), r.S("model"));
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
        catch { return null; }
    }

    /// <summary>Probes the local /24s (plus any manually added addresses) and returns every phone that answered.</summary>
    public static async Task<List<Found>> ScanAsync(IEnumerable<string> extra, CancellationToken ct)
    {
        var local = LocalAddresses();
        var targets = new HashSet<IPAddress>();
        foreach (var a in local)
        {
            var b = a.GetAddressBytes();
            for (int i = 1; i < 255; i++)
            {
                if (i == b[3]) continue;
                targets.Add(new IPAddress(new[] { b[0], b[1], b[2], (byte)i }));
            }
        }
        foreach (var s in extra)
            if (IPAddress.TryParse(s, out var ip) && !local.Contains(ip)) targets.Add(ip);

        var found = new List<Found>();
        await Parallel.ForEachAsync(targets, new ParallelOptions { MaxDegreeOfParallelism = 96, CancellationToken = ct }, async (ip, token) =>
        {
            if (!await PortOpenAsync(ip, token).ConfigureAwait(false)) return;
            var f = await HelloAsync(ip, token).ConfigureAwait(false);
            if (f != null) lock (found) found.Add(f);
        }).ConfigureAwait(false);
        return found;
    }

    private static async Task<bool> PortOpenAsync(IPAddress ip, CancellationToken ct)
    {
        using var tcp = new TcpClient(AddressFamily.InterNetwork);
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(450);
        try
        {
            await tcp.ConnectAsync(ip, Proto.Port, cts.Token).ConfigureAwait(false);
            return true;
        }
        catch { return false; }
    }
}

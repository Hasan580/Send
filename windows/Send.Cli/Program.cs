using Send.Core;

// Test driver for the same Agent the Windows app uses.
//   sendcli scan
//   sendcli send <path>...        send files/folders to the phone once it is connected
//   sendcli listen <seconds>      stay connected and receive whatever the phone offers
//   sendcli text <message>
//   sendcli forget                drop all pairings from this PC's config
// Set SEND_HOME to use a separate config folder.

if (args.Length == 0)
{
    Console.WriteLine("usage: sendcli scan | send <paths...> | listen <seconds> | text <msg> | forget");
    return 2;
}

var cfg = Config.Load();
var cmd = args[0];

if (cmd == "forget")
{
    cfg.Phones.Clear();
    cfg.Save();
    Console.WriteLine("pairings cleared");
    return 0;
}

if (cmd == "scan")
{
    var sw0 = System.Diagnostics.Stopwatch.StartNew();
    Console.WriteLine("local: " + string.Join(", ", Discovery.LocalAddresses()));
    var found = await Discovery.ScanAsync(cfg.ManualIps, CancellationToken.None);
    foreach (var f in found) Console.WriteLine($"found {f.Name} ({f.Model}) id={f.Id} at {f.Ip}");
    Console.WriteLine($"{found.Count} phone(s) in {sw0.ElapsedMilliseconds} ms");
    return found.Count > 0 ? 0 : 1;
}

if (cmd == "probe")
{
    // Everything here must be refused by the phone.
    var ip = System.Net.IPAddress.Parse(args[1]);
    var id = cfg.DeviceId;
    async Task<string> Ask(Func<System.Text.Json.Nodes.JsonObject> req)
    {
        try
        {
            using var c = await Link.ConnectAsync(ip, null, CancellationToken.None);
            using var cts = new CancellationTokenSource(5000);
            await Wire.WriteFrameAsync(c.Ssl, req(), cts.Token);
            var r = await Wire.ReadFrameAsync(c.Ssl, cts.Token);
            return r?.ToJsonString() ?? "(connection closed)";
        }
        catch (Exception e) { return "(" + e.GetType().Name + ")"; }
    }
    var nodes = System.Text.Json.Nodes.JsonNode.Parse("{}")!.AsObject();
    Console.WriteLine("session, wrong token : " + await Ask(() => new() { ["op"] = "session", ["id"] = id, ["token"] = "deadbeef" }));
    Console.WriteLine("session, unknown id  : " + await Ask(() => new() { ["op"] = "session", ["id"] = "nobody", ["token"] = "x" }));
    Console.WriteLine("put, wrong token     : " + await Ask(() => new() { ["op"] = "put", ["id"] = id, ["token"] = "deadbeef", ["tid"] = "x", ["i"] = 0 }));
    Console.WriteLine("get, wrong token     : " + await Ask(() => new() { ["op"] = "get", ["id"] = id, ["token"] = "deadbeef", ["tid"] = "x", ["i"] = 0 }));
    Console.WriteLine("get, no token        : " + await Ask(() => new() { ["op"] = "get", ["id"] = id, ["tid"] = "x", ["i"] = 0 }));
    Console.WriteLine("unknown op           : " + await Ask(() => new() { ["op"] = "format-disk" }));
    try
    {
        using var c = await Link.ConnectAsync(ip, null, CancellationToken.None);
        await c.Ssl.WriteAsync(new byte[] { 0x06, 0x40, 0x00, 0x00, 1, 2, 3 }); // claims a 104 MB frame
        await c.Ssl.FlushAsync();
        using var cts = new CancellationTokenSource(5000);
        var r = await Wire.ReadFrameAsync(c.Ssl, cts.Token);
        Console.WriteLine("oversized frame      : " + (r?.ToJsonString() ?? "(connection closed)"));
    }
    catch (Exception e) { Console.WriteLine("oversized frame      : (" + e.GetType().Name + ")"); }
    try
    {
        using var c = await Link.ConnectAsync(ip, new string('0', 64), CancellationToken.None);
        Console.WriteLine("wrong pinned cert    : CONNECTED (BAD)");
    }
    catch (Exception e) { Console.WriteLine("wrong pinned cert    : refused (" + e.GetType().Name + ")"); }
    return 0;
}

using var agent = new Agent(cfg);
string last = "";
agent.Changed += () =>
{
    var line = $"[{agent.Status}] {agent.PhoneName} {agent.PhoneIp} {(agent.PairCode != null ? "code " + agent.PairCode : "")} {agent.Hint}".Trim();
    if (line != last) { last = line; Console.WriteLine(line); }
};
agent.XferAdded += x => Console.WriteLine($"[xfer {x.Dir}] {x.Title} {x.SizeText}");
agent.XferFinished += x => Console.WriteLine($"[done {x.Dir}] {x.Title} -> {x.State} {x.Error} avg {Util.Size((long)x.AvgSpeed)}/s in {(x.Ended - x.Started).TotalSeconds:0.0}s");
agent.TextReceived += t => Console.WriteLine("[text] " + t);
var sw = System.Diagnostics.Stopwatch.StartNew();
agent.Start();

async Task<bool> Connected(int seconds)
{
    var end = DateTime.UtcNow.AddSeconds(seconds);
    while (DateTime.UtcNow < end)
    {
        if (agent.IsConnected) return true;
        await Task.Delay(100);
    }
    return false;
}

if (!await Connected(cmd == "listen" ? 90 : 120))
{
    Console.WriteLine("could not connect to a phone");
    return 1;
}
Console.WriteLine($"connected after {sw.ElapsedMilliseconds} ms");

switch (cmd)
{
    case "send":
    {
        var x = agent.Send(args.Skip(1));
        while (x.IsActive)
        {
            await Task.Delay(1000);
            Console.WriteLine($"  {x.StatusText} {Util.Size(x.Bytes)} / {Util.Size(x.Total)} {x.SpeedText}");
        }
        await Task.Delay(300);
        return x.State == XState.Done ? 0 : 1;
    }
    case "text":
        await agent.SendTextAsync(string.Join(' ', args.Skip(1)));
        await Task.Delay(500);
        return 0;
    case "listen":
    {
        int secs = args.Length > 1 ? int.Parse(args[1]) : 60;
        var end = DateTime.UtcNow.AddSeconds(secs);
        int lastCount = -1;
        while (DateTime.UtcNow < end)
        {
            await Task.Delay(1000);
            foreach (var x in agent.Xfers.Where(x => x.State == XState.Active))
                Console.WriteLine($"  {x.StatusText} {Util.Size(x.Bytes)} / {Util.Size(x.Total)} {x.SpeedText}");
            // stop early once something arrived and nothing is running
            int finished = agent.Xfers.Count(x => !x.IsActive);
            if (finished > 0 && finished == agent.Xfers.Count && finished != lastCount && agent.Xfers.All(x => (DateTime.Now - x.Ended).TotalSeconds > 2)) break;
            lastCount = finished;
        }
        return agent.Xfers.Any(x => x.State == XState.Done) ? 0 : 1;
    }
}
return 2;

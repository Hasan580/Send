using System.Text.Json;

namespace Send.Core;

public sealed class PhoneRec
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public string Ip { get; set; } = "";
    /// <summary>Pinned SHA-256 of the phone's certificate.</summary>
    public string Fp { get; set; } = "";
    public string Token { get; set; } = "";
}

public sealed class Config
{
    public string DeviceId { get; set; } = Util.RandomHex(8);
    public string DeviceName { get; set; } = Environment.MachineName;
    public string SaveDir { get; set; } = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "Send");
    public bool AutoPair { get; set; } = true;
    public List<PhoneRec> Phones { get; set; } = new();
    public List<string> ManualIps { get; set; } = new();

    private static readonly object Gate = new();

    public static string Dir => Environment.GetEnvironmentVariable("SEND_HOME")
        ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Send");

    private static string FilePath => Path.Combine(Dir, "config.json");

    public static Config Load()
    {
        try
        {
            if (File.Exists(FilePath))
            {
                var c = JsonSerializer.Deserialize<Config>(File.ReadAllText(FilePath));
                if (c != null) return c;
            }
        }
        catch { }
        var fresh = new Config();
        fresh.Save();
        return fresh;
    }

    public void Save()
    {
        lock (Gate)
        {
            try
            {
                Directory.CreateDirectory(Dir);
                var tmp = FilePath + ".tmp";
                File.WriteAllText(tmp, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
                File.Move(tmp, FilePath, true);
            }
            catch { }
        }
    }
}

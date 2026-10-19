using System.Security.Cryptography;
using System.Text.Json;

namespace CopyCat;

public record HistoryItem(string Kind, string Text, string? FilePath, DateTime At, string From);

public class Config
{
    public string Pin { get; set; } = NewPin();
    public List<string> Tokens { get; set; } = [];
    public bool Notify { get; set; } = true;
    public bool AutoAccept { get; set; } = true;

    public static string NewPin() => RandomNumberGenerator.GetInt32(0, 1_000_000).ToString("D6");
}

public class Store
{
    const int MaxHistory = 50;
    static readonly JsonSerializerOptions Json = new() { WriteIndented = true };

    public static readonly string Dir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "CopyCat");
    public static readonly string ReceivedDir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "CopyCat");

    readonly string configPath = Path.Combine(Dir, "config.json");
    readonly string historyPath = Path.Combine(Dir, "history.json");
    readonly object gate = new();

    public Config Config { get; private set; }
    public IReadOnlyList<HistoryItem> History { get; private set; }

    public event Action? Changed;

    public Store()
    {
        Directory.CreateDirectory(Dir);
        Config = Load<Config>(configPath) ?? new Config();
        History = Load<List<HistoryItem>>(historyPath) ?? [];
        Save(configPath, Config);
    }

    public bool IsValidToken(string? token)
    {
        if (string.IsNullOrEmpty(token)) return false;
        lock (gate) return Config.Tokens.Contains(token);
    }

    public string? Pair(string pin)
    {
        lock (gate)
        {
            if (!Config.AutoAccept && !CryptographicOperations.FixedTimeEquals(
                    System.Text.Encoding.UTF8.GetBytes(pin), System.Text.Encoding.UTF8.GetBytes(Config.Pin)))
                return null;
            var token = Convert.ToHexString(RandomNumberGenerator.GetBytes(24));
            Config.Tokens.Add(token);
            Save(configPath, Config);
            return token;
        }
    }

    public void RegeneratePin()
    {
        lock (gate)
        {
            Config.Pin = Config.NewPin();
            Save(configPath, Config);
        }
        Changed?.Invoke();
    }

    public void ForgetDevices()
    {
        lock (gate)
        {
            Config.Tokens.Clear();
            Save(configPath, Config);
        }
        Changed?.Invoke();
    }

    public void SetAutoAccept(bool on)
    {
        lock (gate)
        {
            Config.AutoAccept = on;
            Save(configPath, Config);
        }
        Changed?.Invoke();
    }

    public void SetNotify(bool on)
    {
        lock (gate)
        {
            Config.Notify = on;
            Save(configPath, Config);
        }
        Changed?.Invoke();
    }

    public void Add(HistoryItem item)
    {
        lock (gate)
        {
            History = new[] { item }.Concat(History.Where(h => !(h.Kind == item.Kind && h.Text == item.Text)))
                .Take(MaxHistory).ToList();
            Save(historyPath, History);
        }
        Changed?.Invoke();
    }

    public void ClearHistory()
    {
        lock (gate)
        {
            History = [];
            Save(historyPath, History);
        }
        Changed?.Invoke();
    }

    static T? Load<T>(string path)
    {
        try { return File.Exists(path) ? JsonSerializer.Deserialize<T>(File.ReadAllText(path)) : default; }
        catch (JsonException) { return default; }
    }

    static void Save<T>(string path, T value)
    {
        var tmp = path + ".tmp";
        File.WriteAllText(tmp, JsonSerializer.Serialize(value, Json));
        File.Move(tmp, path, overwrite: true);
    }
}

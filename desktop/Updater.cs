using System.Diagnostics;
using System.Net.Http.Headers;
using System.Reflection;
using System.Text.Json;

namespace CopyCat;

public record Release(Version Version, string ExeUrl);

public static class Updater
{
    const string LatestUrl = "https://api.github.com/repos/BeathovenGala/copycat/releases/latest";
    // Must initialize before Http, which reads it.
    public static Version Current { get; } = Assembly.GetExecutingAssembly().GetName().Version ?? new Version(0, 0);

    static readonly HttpClient Http = CreateClient();

    public static async Task<Release?> CheckAsync()
    {
        using var res = await Http.GetAsync(LatestUrl);
        if (!res.IsSuccessStatusCode) return null;
        using var doc = JsonDocument.Parse(await res.Content.ReadAsStringAsync());
        var tag = doc.RootElement.GetProperty("tag_name").GetString()?.TrimStart('v');
        if (!Version.TryParse(tag, out var version)) return null;
        var exe = doc.RootElement.GetProperty("assets").EnumerateArray()
            .FirstOrDefault(a => a.GetProperty("name").GetString()?.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) == true);
        if (exe.ValueKind == JsonValueKind.Undefined) return null;
        var release = new Release(version, exe.GetProperty("browser_download_url").GetString()!);
        return Normalize(release.Version) > Normalize(Current) ? release : null;
    }

    /// <summary>Downloads the new exe, then a detached script swaps it in once this process exits and relaunches.</summary>
    public static async Task DownloadAndStageAsync(Release release)
    {
        var newExe = Path.Combine(Program.InstallDir, "CopyCat.new.exe");
        await using (var src = await Http.GetStreamAsync(release.ExeUrl))
        await using (var dst = File.Create(newExe))
            await src.CopyToAsync(dst);

        var script = Path.Combine(Program.InstallDir, "update.cmd");
        File.WriteAllText(script, $"""
            @echo off
            cd /d "{Program.InstallDir}"
            :retry
            timeout /t 1 /nobreak >nul
            move /y "CopyCat.new.exe" "CopyCat.exe" >nul 2>&1 || goto retry
            start "" "CopyCat.exe"
            del "%~f0"
            """);
        Process.Start(new ProcessStartInfo("cmd.exe", $"/c \"{script}\"")
        {
            CreateNoWindow = true,
            UseShellExecute = false,
            WorkingDirectory = Program.InstallDir,
        });
    }

    static Version Normalize(Version v) => new(v.Major, v.Minor, Math.Max(v.Build, 0));

    static HttpClient CreateClient()
    {
        var client = new HttpClient { Timeout = TimeSpan.FromMinutes(5) };
        client.DefaultRequestHeaders.UserAgent.Add(new ProductInfoHeaderValue("CopyCat", Current.ToString()));
        client.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/vnd.github+json"));
        return client;
    }
}

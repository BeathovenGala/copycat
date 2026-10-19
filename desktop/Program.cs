using System.Diagnostics;

namespace CopyCat;

static class Program
{
    public static readonly string InstallDir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "CopyCat");
    public static readonly string InstalledExe = Path.Combine(InstallDir, "CopyCat.exe");

    [STAThread]
    static void Main()
    {
        if (!IsInstalledCopy() && !IsDevBuild() && TryInstall()) return;

        using var mutex = new Mutex(true, @"Local\CopyCat.Desktop", out var isFirstInstance);
        if (!isFirstInstance) return;

        ApplicationConfiguration.Initialize();
        Application.Run(new TrayApp());
    }

    static bool IsInstalledCopy() =>
        string.Equals(Path.GetFullPath(Environment.ProcessPath!), Path.GetFullPath(InstalledExe), StringComparison.OrdinalIgnoreCase);

    // A non-single-file build is just a launcher for the CopyCat.dll beside it; copying it alone would break the install.
    static bool IsDevBuild() => File.Exists(Path.Combine(AppContext.BaseDirectory, "CopyCat.dll"));

    // Running the downloaded exe copies it into %LOCALAPPDATA%\Programs so startup and updates have a fixed path.
    static bool TryInstall()
    {
        try
        {
            foreach (var p in Process.GetProcessesByName("CopyCat").Where(p => p.Id != Environment.ProcessId))
            {
                p.Kill();
                p.WaitForExit(5000);
            }
            Directory.CreateDirectory(InstallDir);
            File.Copy(Environment.ProcessPath!, InstalledExe, overwrite: true);
            Process.Start(new ProcessStartInfo(InstalledExe) { UseShellExecute = true });
            return true;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or System.ComponentModel.Win32Exception)
        {
            return false;
        }
    }
}

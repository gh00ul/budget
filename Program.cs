using System.Diagnostics;
using System.Globalization;
using System.Reflection;
using System.Text.Json;

namespace Budget;

static class Program
{
    [STAThread]
    static void Main()
    {
        ApplicationConfiguration.Initialize();
        Application.Run(new MainForm());
    }
}

class Bill
{
    public string Name { get; set; } = "";
    public decimal Amount { get; set; }
}

// Saved to %APPDATA%\Budget\budget.json so updates never touch your numbers.
class BudgetData
{
    public decimal Income { get; set; }
    public List<Bill> Bills { get; set; } = new();

    static readonly string FilePath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Budget", "budget.json");

    public static BudgetData Load()
    {
        if (!File.Exists(FilePath)) return new();
        try
        {
            return JsonSerializer.Deserialize<BudgetData>(File.ReadAllText(FilePath)) ?? new();
        }
        catch
        {
            File.Copy(FilePath, FilePath + ".bak", overwrite: true); // keep the unreadable file instead of losing it
            return new();
        }
    }

    public void Save()
    {
        Directory.CreateDirectory(Path.GetDirectoryName(FilePath)!);
        File.WriteAllText(FilePath, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
    }
}

// Checks GitHub Releases for a newer Budget.exe and swaps it in place.
static class Updater
{
    const string Repo = "gh00ul/budget";
    const string AssetName = "Budget.exe";

    static readonly HttpClient Http = CreateClient();

    public static Version Current { get; } = Normalize(Assembly.GetExecutingAssembly().GetName().Version!);

    static HttpClient CreateClient()
    {
        var http = new HttpClient { Timeout = TimeSpan.FromSeconds(30) };
        http.DefaultRequestHeaders.UserAgent.ParseAdd("Budget-App");
        return http;
    }

    static Version Normalize(Version v) => new(v.Major, v.Minor, Math.Max(v.Build, 0));

    // Returns the newer version and its download URL, or null when already up to date.
    public static async Task<(Version Version, string Url)?> CheckAsync()
    {
        var json = await Http.GetStringAsync($"https://api.github.com/repos/{Repo}/releases/latest");
        using var doc = JsonDocument.Parse(json);
        var release = doc.RootElement;

        if (!Version.TryParse(release.GetProperty("tag_name").GetString()?.TrimStart('v'), out var latest)) return null;
        latest = Normalize(latest);
        if (latest <= Current) return null;

        foreach (var asset in release.GetProperty("assets").EnumerateArray())
            if (asset.GetProperty("name").GetString() == AssetName)
                return (latest, asset.GetProperty("browser_download_url").GetString()!);
        return null;
    }

    public static async Task InstallAsync(string url)
    {
        var exe = Environment.ProcessPath!;
        var newExe = exe + ".new";
        var oldExe = exe + ".old";

        await File.WriteAllBytesAsync(newExe, await Http.GetByteArrayAsync(url));

        // Windows won't let a running exe be overwritten, but it can be renamed out of the way.
        File.Move(exe, oldExe, overwrite: true);
        try
        {
            File.Move(newExe, exe);
        }
        catch
        {
            File.Move(oldExe, exe);
            throw;
        }

        Process.Start(exe);
        Application.Exit();
    }

    // Deletes the previous version left behind by an update (it can take a moment to finish closing).
    public static async Task CleanupAsync()
    {
        var oldExe = Environment.ProcessPath + ".old";
        for (int i = 0; i < 20 && File.Exists(oldExe); i++)
        {
            try { File.Delete(oldExe); }
            catch { await Task.Delay(500); }
        }
    }
}

class MainForm : Form
{
    readonly BudgetData data = BudgetData.Load();

    readonly TextBox incomeBox = new() { Anchor = AnchorStyles.Left | AnchorStyles.Right, PlaceholderText = "0.00" };
    readonly ListView billList = new()
    {
        Dock = DockStyle.Fill,
        View = View.Details,
        FullRowSelect = true,
        HeaderStyle = ColumnHeaderStyle.Nonclickable,
    };
    readonly TextBox nameBox = new() { Dock = DockStyle.Fill, PlaceholderText = "Bill name" };
    readonly TextBox amountBox = new() { Dock = DockStyle.Fill, PlaceholderText = "Amount" };
    readonly Label totalLabel = new() { AutoSize = true, Anchor = AnchorStyles.Right };
    readonly Label leftLabel = new() { AutoSize = true, Anchor = AnchorStyles.Right };
    readonly Label updateLabel = new() { AutoSize = true, Anchor = AnchorStyles.Left, ForeColor = Color.DimGray };
    readonly Button updateButton = new() { AutoSize = true, Text = "Update now", Visible = false };
    string? updateUrl;

    public MainForm()
    {
        Text = $"Budget v{Updater.Current}";
        Font = new Font("Segoe UI", 10f);
        StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(LogicalToDeviceUnits(420), LogicalToDeviceUnits(540));
        MinimumSize = new Size(LogicalToDeviceUnits(360), LogicalToDeviceUnits(420));

        var addButton = new Button { Text = "Add", AutoSize = true };
        var removeButton = new Button { Text = "Remove", AutoSize = true };
        leftLabel.Font = new Font(Font.FontFamily, 14f, FontStyle.Bold);

        billList.Columns.Add("Bill", LogicalToDeviceUnits(240));
        billList.Columns.Add("Amount", LogicalToDeviceUnits(110), HorizontalAlignment.Right);
        billList.Resize += (_, _) => billList.Columns[0].Width = billList.ClientSize.Width - billList.Columns[1].Width;

        var addRow = new TableLayoutPanel { Dock = DockStyle.Fill, AutoSize = true, ColumnCount = 4, Margin = Padding.Empty };
        addRow.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        addRow.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, LogicalToDeviceUnits(90)));
        addRow.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        addRow.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        addRow.Controls.AddRange(new Control[] { nameBox, amountBox, addButton, removeButton });

        var updateRow = new FlowLayoutPanel { AutoSize = true, Dock = DockStyle.Fill, WrapContents = false, Margin = Padding.Empty };
        updateRow.Controls.AddRange(new Control[] { updateLabel, updateButton });

        var layout = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, Padding = new Padding(LogicalToDeviceUnits(12)) };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));

        void AddRow(Control left, Control? right = null, bool grow = false)
        {
            layout.RowStyles.Add(grow ? new RowStyle(SizeType.Percent, 100) : new RowStyle(SizeType.AutoSize));
            int row = layout.RowStyles.Count - 1;
            layout.Controls.Add(left, 0, row);
            if (right != null) layout.Controls.Add(right, 1, row);
            else layout.SetColumnSpan(left, 2);
        }

        AddRow(new Label { Text = "Monthly income", AutoSize = true, Anchor = AnchorStyles.Left }, incomeBox);
        AddRow(new Label { Text = "Bills", AutoSize = true, Font = new Font(Font, FontStyle.Bold), Margin = new Padding(3, 12, 3, 3) });
        AddRow(billList, grow: true);
        AddRow(addRow);
        AddRow(new Label { Text = "Total bills", AutoSize = true, Anchor = AnchorStyles.Left }, totalLabel);
        AddRow(new Label { Text = "Left over", AutoSize = true, Anchor = AnchorStyles.Left }, leftLabel);
        AddRow(updateRow);
        Controls.Add(layout);

        incomeBox.Text = data.Income == 0 ? "" : data.Income.ToString("0.00");
        incomeBox.TextChanged += (_, _) =>
        {
            data.Income = ParseMoney(incomeBox.Text) ?? 0;
            data.Save();
            Recalculate();
        };
        addButton.Click += (_, _) => AddBill();
        AcceptButton = addButton;
        removeButton.Click += (_, _) => RemoveSelected();
        billList.KeyDown += (_, e) => { if (e.KeyCode == Keys.Delete) RemoveSelected(); };
        updateButton.Click += async (_, _) => await InstallUpdate();

        RefreshBills();
    }

    protected override async void OnShown(EventArgs e)
    {
        base.OnShown(e);
        _ = Updater.CleanupAsync();

        updateLabel.Text = "Checking for updates…";
        try
        {
            var update = await Updater.CheckAsync();
            if (update is null)
            {
                updateLabel.Text = $"Up to date (v{Updater.Current})";
            }
            else
            {
                updateUrl = update.Value.Url;
                updateLabel.Text = $"v{update.Value.Version} is available";
                updateButton.Visible = true;
            }
        }
        catch
        {
            updateLabel.Text = $"Couldn't check for updates (v{Updater.Current})";
        }
    }

    async Task InstallUpdate()
    {
        updateButton.Enabled = false;
        updateLabel.Text = "Downloading update…";
        try
        {
            await Updater.InstallAsync(updateUrl!);
        }
        catch (Exception ex)
        {
            updateLabel.Text = "Update failed: " + ex.Message;
            updateButton.Enabled = true;
        }
    }

    static decimal? ParseMoney(string text) =>
        decimal.TryParse(text, NumberStyles.Currency, CultureInfo.CurrentCulture, out var value) ? value : null;

    void AddBill()
    {
        var amount = ParseMoney(amountBox.Text);
        if (string.IsNullOrWhiteSpace(nameBox.Text)) { nameBox.Focus(); return; }
        if (amount is null) { amountBox.Focus(); amountBox.SelectAll(); return; }

        data.Bills.Add(new Bill { Name = nameBox.Text.Trim(), Amount = amount.Value });
        data.Save();
        RefreshBills();
        nameBox.Clear();
        amountBox.Clear();
        nameBox.Focus();
    }

    void RemoveSelected()
    {
        foreach (var i in billList.SelectedIndices.Cast<int>().OrderByDescending(i => i))
            data.Bills.RemoveAt(i);
        data.Save();
        RefreshBills();
    }

    void RefreshBills()
    {
        billList.BeginUpdate();
        billList.Items.Clear();
        foreach (var bill in data.Bills)
            billList.Items.Add(new ListViewItem(new[] { bill.Name, bill.Amount.ToString("C") }));
        billList.EndUpdate();
        Recalculate();
    }

    void Recalculate()
    {
        var total = data.Bills.Sum(b => b.Amount);
        var left = data.Income - total;
        totalLabel.Text = total.ToString("C");
        leftLabel.Text = left.ToString("C");
        leftLabel.ForeColor = left < 0 ? Color.Firebrick : Color.ForestGreen;
    }
}
